/**
 * This file contains the main class for working with the Lucene search Index
 */

package dev.emailsfts.core

import dev.emailsfts.core.models.*

import java.io.Closeable
import java.nio.file.Path
import java.time.OffsetDateTime

import org.apache.lucene.document.*
import org.apache.lucene.store.FSDirectory
import org.apache.lucene.index.IndexWriter
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.index.DirectoryReader
import org.apache.lucene.queries.mlt.MoreLikeThis
import org.apache.lucene.search.BooleanClause
import org.apache.lucene.search.BooleanQuery
import org.apache.lucene.search.IndexSearcher
import org.apache.lucene.search.Query
import org.apache.lucene.search.ScoreDoc
import org.apache.lucene.search.highlight.Highlighter
import org.apache.lucene.search.highlight.QueryScorer
import org.apache.lucene.search.highlight.SimpleHTMLFormatter
import org.apache.lucene.search.highlight.SimpleSpanFragmenter

import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.core.eq


class LuceneCore(
    private val appConfig: Configuration,
    private val db: Database
) : Closeable {

    private val indexDirectoryPath = Path.of(appConfig.luceneIndexDirPath)

    private val indexDirectory = FSDirectory.open(
        indexDirectoryPath
    )

    // use lateinit to prevent errors when the index doesn't yet exist
    // ensureReaderOpen() must first be called in `search` and other methods that require it
    private lateinit var directoryReader: DirectoryReader
    private lateinit var indexSearcher: IndexSearcher

    private fun ensureReaderOpen() {
        // get reference to directory property to see if it's initialized
        if (!::directoryReader.isInitialized) {
            directoryReader = DirectoryReader.open(indexDirectory)
            indexSearcher = IndexSearcher(directoryReader)
        }  else {
            // index might have changed via `buildIndex` before a `search` call so update the reader here
            val newReader = DirectoryReader.openIfChanged(directoryReader)
            if (newReader != null) {
                directoryReader.close()
                directoryReader = newReader
                indexSearcher = IndexSearcher(directoryReader)
            }
        }
    }

    // implement Closeable to allow with `.use` scope function
    override fun close() {
        if (::directoryReader.isInitialized) directoryReader.close()
        indexDirectory.close()
    }

    /**
     * Checks if both the index directory exists and contains a valid Lucene index
     */
    fun checkIndexExists(): Boolean {
        if (!indexDirectoryPath.toFile().exists())
            return false

        return DirectoryReader.indexExists(indexDirectory)
    }

    fun indexStats(): IndexStatsResult {
        ensureReaderOpen()

        val indexSizeBytes = indexDirectory
            .listAll()
            .sumOf { indexDirectory.fileLength(it) }

        val documentCount = directoryReader.numDocs()

        return IndexStatsResult(
            documentCount = documentCount,
            indexSizeBytes = indexSizeBytes
        )
    }

    fun buildIndex() {

        // The same analyzer must be used at search time for tokenization compatibility,
        // so it has been moved to appConfig with EnglishAnalyzer as the default

        // EnglishAnalyzer provides more English specific tokenization than the StandardAnalyzer
        // such as reducing words to root forms (stemming) which allows searches on semantic similarity (attachment -> attach etc).

        val analyzer = appConfig.luceneAnalyzer
        val config = IndexWriterConfig(analyzer)

        // Create or override existing index
        config.openMode = IndexWriterConfig.OpenMode.CREATE

        val writer = IndexWriter(indexDirectory, config)

        println("Building Lucene Index")

        transaction(db.db) {

            // Get the message count for print logging
            // exposed translates this to a SELECT COUNT(*) query
            val totalMessages = Messages.selectAll().count()

            val batchSize = 100

            Messages.selectAll()
                .fetchBatchedResults(batchSize = batchSize)
                .forEachIndexed { batchIndex, batch ->
                    batch.forEachIndexed { index, resultRow ->

                    val absoluteIndex = batchIndex * batchSize + index

                    println("Creating index document from email message $absoluteIndex of $totalMessages")

                    val doc = Document().apply {

                        // Stored fields are stored and returned with the document and not indexed, others are just built into the index
                        // `mid` was originally a StoredField but we now require it to be indexed so it can be excluded with a Query in findRelated

                        // We can use the message ID `mid` to look up details in the SQL database one we have the document
                        add(IntField("mid", resultRow[Messages.mid], Field.Store.YES))

                        // Text fields are for tokenized full text search,
                        // we don't usually need store all the field values as they can be found in the SQL database via the mid,
                        // however the Lucene highlighter needs them,
                        // alternatively we could make another query to the SQL database for the highlighter
                        add(TextField("subject", resultRow[Messages.subject] ?: "", Field.Store.YES))
                        add(TextField("body", resultRow[Messages.bodyPlain] ?: "", Field.Store.YES))
                        add(TextField("sender", resultRow[Messages.sender], Field.Store.YES))
                        add(TextField("senderName", resultRow[Messages.senderName] ?: "", Field.Store.YES))

                        // KeywordField is similar to StringField in that they are used for filtering and exact search,
                        // except the keyword field also stores the value for sorting and faceting on the field
                        add(KeywordField("messageId", resultRow[Messages.messageId], Field.Store.YES))
                        add(KeywordField("xOrigin", resultRow[Messages.xOrigin] ?: "", Field.Store.YES))
                        add(KeywordField("xFolder", resultRow[Messages.xFolder] ?: "", Field.Store.YES))

                        // Store date field for sorting
                        // Source dates are ISO8601 strings with timezone offsets in the emails and SQLite database,
                        // the tiemzone offsets on these strings prevent sorting by actual time
                        // so we will convert them to epoch milliseconds here as per the reccommendation in lucene docs
                        val epochMillis = OffsetDateTime.parse(resultRow[Messages.date])
                            .toInstant()
                            .toEpochMilli()

                        add(LongField("date", epochMillis, Field.Store.YES))

                        // Add denormalized recipients for to: search
                        val messageRecipientAddresses = Recipients
                            .selectAll()
                            .where { Recipients.mid eq resultRow[Messages.mid] }
                            .joinToString(" ") { it[Recipients.address] }

                        add(TextField("receiver", messageRecipientAddresses, Field.Store.NO))

                        // Add denormalized attachment names for attachment filename search
                        val messageAttachmentNames = Attachments
                            .selectAll()
                            .where { Attachments.mid eq resultRow[Messages.mid] }
                            .joinToString(" ") { it[Attachments.filename] ?: "" }

                        add(TextField("attachments", messageAttachmentNames, Field.Store.NO))


                        // Add denormalized employee names for search by real name
                        val senderEmployeeName = EmployeeEmails
                            .innerJoin(Employees)
                            .selectAll()
                            .where { EmployeeEmails.address eq resultRow[Messages.sender] }
                            .firstOrNull()
                            ?.let { row ->
                                listOfNotNull(
                                    row[Employees.firstName],
                                    row[Employees.lastName],
                                ).joinToString(" ")
                            } ?: ""

                        add(TextField("employeeName", senderEmployeeName, Field.Store.YES))


                        // Debug logging
                        // if (messageAttachmentNames != "") println("AA $messageAttachmentNames")
                        // fields.forEach { println("${it.name()}: ${it.stringValue()?.take(100)}") }
                    }

                    writer.addDocument(doc)

                    // Commit batches every 10,000 entries to optimize system resource usage
                    if ((absoluteIndex + 1) % 10_000 == 0) {
                        writer.commit()
                    }
                }
            }

            writer.commit()
            writer.close()

            val indexSavedPath = Path.of(appConfig.luceneIndexDirPath).toAbsolutePath()
            val indexStats = indexStats()
            println(indexStats)
            println("Index Build Completed - created in directory $indexSavedPath")
        }
    }

    fun search(
        query: Query,
        cursorDoc: ScoreDoc? = null,
        pageSize: Int = appConfig.defaultPageSize
    ): LuceneSearchResult {

        ensureReaderOpen()

        val effectivePageSize = pageSize.coerceAtMost(appConfig.maxPageSize)

        // cursorDoc is the last doc from the last search and is used as a pagination cursor
        val topDocs = if (cursorDoc == null) {
            indexSearcher.search(query, effectivePageSize)
        } else {
            indexSearcher.searchAfter(cursorDoc, query, effectivePageSize)
        }

        val hits = topDocs.scoreDocs

        val luceneHits = hitsToLuceneHits(hits.toList(), query)

        val totalHits = topDocs.totalHits.value()
        val totalPages = ((totalHits + effectivePageSize - 1) / effectivePageSize).toInt()

        return LuceneSearchResult(
            hits = luceneHits,
            totalHits = totalHits,
            totalPages = totalPages,
            lastScoreDoc = topDocs.scoreDocs.lastOrNull()
        )

    }

    fun findRelated(
        emailId: Int,
        documentId: Int,
        cursorDoc: ScoreDoc? = null,
        pageSize: Int = appConfig.defaultPageSize
    ): LuceneSearchResult {

        ensureReaderOpen()

        // this could use a lot more tweaking
        val mlt = MoreLikeThis(directoryReader)
        mlt.analyzer = appConfig.luceneAnalyzer
        mlt.fieldNames = arrayOf("subject", "body")
        mlt.minTermFreq = 1
        mlt.minDocFreq = 1

        val mltQuery = mlt.like(documentId)

        // Exclude the source document
        val query = BooleanQuery.Builder()
            .add(mltQuery, BooleanClause.Occur.MUST)
            .add(
                IntField.newExactQuery(
                    "mid",
                    emailId
                ),
                BooleanClause.Occur.MUST_NOT
            )
            .build()

        return search(query, cursorDoc, pageSize)

    }

    private fun hitsToLuceneHits(
        hits: List<ScoreDoc>,
        query: Query,
    ): List<LuceneHit> {

        // Set up formatter and scorer and use with the hightlighter to highlight matched fragments
        val formatter = SimpleHTMLFormatter("<mark>", "</mark>")
        val scorer = QueryScorer(query)
        val highlighter = Highlighter(formatter, scorer)
        highlighter.textFragmenter = SimpleSpanFragmenter(scorer, 120)

        // Loop through the hits and build a List of our LuceneHit data classes
        val storedFields = indexSearcher.storedFields()
        val luceneHits: List<LuceneHit> = hits.map { hit ->
            val doc = storedFields.document(hit.doc)
            val mailId = doc.getField("mid").numericValue().toInt()

            val highlightedFragments = mutableMapOf<String, List<String>>()
            for (field in listOf("subject", "body", "sender")) {
                val text = doc.get(field) ?: continue
                val tokenStream = appConfig.luceneAnalyzer.tokenStream(field, text)
                val fragments = highlighter.getBestFragments(tokenStream, text, 3)
                if (fragments.isNotEmpty()) {
                    highlightedFragments[field] = fragments.toList()
                }
            }

            LuceneHit(
                luceneDocId = hit.doc,
                score = hit.score,
                emailId = mailId,
                highlightedFragments = highlightedFragments
            )
        }

        return luceneHits
    }

}

data class IndexStatsResult(
    val documentCount: Int,
    val indexSizeBytes: Long,
)

data class LuceneSearchResult(
    val hits: List<LuceneHit>,
    val totalHits: Long,
    val totalPages: Int,
    val lastScoreDoc: ScoreDoc?  // cursor for next page
) {
    val hasNextPage: Boolean get() = lastScoreDoc != null && hits.isNotEmpty()
}

data class LuceneHit(
    val luceneDocId: Int,
    val score: Float,

    val emailId: Int,

    // Map of fieldName -> highlighted fragments
    val highlightedFragments: Map<String, List<String>>
)