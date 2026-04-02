/**
 * This file contains the class responsible for application wide configuration
 */

package dev.emailsfts

data class Configuration(
    val sqliteDbFilePath: String,
    val luceneIndexPath: String,
    val defaultPageSize: Int,
    val maxPageSize: Int,
    val maxMemoryMb: Int,
    val fuzzyMatchDistance: Int,
    val apiPort: Int
)