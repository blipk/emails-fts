/**
 * This file contains the
 */

package dev.emailsfts.core

import dev.emailsfts.core.models.*

import org.apache.lucene.document.*
import org.apache.lucene.index.IndexWriter
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.index.DirectoryReader
import org.apache.lucene.store.FSDirectory

import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.core.eq

import java.nio.file.Path
import java.time.OffsetDateTime

class LuceneIndex(
    private val appConfig: Configuration,
    private val db: Database
) {

    /**
     * Checks if both the index directory exists and contains a valid Lucene index
     */
    fun checkIndexExists(): Boolean {
        val path = Path.of(appConfig.luceneIndexDirPath)

        if (!path.toFile().exists())
            return false

        val indexFileDirectory = FSDirectory.open(path)

        return indexFileDirectory.use { DirectoryReader.indexExists(it) }
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

        val indexFileDirectory = FSDirectory.open(
            Path.of(appConfig.luceneIndexDirPath)
        )

        val writer = IndexWriter(indexFileDirectory, config)

        println("Building Lucene Index")

        transaction(db.db) {

            // Get the message count for print logging
            // exposed translates this to a SELECT COUNT(*) query
            val totalMessages = Messages.selectAll().count()

            val BATCH_SIZE = 100

            Messages.selectAll()
                .fetchBatchedResults(batchSize = BATCH_SIZE)
                .forEachIndexed { batchIndex, batch ->
                    batch.forEachIndexed { index, resultRow ->

                    val absoluteIndex = batchIndex * BATCH_SIZE + index

                    println("Creating index document from message $absoluteIndex of $totalMessages")

                    val doc = Document().apply {


                        // Stored fields are stored and returned with the document, others are just built into the index

                        // We can use the message ID `mid`` to look it up after searches using the index
                        add(StoredField("mid", resultRow[Messages.mid]))

                        // Text fields are for tokenized full text search,
                        // we don't store the field value as it can be found in the SQL database via the mid
                        add(TextField("subject", resultRow[Messages.subject] ?: "", Field.Store.NO))
                        add(TextField("body", resultRow[Messages.bodyPlain] ?: "", Field.Store.NO))
                        add(TextField("sender", resultRow[Messages.sender], Field.Store.NO))
                        add(TextField("senderName", resultRow[Messages.senderName] ?: "", Field.Store.NO))

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
            indexFileDirectory.close()

            val indexSavedPath = Path.of(appConfig.luceneIndexDirPath).toAbsolutePath()
            println("Index Build Completed - created in directory $indexSavedPath")
        }
    }

    // fun search(query: Query, page: Int, pageSize: Int): LuceneSearchResult {

    // }

    // fun findRelated(documentId: Int, maxResults: Int): LuceneSearchResult {
    //     // lucene MoreLikeThis
    // }

}

data class IndexResult(
    val documentCount: Int,
    val indexSizeBytes: Long,
)

data class LuceneSearchResult(
    val hits: List<LuceneHit>,
    val totalHits: Long
)

data class LuceneHit(
    val emailId: Int,
    val score: Float,
    val highlightedFragments: Map<String, List<String>>
)