/**
 * This file contains the class that converts application specific search query strings into those compatible with lucences syntax
 */

package dev.emailsfts.core

import org.apache.lucene.search.Query
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser


class SearchInputParser(
    appConfig: Configuration
) {

    // apply the search term to multiple fields (body + subject + sender)
    // with weights/boosts for each of the search terms
    private val boosts = mapOf(
        "subject" to 2.0f,
        "body" to 1.0f,
        "sender" to 1.5f
    )
    private val luceneQueryParser = MultiFieldQueryParser(
        arrayOf("subject", "body", "sender", "senderName"),
        appConfig.luceneAnalyzer,
        boosts
    )

    fun parse(rawQuery: String): Query {

        // Parse application user friendly field filter names to the exact field names in the lucene fields
        val intermediateQuery = rawQuery
            .replace(Regex("from:"), "sender:")
            .replace(Regex("to:"), "receiver:")
            .replace(Regex("has:attachments"), "hasAttachments:1")

        // handle fuzzy expansion for misspellings options:
        //  - regex to append lucene ~ fuzzy operator to appropriate terms
        //  - override appropriate methods on MultiFieldQueryParser

        // lucene already handles boolean operators (AND, OR, NOT)
        // as well as quoated phrases and other fields that already match the schema
        // the frontend should provide a guide on available fields and lucene query syntax

        // this will throw an error on a bad query,
        // need to add try/catch and bubble areas up to user layer
        return luceneQueryParser.parse(intermediateQuery)

    }
}