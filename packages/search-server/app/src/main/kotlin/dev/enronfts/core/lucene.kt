/**
 * This file contains the
 */

package dev.emailsfts

class LuceneIndex(

) {

    //fun buildIndex, search, findRelated

}

data class LuceneSearchResult(
    val hits: List<LuceneHit>,
    val totalHits: Long
)

data class LuceneHit(
    val emailId: Int,
    val score: Float,
    val highlightedFragments: Map<String, List<String>>
)