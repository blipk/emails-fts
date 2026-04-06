/*
 * The main application class and main loop - handle CLI args and delegates appropriately
 */

package dev.emailsfts

import dev.emailsfts.core.*

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option

class App : CliktCommand() {

    // CLI flags/arguments
    val buildIndex by option(
        "-b",
        "--buildIndex",
        help="builds the Lucene index and exits"
    ).flag()

    val run by option(
        "-r", "--run",
        help = "runs the HTTP API server"
    ).flag()

    val status by option(
        "-i", "--indexStatus",
        help = "returns status and stats of the index"
    ).flag()

    val search by option(
        "-s", "--search",
        help = "performs a search on the lucene index",
    )

    override fun run() {

        println("\n=== Emails FTS ===")

        val numOptionsChosen = listOf(buildIndex, run, status, search != null).count { it }
        if (numOptionsChosen > 1) {
            println("Please only specify one CLI argument at a time")
            return
        }
        if (numOptionsChosen == 0) {
            echo(getFormattedHelp())
            return
        }


        val appConfig = Configuration.DEFAULT
        val sqlDatabase = Database(appConfig)
        val luceneService = LuceneCore(appConfig, sqlDatabase)
        val searchService = SearchCore(appConfig, sqlDatabase, luceneService)

        luceneService.use { luceneService ->

            val indexExists = luceneService.checkIndexExists()

            if (status) {
                if (indexExists) {
                    val stats = luceneService.indexStats()
                    println(stats)
                } else {
                    println("Index not found at ${appConfig.luceneIndexDirPath} - use --buildIndex to create it")
                }
            }

            if (buildIndex) {

                if (indexExists) {

                    println("Lucene index exists - (e)xit or (c)ontinue and overwrite with new index build?")

                    while (true) {
                        when (readlnOrNull()?.trim()?.lowercase()) {
                            "e" -> return
                            "c" -> {
                                luceneService.buildIndex()
                                break
                            }

                            else -> println(
                                "Please enter 'e' or 'c' (exit or continue with overwrite)"
                            )
                        }
                    }

                } else {
                    luceneService.buildIndex()
                }

            }

            if (search != null) {

                if (!indexExists) {
                    println("Index not found at ${appConfig.luceneIndexDirPath} - use --buildIndex to create it")
                    return
                }

                var page = 1
                var previousSearches: List<SearchResult?> = listOf(null)
                var running = true

                while (running) {
                    // ANSI escape sequence to clear screen - doesn't work with gradle as it's capturing stdio
                    // print("\u001b[H\u001b[2J")
                    // System.out.flush()

                    val lastSearch = previousSearches.last()

                    val searchResult = searchService.search(
                        search!!,
                        lastSearch?.luceneResult,
                        appConfig.defaultPageSize,
                        page
                    )
                    val luceneResult = searchResult.luceneResult
                    println("Found ${luceneResult.totalHits} results")

                    for (searchHit in searchResult.searchHits) {
                        val luceneHit = searchHit.luceneHit
                        println(
                            "----- email ID: ${luceneHit.emailId} - sender: ${searchHit.messageRecord.sender}  -----"
                        )
                        if (luceneHit.highlightedFragments.isEmpty()) {
                            val preview = searchHit.messageRecord.bodyPlain?.take(200) ?: ""
                            println(preview)
                        } else {
                            println("${luceneHit.highlightedFragments}")
                        }
                        println("-".repeat(80))
                    }

                    val canGoBack = searchResult.currentPage > 1
                    val canGoForward = searchResult.hasNextPage && page != luceneResult.totalPages

                    if (!canGoBack && !canGoForward) break

                    val options = buildList {
                        if (canGoForward) add("(n)ext page")
                        if (canGoBack) add("(p)revious page")
                        add("(e)xit")
                    }
                    val message = options.joinToString(", ") + "?"
                    println(
                        "Page ${searchResult.currentPage} of ${luceneResult.totalPages} - $message"
                    )

                    var navigated = false
                    while (!navigated) {
                        when (readlnOrNull()?.trim()?.lowercase()) {
                            "e" -> {
                                running = false
                                navigated = true
                            }
                            "n" -> if (canGoForward) {
                                previousSearches = previousSearches + searchResult
                                page++
                                navigated = true
                            }
                            "p" -> if (canGoBack) {
                                previousSearches = previousSearches.dropLast(1)
                                page--
                                navigated = true
                            }
                            else -> echo("Please choose an option: $message")
                        }
                    }
                }
            }

            if (run) {
                println("Not yet implemented!")
                // run API server:
                //  check for index and prompt to build if it doesnt
                //  read settings and run
            }
        }

    }

}


fun main(args: Array<String>) = App().main(args)
