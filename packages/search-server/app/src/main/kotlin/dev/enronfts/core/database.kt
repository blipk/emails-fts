/**
 *  This file contains the class that configures and provides the database connection
 */

package dev.emailsfts

import org.jetbrains.exposed.v1.jdbc.Database

class Database(
    private val appConfig: Configuration
) {

    lateinit var connectionString: String
        private set

    lateinit var db: Database
        private set

    fun init(): Database {
        connectionString = "jdbc:sqlite:${appConfig.sqliteDbFilePath}?journal_mode=WAL&foreign_keys=ON"

        db = Database.connect(connectionString, "org.sqlite.JDBC")

        return db
    }

}