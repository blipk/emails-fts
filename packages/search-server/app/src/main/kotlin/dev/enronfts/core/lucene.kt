/**
 * This file contains the
 */

package dev.emailsfts

import org.apache.lucene.search.Query
import org.apache.lucene.analysis.standard.StandardAnalyzer
import org.apache.lucene.analysis.en.EnglishAnalyzer
import org.apache.lucene.document.*
import org.apache.lucene.index.IndexWriter
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.store.FSDirectory

import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

import java.nio.file.Path
import java.time.OffsetDateTime

class LuceneIndex(
    private val appConfig: Configuration,
    private val db: Database
) {

    fun buildIndex() {

        // The same analyzer must be used at search time for tokenization compatibility,
        // so it has been moved to appConfig with EnglishAnalyzer as the default

        // EnglishAnalyzer provides more English specific tokenization than the StandardAnalyzer
        // such as reducing words to root forms (stemming) which allows searches on semantic similarity (attachment -> attach etc).

        val analyzer = appConfig.luceneAnalyzer
        val config = IndexWriterConfig(analyzer)

        config.openMode = IndexWriterConfig.OpenMode.CREATE

        val indexFileDirectory = FSDirectory.open(
            Path.of(appConfig.luceneIndexFilePath)
        )

        val writer = IndexWriter(indexFileDirectory, config)

        transaction(db.db) {

            // Pre-fetch all recipients grouped by message id
            // and map their addresses to a string that can be indexed by lucene.
            // Used for reciever denormalization from email documents to enable `to:` search
            val recipientsByMid: Map<Int, String> = Recipients
                .selectAll()
                .groupBy { it[Recipients.mid] }
                .mapValues { (_, rows) ->
                    rows.joinToString(" ") { it[Recipients.address] }
                }

            // Do the same for attachments for filename search
            val attachmentsByMid: Map<Int, String> = Attachments
                .selectAll()
                .groupBy { it[Attachments.mid] }
                .mapValues { (_, rows) ->
                    rows.joinToString(" ") { it[Attachments.filename] ?: "" }
                }

            // Pre-fetch employee and address records for real name lookup
            val employeesNamesByAddress: Map<String, String> = EmployeeEmails
                .innerJoin(Employees)
                .selectAll()
                .associate { row ->
                    row[EmployeeEmails.address] to listOfNotNull(
                        row[Employees.firstName],
                        row[Employees.lastName],
                    ).joinToString(" ")
                }

            Messages.selectAll().forEachIndexed { index, resultRow ->

                val doc = Document().apply {

                    // Setting most of these to Store.YES initially but seeing as the data is already in the database,
                    // and we use lookups via the mid, that may not be required

                    // Stored fields are stored and returned with the document, others are just built into the index
                    // we can use the mid to look it up after searches using the index
                    add(StoredField("mid", resultRow[Messages.mid]))

                    // Text fields are for tokenized full text search
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
                    val messageRecipientAddresses = recipientsByMid[resultRow[Messages.mid]] ?: ""
                    add(TextField("receiver", messageRecipientAddresses, Field.Store.NO))

                    // Add denormalized attachment names for attachment filename search
                    val messageAttachmentNames = attachmentsByMid[resultRow[Messages.mid]] ?: ""
                    add(TextField("attachments", messageAttachmentNames, Field.Store.NO))

                    // Add denormalized employee names for search by real name
                    val senderEmployeeName = employeesNamesByAddress[resultRow[Messages.sender]] ?: ""
                    add(TextField("employeeName", senderEmployeeName, Field.Store.YES))
                }

                writer.addDocument(doc)

                // Commit batches every 10,000 entries to optimize system resource usage
                if ((index + 1) % 10_000 == 0) {
                    writer.commit()
                }
            }

            writer.commit()
            writer.close()
            indexFileDirectory.close()
        }
    }

    fun search(query: Query, page: Int, pageSize: Int): LuceneSearchResult {

    }

    fun findRelated(documentId: Int, maxResults: Int): LuceneSearchResult {
        // lucene MoreLikeThis
    }

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