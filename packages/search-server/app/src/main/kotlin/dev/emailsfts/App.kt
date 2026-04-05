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

    val search by option(
        "-c", "--search",
        help = "performs a search on the lucene index",
    )

    override fun run() {
        if (buildIndex && run) {
            println("Please only specify one of `--buildIndex`` or `--run`")
            return
        }

        val appConfig = Configuration.DEFAULT

        if (buildIndex) {

            val sqlDatabase = Database(appConfig)
            val luceneIndex = LuceneIndex(appConfig, sqlDatabase)

            val indexExists = luceneIndex.checkIndexExists()

            if (indexExists) {


                println("Lucene index exists - (e)xit or (c)ontinue and overwrite with new index build")

                // this is kinda ugly but it works and I'm just playing around with Kotlin unique features
                while ( readlnOrNull()?.trim()?.lowercase().let  {
                    when (it) {
                        "e" -> { return }
                        "c" -> {
                            luceneIndex.buildIndex()
                            false
                        }
                        else -> {
                            println("Please enter 'e' or 'c' (exit or continue with overwrite)")
                            true
                        }
                    }
                }) {}

            } else {
                luceneIndex.buildIndex()
            }

            return
        }

        if (run) {
            // run API server:
            //  check for index and prompt to build if it doesnt
            //  read settings and run
            return
        }

    }

}


fun main(args: Array<String>) = App().main(args)
