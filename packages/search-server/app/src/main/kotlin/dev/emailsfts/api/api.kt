/**
 * This file contains the API server that is a bridge between the Search class and web client
 */

package dev.emailsfts.api

import dev.emailsfts.core.*

class Api(
    searchCore: SearchCore,
    exporter: SearchExporter,
    config: Configuration
) {
    // endpoints: fun search, email, thread, related, export

    // also serve front end static at "/"
}