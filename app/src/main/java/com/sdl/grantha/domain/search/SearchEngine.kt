package com.sdl.grantha.domain.search

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Local search engine for grantha text content.
 * Port of smart_search() and fuzzy_search() from the Python web app.
 *
 * All searching happens on-device — no server calls.
 */
object SearchEngine {
    private val PAGE_PATTERN = Regex("\\{\\[\\(\\d+\\)\\]\\}")
    private val WHITESPACE_PATTERN = Regex("\\s+")

    class PreparedText(
        val cleanText: CharSequence,
        val cleanToOrigIndex: IntArray,
        val cleanCount: Int,
        val pageMap: List<Pair<Int, Int>>
    )

    /**
     * High-performance single-pass scanner that prepares text for searching.
     * Replaces multiple regex passes with a single character-by-character scan.
     */
    fun prepareText(textContent: String): PreparedText {
        val pageMap = mutableListOf<Pair<Int, Int>>()
        val cleanChars = StringBuilder(textContent.length)
        val cleanToOrigIndex = IntArray(textContent.length)
        var cleanCount = 0

        var i = 0
        val len = textContent.length
        while (i < len) {
            val ch = textContent[i]

            // Fast check for page marker: {[(123)]}
            if (ch == '{' && i + 5 < len && textContent[i+1] == '[' && textContent[i+2] == '(') {
                val end = textContent.indexOf(")]}", i + 3)
                if (end != -1) {
                    val pageStr = textContent.substring(i + 3, end)
                    val pageNum = pageStr.toIntOrNull()
                    if (pageNum != null) {
                        // Mark the end of the marker as the start position for this page
                        pageMap.add(Pair(end + 3, pageNum))
                        i = end + 3
                        continue
                    }
                }
            }

            // Fast path for common whitespace and hyphens
            if (ch == ' ' || ch == '-' || ch == '\n' || ch == '\r' || ch == '\t') {
                i++
                continue
            }

            // Fallback for other Unicode whitespace
            if (ch.isWhitespace()) {
                i++
                continue
            }

            // Manual lowercase for ASCII to bypass expensive lowercaseChar()
            if (ch in 'A'..'Z') {
                cleanChars.append((ch.code + 32).toChar())
            } else {
                cleanChars.append(ch.lowercaseChar())
            }
            
            cleanToOrigIndex[cleanCount++] = i
            i++
        }
        return PreparedText(cleanChars, cleanToOrigIndex, cleanCount, pageMap)
    }

    /**
     * Build a map of character indices to page numbers.
     * (Deprecated: Use prepareText for better performance)
     */
    fun getPageMap(textContent: String): List<Pair<Int, Int>> {
        val pageMap = mutableListOf<Pair<Int, Int>>()
        for (match in PAGE_PATTERN.findAll(textContent)) {
            val pageNum = match.groupValues[1].toIntOrNull() ?: continue
            pageMap.add(Pair(match.range.last + 1, pageNum))
        }
        return pageMap
    }

    /**
     * Find the page number for a given character index.
     */
    fun getPageForIndex(index: Int, pageMap: List<Pair<Int, Int>>): Int {
        var currentPage = 0
        for ((startPos, pageNum) in pageMap) {
            if (startPos <= index) {
                currentPage = pageNum
            } else {
                break
            }
        }
        return currentPage
    }

    /**
     * Get the sub-book name for a given page number.
     */
    fun getSubBookForPage(page: Int, subBooks: List<SubBookInfo>): String? {
        if (subBooks.isEmpty() || page <= 0) return null
        return subBooks.firstOrNull { page in it.startPage..it.endPage }?.name
    }

    data class SubBookInfo(val startPage: Int, val endPage: Int, val name: String)

    /**
     * Parse a Books: field string into SubBookInfo list.
     * Format: "1-50:BookName1, 51-120:BookName2"
     */
    fun parseSubBooks(booksRaw: String): List<SubBookInfo> {
        if (booksRaw.isBlank()) return emptyList()
        return booksRaw.split(",").mapNotNull { entry ->
            val trimmed = entry.trim()
            val colonIdx = trimmed.indexOf(':')
            if (colonIdx < 0) return@mapNotNull null
            val rangePart = trimmed.take(colonIdx).trim()
            val name = trimmed.substring(colonIdx + 1).trim()
            if (name.isBlank()) return@mapNotNull null
            try {
                if ('-' in rangePart) {
                    val parts = rangePart.split('-', limit = 2)
                    SubBookInfo(parts[0].trim().toInt(), parts[1].trim().toInt(), name)
                } else {
                    val page = rangePart.toInt()
                    SubBookInfo(page, page, name)
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    /**
     * Smart search: exact match ignoring spaces and hyphens.
     */
    fun smartSearch(
        textContent: String,
        query: String,
        granthaName: String = "",
        subBooks: List<SubBookInfo> = emptyList(),
        customRules: Map<String, String>? = null,
        maxResults: Int = 0
    ): List<SearchResult> {
        val prepared = prepareText(textContent)
        return smartSearchInternal(prepared, textContent, query, granthaName, subBooks, customRules, maxResults)
    }

    fun smartSearchInternal(
        prepared: PreparedText,
        textContent: String,
        query: String,
        granthaName: String,
        subBooks: List<SubBookInfo>,
        customRules: Map<String, String>?,
        maxResults: Int
    ): List<SearchResult> {
        if (query.isBlank()) return emptyList()

        val queryVariants = SanskritUtils.getCustomVariants(query, customRules)
        val queryVariantsClean = queryVariants
            .map { SanskritUtils.removeSpacesAndHyphens(it).lowercase() }
            .filter { it.isNotEmpty() }

        if (queryVariantsClean.isEmpty()) return emptyList()

        val results = mutableListOf<SearchResult>()
        val seenRegions = mutableSetOf<Long>()

        for (qClean in queryVariantsClean) {
            var startPos = 0
            while (true) {
                val matchIdx = prepared.cleanText.indexOf(qClean, startPos)
                if (matchIdx == -1) break
                
                val origIdx = prepared.cleanToOrigIndex[matchIdx]
                val pageNum = getPageForIndex(origIdx, prepared.pageMap)
                
                val regionKey = (pageNum.toLong() shl 32) or (origIdx / 200).toLong()
                if (regionKey !in seenRegions) {
                    seenRegions.add(regionKey)
                    
                    val contextStart = max(0, origIdx - 100)
                    val origEnd = prepared.cleanToOrigIndex[matchIdx + qClean.length - 1]
                    val contextEnd = min(textContent.length, origEnd + 100)
                    val rawContext = textContent.substring(contextStart, contextEnd)

                    val cleanContext = rawContext.replace(PAGE_PATTERN, " ").replace(WHITESPACE_PATTERN, " ").trim()
                    val subBook = getSubBookForPage(pageNum, subBooks)

                    results.add(
                        SearchResult(
                            granthaName = granthaName,
                            page = pageNum,
                            contextText = "...$cleanContext...",
                            subBook = subBook
                        )
                    )
                }
                
                if (maxResults > 0 && results.size >= maxResults) break
                startPos = matchIdx + 1
            }
            if (maxResults > 0 && results.size >= maxResults) break
        }
        return results.sortedBy { it.page }
    }

    /**
     * Fuzzy search using Levenshtein edit distance.
     */
    fun fuzzySearch(
        textContent: String,
        query: String,
        errorPct: Int = 20,
        granthaName: String = "",
        subBooks: List<SubBookInfo> = emptyList(),
        customRules: Map<String, String>? = null,
        maxResults: Int = 0
    ): List<SearchResult> {
        val prepared = prepareText(textContent)
        return fuzzySearchInternal(prepared, textContent, query, errorPct, granthaName, subBooks, customRules, maxResults)
    }

    fun fuzzySearchInternal(
        prepared: PreparedText,
        textContent: String,
        query: String,
        errorPct: Int,
        granthaName: String,
        subBooks: List<SubBookInfo>,
        customRules: Map<String, String>?,
        maxResults: Int
    ): List<SearchResult> {
        if (query.isBlank()) return emptyList()

        val queryVariants = SanskritUtils.getCustomVariants(query, customRules)
        val queryVariantsClean = queryVariants
            .map { SanskritUtils.removeSpacesAndHyphens(it).lowercase() }
            .filter { it.length >= 2 }

        if (queryVariantsClean.isEmpty()) return emptyList()

        val baseQueryLen = queryVariantsClean[0].length
        val maxErrors = max(1, ceil(baseQueryLen * errorPct / 100.0).toInt())

        val results = mutableListOf<SearchResult>()
        val seenRegions = mutableSetOf<Long>()

        val windowMin = max(1, baseQueryLen - maxErrors)
        val windowMax = baseQueryLen + maxErrors

        for (winSize in windowMin..windowMax) {
            val limit = prepared.cleanCount - winSize
            if (limit < 0) continue
            
            for (i in 0..limit) {
                var minDist = Int.MAX_VALUE
                for (qClean in queryVariantsClean) {
                    if (kotlin.math.abs(winSize - qClean.length) > maxErrors) continue
                    val dist = SanskritUtils.levenshteinDistance(qClean, prepared.cleanText, i, i + winSize, maxErrors)
                    if (dist < minDist) minDist = dist
                }

                if (minDist <= maxErrors) {
                    val origIdx = prepared.cleanToOrigIndex[i]
                    val pageNum = getPageForIndex(origIdx, prepared.pageMap)
                    val regionKey = (pageNum.toLong() shl 32) or (origIdx / 200).toLong()
                    if (regionKey in seenRegions) continue
                    seenRegions.add(regionKey)

                    val contextStart = max(0, origIdx - 100)
                    val origEnd = prepared.cleanToOrigIndex[i + winSize - 1]
                    val contextEnd = min(textContent.length, origEnd + 100)
                    val rawContext = textContent.substring(contextStart, contextEnd)
                    val cleanContext = rawContext.replace(PAGE_PATTERN, " ").replace(Regex("\\s+"), " ").trim()
                    val subBook = getSubBookForPage(pageNum, subBooks)

                    results.add(
                        SearchResult(
                            granthaName = granthaName,
                            page = pageNum,
                            contextText = "...$cleanContext...",
                            subBook = subBook
                        )
                    )
                    if (maxResults > 0 && results.size >= maxResults) return results
                }
            }
        }
        return results
    }

    /**
     * Search across multiple granthas with tag filtering.
     */
    fun searchAll(
        granthaTexts: Map<String, Triple<String, String, String>>,
        textQueries: List<String>,
        textLogic: String = "or",
        fuzzyPct: Int = 0,
        tagQueries: List<String> = emptyList(),
        tagsLogic: String = "or",
        negativeTagQueries: List<String> = emptyList(),
        customRules: Map<String, String>? = null,
        maxPerBook: Int = 0,
        stopAtFirstMatch: Boolean = false,
        onProgress: ((searched: Int, total: Int, bookName: String) -> Unit)? = null
    ): List<SearchResult> {
        val allResults = mutableListOf<SearchResult>()
        val total = granthaTexts.size
        var searched = 0

        for ((granthaName, triple) in granthaTexts) {
            val (textContent, tags, booksRaw) = triple
            if (!matchesTags(tags.lowercase(), granthaName.lowercase(), tagQueries, tagsLogic, negativeTagQueries)) {
                searched++; onProgress?.invoke(searched, total, granthaName); continue
            }

            val subBooks = parseSubBooks(booksRaw)
            if (textQueries.isNotEmpty()) {
                val prepared = prepareText(textContent)
                if (textLogic == "and") {
                    val allQueryResults = mutableListOf<SearchResult>()
                    var hasAll = true
                    for (q in textQueries) {
                        val matches = if (fuzzyPct > 0) {
                            fuzzySearchInternal(prepared, textContent, q, fuzzyPct, granthaName, subBooks, customRules, if (stopAtFirstMatch) 1 else maxPerBook)
                        } else {
                            smartSearchInternal(prepared, textContent, q, granthaName, subBooks, customRules, if (stopAtFirstMatch) 1 else maxPerBook)
                        }
                        if (matches.isEmpty()) { hasAll = false; break }
                        allQueryResults.addAll(matches)
                        if (stopAtFirstMatch) break
                    }
                    if (hasAll) allResults.addAll(allQueryResults.sortedBy { it.page })
                } else {
                    for (q in textQueries) {
                        val matches = if (fuzzyPct > 0) {
                            fuzzySearchInternal(prepared, textContent, q, fuzzyPct, granthaName, subBooks, customRules, if (stopAtFirstMatch) 1 else maxPerBook)
                        } else {
                            smartSearchInternal(prepared, textContent, q, granthaName, subBooks, customRules, if (stopAtFirstMatch) 1 else maxPerBook)
                        }
                        allResults.addAll(matches)
                        if (stopAtFirstMatch && matches.isNotEmpty()) break
                    }
                }
            }
            searched++; onProgress?.invoke(searched, total, granthaName)
        }
        return allResults
    }

    private fun matchesTags(
        tags: String,
        bookName: String,
        tagQueries: List<String>,
        tagsLogic: String,
        negativeTagQueries: List<String>
    ): Boolean {
        // Check negative tags
        if (negativeTagQueries.isNotEmpty()) {
            if (negativeTagQueries.any { it in tags || it in bookName }) return false
        }

        // Check positive tags
        if (tagQueries.isEmpty()) return true

        return if (tagsLogic == "and") {
            tagQueries.all { it in tags || it in bookName }
        } else {
            tagQueries.any { it in tags || it in bookName }
        }
    }

    /**
     * Add highlighting markup to search result text.
     * Returns text with <b> tags around matched terms.
     */
    fun highlightText(
        text: String,
        searchTerms: List<String>,
        fuzzyPct: Int = 0,
        customRules: Map<String, String>? = null
    ): String {
        if (searchTerms.isEmpty()) return text

        // Expand search terms with custom rule variants
        val allTerms = if (customRules != null) {
            searchTerms.flatMap { SanskritUtils.getCustomVariants(it, customRules) }.distinct()
        } else {
            searchTerms
        }

        if (fuzzyPct > 0) {
            // For fuzzy highlighting, do a simple substring-based approach
            var result = text
            for (term in allTerms) {
                val termClean = SanskritUtils.removeSpacesAndHyphens(term).lowercase()
                if (termClean.length < 2) continue
                val maxErrors = max(1, ceil(termClean.length * fuzzyPct / 100.0).toInt())

                // Simple approach: find substrings in the text that are close matches
                val textLower = result.lowercase()
                val windowSize = termClean.length
                val replacements = mutableListOf<Pair<IntRange, String>>()

                for (winSize in max(1, windowSize - maxErrors)..(windowSize + maxErrors)) {
                    for (i in 0..(textLower.length - winSize)) {
                        val window = textLower.substring(i, i + winSize)
                        val dist = SanskritUtils.levenshteinDistance(termClean, window, maxErrors)
                        if (dist <= maxErrors) {
                            val original = result.substring(i, i + winSize)
                            // Check no overlap with existing replacements
                            val range = i until (i + winSize)
                            if (replacements.none { !it.first.intersectRange(range).isEmpty() }) {
                                replacements.add(Pair(range, original))
                            }
                        }
                    }
                }

                // Apply replacements from end to start
                for ((range, original) in replacements.sortedByDescending { it.first.first }) {
                    result = result.take(range.first) +
                            "【$original】" +
                            result.substring(range.last + 1)
                }
            }
            return result
        }

        // Exact highlighting with noise pattern
        val noisePattern = "[\\s\\-]*"
        val regexes = allTerms.mapNotNull { term ->
            val trimmed = term.trim()
            if (trimmed.isEmpty()) return@mapNotNull null
            val termClean = SanskritUtils.removeSpacesAndHyphens(trimmed)
            buildString {
                for (i in termClean.indices) {
                    append(Regex.escape(termClean[i].toString()))
                    if (i < termClean.length - 1) append(noisePattern)
                }
            }
        }

        if (regexes.isEmpty()) return text

        return try {
            val combinedRegex = Regex(
                regexes.joinToString("|") { "($it)" },
                setOf(RegexOption.IGNORE_CASE)
            )
            combinedRegex.replace(text) { matchResult ->
                "【${matchResult.value}】"
            }
        } catch (_: Exception) {
            text
        }
    }

    private fun IntRange.intersectRange(other: IntRange): IntRange {
        val start = maxOf(this.first, other.first)
        val end = minOf(this.last, other.last)
        return if (start <= end) start..end else IntRange.EMPTY
    }
}
