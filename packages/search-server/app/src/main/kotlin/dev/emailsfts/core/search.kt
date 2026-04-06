/**
 * This file contains the core Search class (and associated classes) that integrat with Lucene/SQLite and provides the application core user functionality
 */

package dev.emailsfts.core

import dev.emailsfts.core.models.Messages
import dev.emailsfts.core.models.MessageRecord
import dev.emailsfts.core.models.RecipientRecord
import dev.emailsfts.core.models.Recipients

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction


class SearchCore(
    private val appConfig: Configuration,
    private val db: Database,
    private val lucene: LuceneCore
) {

    private val searchInputParser = SearchInputParser(appConfig)

    fun search(
        inputQueryText: String,
        lastSearch: LuceneSearchResult? = null,
        pageSize: Int = appConfig.defaultPageSize,

        // This is currently managed by whatever wraps this, currently the CLI, or API
        // possibly could have the wrapper here and use elsewhere,
        // but may get complicated with both CLI/API using it
        pageTracker: Int = 1
    ): SearchResult {

        val query = searchInputParser.parse(inputQueryText)

        val cursorDoc = lastSearch?.lastScoreDoc

        val luceneSearchResult = lucene.search(
            query,
            cursorDoc,
            pageSize
        )

        val searchHits = buildList {
            for (hit in luceneSearchResult.hits) {
                add(
                    SearchHit(
                        luceneHit = hit,
                        // N + 1 on SQLite but it handles it well and pre-fetching pushes system memory constraints
                        messageRecord = getMessage(hit.messageId)!!,
                        recipientRecords = getRecipients(hit.messageId)
                    )
                )
            }
        }

        return SearchResult(
            luceneResult = luceneSearchResult,
            searchHits = searchHits,
            currentPage = pageTracker
        )

    }

    fun findRelated(messageId: Int) {
        // get lucene doc id from email id and pass to Lucene.findRelated
    }

    fun getMessage(messageId: Int): MessageRecord? {
        return getMessages(listOf(messageId)).firstOrNull()
    }

    fun getMessages(messageIds: List<Int>): List<MessageRecord> {

        fun toMessageRecord(resultRow: ResultRow): MessageRecord {
            return MessageRecord(
                mid = resultRow[Messages.mid],
                messageId = resultRow[Messages.messageId],
                inReplyTo = resultRow[Messages.inReplyTo],
                inReplyToResolved = resultRow[Messages.inReplyToResolved],
                date = resultRow[Messages.date],
                sender = resultRow[Messages.sender],
                senderName = resultRow[Messages.senderName],
                subject = resultRow[Messages.subject],
                bodyPlain = resultRow[Messages.bodyPlain],
                bodyHtml = resultRow[Messages.bodyHtml],
                contentType = resultRow[Messages.contentType],
                charset = resultRow[Messages.charset],
                xOrigin = resultRow[Messages.xOrigin],
                xFolder = resultRow[Messages.xFolder],
                sourcePath = resultRow[Messages.sourcePath],
                hasAttachments = resultRow[Messages.hasAttachments] != 0,
                rawHeaders = resultRow[Messages.rawHeaders],
            )
        }

        val messageRecords = transaction(db.db) {
            Messages.selectAll()
            .where { Messages.mid inList messageIds }
            .map { row -> toMessageRecord(row) }
        }

        return messageRecords
    }

    fun getThread(messageId: Int) {
        /*

        Check in_reply_to first (MIME header),
        fall back to in_reply_to_resolved (backfilled FK from quote parsing)

        Also walk thread_reference (MIME References header)
        and message_reference (resolved quoted blocks)

        for the full chain, ordered by date.
        */
    }

    fun getRecipients(messageId: Int): List<RecipientRecord> {
        return getRecipients(listOf(messageId))
    }

    fun getRecipients(messageIds: List<Int>): List<RecipientRecord> {

        fun toRecipientRecord(resultRow: ResultRow): RecipientRecord {
            return RecipientRecord(
                rid = resultRow[Recipients.rid],
                mid = resultRow[Recipients.mid],
                rtype = resultRow[Recipients.rtype],
                address = resultRow[Recipients.address],
                displayName = resultRow[Recipients.displayName],
            )
        }

        return transaction(db.db) {
            Recipients.selectAll()
                .where { Recipients.mid inList messageIds }
                .map { row -> toRecipientRecord(row) }
        }
    }

}

// Might be better to use an interface for this and LuceneSearchResult,
// but for now I will use composition
data class SearchResult(
    // should probably decompose this and just use the ScoreDoc cursors
    val luceneResult: LuceneSearchResult,

    val searchHits: List<SearchHit>,
    val currentPage: Int,
)  {
    val hasNextPage: Boolean get() =
        luceneResult.lastScoreDoc != null && luceneResult.hits.isNotEmpty()
}

data class SearchHit(
    val luceneHit: LuceneHit,
    val messageRecord: MessageRecord,
    val recipientRecords: List<RecipientRecord>
)