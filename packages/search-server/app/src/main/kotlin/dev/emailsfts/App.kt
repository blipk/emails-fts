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

        if (listOf(buildIndex, run, status, search != null).count { it } > 1) {
            println("Please only specify one CLI argument at a time")
            return
        }

        val appConfig = Configuration.DEFAULT
        val sqlDatabase = Database(appConfig)
        val luceneService = LuceneCore(appConfig, sqlDatabase)
        val searchService = SearchCore(appConfig, sqlDatabase, luceneService)

        luceneService.use { luceneService ->

            if (status) {
                val indexExists = luceneService.checkIndexExists()
                if (indexExists) {
                    val stats = luceneService.indexStats()
                    println(stats)
                } else {
                    println("Index not found at ${appConfig.luceneIndexDirPath} - use --buildIndex to create it")
                }

            }

            if (buildIndex) {
                val indexExists = luceneService.checkIndexExists()

                if (indexExists) {

                    println("Lucene index exists - (e)xit or (c)ontinue and overwrite with new index build")

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
                val searchRresult = searchService.search(search!!)
                print(searchRresult)
            }

            if (run) {
                // run API server:
                //  check for index and prompt to build if it doesnt
                //  read settings and run
            }
        }

    }

}


fun main(args: Array<String>) = App().main(args)
