/**
 *  This file contains the class that configures and provides the database connection
 */

package dev.emailsfts.core

import org.jetbrains.exposed.v1.jdbc.Database

class Database(
    appConfig: Configuration
) {

    var connectionString = "jdbc:sqlite:${appConfig.sqliteDbFilePath}?journal_mode=WAL&foreign_keys=ON"
    var db: Database = Database.connect(connectionString, "org.sqlite.JDBC")

}