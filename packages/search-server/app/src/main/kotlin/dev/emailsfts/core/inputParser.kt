/**
 * This file contains the class that converts application specific search query strings into those compatible with lucences syntax
 */

package dev.emailsfts.core

import org.apache.lucene.search.Query
import org.apache.lucene.search.FuzzyQuery
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

    // Create an object instance that extends the Lucene parser
    // and overrides newTermQuery to turn term queries into terms with fuzzy expansion
    private val luceneQueryParser = object : MultiFieldQueryParser(
        arrayOf("subject", "body", "sender", "senderName"),
        appConfig.luceneAnalyzer,
        boosts
    ) {
        override fun newTermQuery(term: org.apache.lucene.index.Term, boost: Float) =
            if (appConfig.fuzzyMatchDistance > 0)
                FuzzyQuery(term, appConfig.fuzzyMatchDistance)
            else
                super.newTermQuery(term, boost)

        // These could also be useful to change in the future
        override fun getWildcardQuery(field: String, termStr: String) =
            super.getWildcardQuery(field, termStr)

        override fun getFuzzyQuery(field: String, termStr: String, minSimilarity: Float) =
            super.getFuzzyQuery(field, termStr, minSimilarity)
    }

    fun parse(rawQuery: String): Query {

        // Parse application user friendly field filter names to the exact field names in the lucene fields
        val intermediateQuery = rawQuery
            .replace(Regex("from:"), "sender:")
            .replace(Regex("to:"), "receiver:")
            .replace(Regex("has:attachments"), "hasAttachments:1")

        // lucene already handles boolean operators (AND, OR, NOT)
        // as well as quoted phrases and other fields that already match the schema
        // the frontend should provide a guide on available fields and lucene query syntax

        // this will throw an error on a bad query,
        // need to add try/catch and bubble errors up to user layer
        return luceneQueryParser.parse(intermediateQuery)

    }
}