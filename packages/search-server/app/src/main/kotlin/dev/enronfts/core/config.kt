/**
 * This file contains the class responsible for application wide configuration
 */

package dev.emailsfts

import org.apache.lucene.analysis.StopwordAnalyzerBase;
import org.apache.lucene.analysis.en.EnglishAnalyzer;

/**
 * Holds the application configuration.
 * relative paths will be relative to application root `search-serer/app`
 **/
data class Configuration(
    val sqliteDbFilePath: String,
    val luceneIndexFilePath: String,

    val luceneAnalyzer: StopwordAnalyzerBase,

    val defaultPageSize: Int,
    val maxPageSize: Int,
    val maxMemoryMb: Int,
    val fuzzyMatchDistance: Int,
    val apiPort: Int
) {

    // Kotlin companion objects are sort of like static members
    companion object {
        val DEFAULT = Configuration(
            sqliteDbFilePath = "./../../data-ingest/enron_emails.db",
            luceneIndexFilePath = "./data/lucene-index",
            luceneAnalyzer = EnglishAnalyzer(),
            defaultPageSize = 25,
            maxPageSize = 100,
            maxMemoryMb = 256,
            fuzzyMatchDistance = 2,
            apiPort = 8080
        )
    }
}

