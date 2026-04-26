package com.sdl.grantha.domain.search

/**
 * Represents a single search result within a grantha.
 */
data class SearchResult(
    val granthaName: String,
    val page: Int,
    val contextText: String,
    val highlightedText: String = "",
    val subBook: String? = null,
    val isLimited: Boolean = false,
    val matchOffset: Int = -1
)
