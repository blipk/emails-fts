/**
 * This file contains the class responsible for application wide configuration
 */

package dev.emailsfts.core

import org.apache.lucene.analysis.StopwordAnalyzerBase;
import org.apache.lucene.analysis.en.EnglishAnalyzer;

/**
 * Holds the application configuration.
 * relative paths will be relative to application root `search-serer/app/`
 **/
data class Configuration(
    val sqliteDbFilePath: String,
    val luceneIndexDirPath: String,

    val luceneAnalyzer: StopwordAnalyzerBase,
    val defaultPageSize: Int,
    val maxPageSize: Int,
    val fuzzyMatchDistance: Int,

    val apiPort: Int
) {

    // Kotlin companion objects are sort of like static members
    companion object {
        val DEFAULT = Configuration(
            sqliteDbFilePath = "./../../data-ingest/enron_emails.db",
            luceneIndexDirPath = "./data/lucene-index",
            luceneAnalyzer = EnglishAnalyzer(),
            defaultPageSize = 25,
            maxPageSize = 100,
            fuzzyMatchDistance = 0,
            apiPort = 8080
        )
    }
}
