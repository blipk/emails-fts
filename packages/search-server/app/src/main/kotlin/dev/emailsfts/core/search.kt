/**
 * This file contains the core Search class (and associated classes) that integrat with Lucene/SQLite and provides the application core user functionality
 */

package dev.emailsfts.core


class SearchCore(
    appConfig: Configuration,
    private val db: Database,
    private val lucene: LuceneCore
) {

    private val searchInputParser = SearchInputParser(appConfig)

    fun search(inputQueryText: String) {

        val query = searchInputParser.parse(inputQueryText)

        val searchResult = lucene.search(query)

        println ("Found ${searchResult.totalHits} results")

        for (hit in searchResult.hits) {
            println(hit)
        }

    }

    fun findRelated(emailId: Int) {
        // get lucene doc id from email id and pass to Lucene.findRelated
    }

    fun getEmail(emailId: Int) {
        // get email from SQL
    }

    fun getThread(emailId: Int) {
        /*

        Check in_reply_to first (MIME header),
        fall back to in_reply_to_resolved (backfilled FK from quote parsing)

        Also walk thread_reference (MIME References header)
        and message_reference (resolved quoted blocks)

        for the full chain, ordered by date.
        */
    }

}