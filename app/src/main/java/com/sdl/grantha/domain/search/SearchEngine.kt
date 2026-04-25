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

    // Page marker pattern: {[(123)]}
    private val PAGE_PATTERN = Regex("\\{\\[\\((\\d+)\\)\\]\\}")

    /**
     * Build a map of character indices to page numbers.
     * Returns list of (charIndex, pageNumber) sorted by index.
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
     * Optimized version using clean text indexing and native indexOf.
     */
    fun smartSearch(
        textContent: String,
        query: String,
        granthaName: String = "",
        subBooks: List<SubBookInfo> = emptyList(),
        customRules: Map<String, String>? = null,
        maxResults: Int = 0
    ): List<SearchResult> {
        if (query.isBlank()) return emptyList()

        // Generate variants from custom rules
        val queryVariants = SanskritUtils.getCustomVariants(query, customRules)
        val queryVariantsClean = queryVariants
            .map { SanskritUtils.removeSpacesAndHyphens(it).lowercase() }
            .filter { it.isNotEmpty() }

        if (queryVariantsClean.isEmpty()) return emptyList()

        // Build page map and clean text once
        val pageMap = getPageMap(textContent)
        val cleanChars = StringBuilder(textContent.length)
        val cleanToOrigIndex = IntArray(textContent.length)
        var cleanCount = 0

        val pageMarkerRegex = Regex("\\{\\[\\(\\d+\\)\\]\\}")
        var lastEnd = 0
        for (match in pageMarkerRegex.findAll(textContent)) {
            for (idx in lastEnd until match.range.first) {
                val ch = textContent[idx]
                if (!ch.isWhitespace() && ch != '-') {
                    cleanChars.append(ch.lowercaseChar())
                    cleanToOrigIndex[cleanCount++] = idx
                }
            }
            lastEnd = match.range.last + 1
        }
        for (idx in lastEnd until textContent.length) {
            val ch = textContent[idx]
            if (!ch.isWhitespace() && ch != '-') {
                cleanChars.append(ch.lowercaseChar())
                cleanToOrigIndex[cleanCount++] = idx
            }
        }

        val cleanText = cleanChars.toString()
        val results = mutableListOf<SearchResult>()
        val seenRegions = mutableSetOf<Long>()

        for (qClean in queryVariantsClean) {
            var startPos = 0
            while (true) {
                val matchIdx = cleanText.indexOf(qClean, startPos)
                if (matchIdx == -1) break
                
                // Map back to original
                val origIdx = cleanToOrigIndex[matchIdx]
                val pageNum = getPageForIndex(origIdx, pageMap)
                
                // Deduplicate by region
                val regionKey = (pageNum.toLong() shl 32) or (origIdx / 200).toLong()
                if (regionKey !in seenRegions) {
                    seenRegions.add(regionKey)
                    
                    // Extract context
                    val contextStart = max(0, origIdx - 100)
                    val origEnd = cleanToOrigIndex[matchIdx + qClean.length - 1]
                    val contextEnd = min(textContent.length, origEnd + 100)
                    val rawContext = textContent.substring(contextStart, contextEnd)

                    val cleanContext = rawContext
                        .replace(PAGE_PATTERN, " ")
                        .replace(Regex("\\s+"), " ")
                        .trim()

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
     * Port of fuzzy_search() from Python.
     *
     * @param textContent The full grantha text
     * @param query The search query
     * @param errorPct Percentage of character differences allowed (0-50)
     * @param maxResults Maximum number of results (0 = unlimited)
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
        if (query.isBlank()) return emptyList()

        // Generate variants
        val queryVariants = SanskritUtils.getCustomVariants(query, customRules)
        val queryVariantsClean = queryVariants
            .map { SanskritUtils.removeSpacesAndHyphens(it).lowercase() }
            .filter { it.length >= 2 }

        if (queryVariantsClean.isEmpty()) return emptyList()

        val baseQueryLen = queryVariantsClean[0].length
        val maxErrors = max(1, ceil(baseQueryLen * errorPct / 100.0).toInt())

        // Build page map from original text
        val pageMap = getPageMap(textContent)

        // Build clean text (no page markers, no spaces/hyphens) with index mapping
        // Pre-allocate to avoid re-allocations
        val cleanChars = StringBuilder(textContent.length)
        val cleanToOrigIndex = IntArray(textContent.length)
        var cleanCount = 0

        val pageMarkerRegex = Regex("\\{\\[\\(\\d+\\)\\]\\}")
        var lastEnd = 0
        
        // Manual character processing is faster than multiple regex replaces
        for (match in pageMarkerRegex.findAll(textContent)) {
            // Process chunk before marker
            for (idx in lastEnd until match.range.first) {
                val ch = textContent[idx]
                if (ch != ' ' && ch != '-' && ch != '\t' && ch != '\n' && ch != '\r') {
                    cleanChars.append(ch.lowercaseChar())
                    cleanToOrigIndex[cleanCount++] = idx
                }
            }
            lastEnd = match.range.last + 1
        }
        // Last chunk
        for (idx in lastEnd until textContent.length) {
            val ch = textContent[idx]
            if (ch != ' ' && ch != '-' && ch != '\t' && ch != '\n' && ch != '\r') {
                cleanChars.append(ch.lowercaseChar())
                cleanToOrigIndex[cleanCount++] = idx
            }
        }

        val cleanText = cleanChars // StringBuilder is a CharSequence
        val results = mutableListOf<SearchResult>()
        val seenRegions = mutableSetOf<Long>() // Use Long for more efficient keying (pageNum << 32 | block)

        val windowMin = max(1, baseQueryLen - maxErrors)
        val windowMax = baseQueryLen + maxErrors

        for (winSize in windowMin..windowMax) {
            val limit = cleanCount - winSize
            if (limit < 0) continue
            
            for (i in 0..limit) {
                // Test against all variants
                var minDist = Int.MAX_VALUE
                for (qClean in queryVariantsClean) {
                    if (kotlin.math.abs(winSize - qClean.length) > maxErrors) continue
                    
                    // Call optimized Levenshtein without substring
                    val dist = SanskritUtils.levenshteinDistance(qClean, cleanText, i, i + winSize, maxErrors)
                    if (dist < minDist) minDist = dist
                }

                if (minDist <= maxErrors) {
                    // Map back to original text position
                    val origIdx = cleanToOrigIndex[i]
                    val pageNum = getPageForIndex(origIdx, pageMap)

                    // Deduplicate by region (block of 200 chars)
                    val regionKey = (pageNum.toLong() shl 32) or (origIdx / 200).toLong()
                    if (regionKey in seenRegions) continue
                    seenRegions.add(regionKey)

                    // Extract context from original text
                    val contextStart = max(0, origIdx - 100)
                    val origEnd = cleanToOrigIndex[i + winSize - 1]
                    val contextEnd = min(textContent.length, origEnd + 100)
                    val rawContext = textContent.substring(contextStart, contextEnd)

                    val cleanContext = rawContext
                        .replace(PAGE_PATTERN, " ")
                        .replace(Regex("\\s+"), " ")
                        .trim()

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
     *
     * @param granthaTexts Map of granthaName to (textContent, tags, booksRaw)
     * @param textQueries List of text queries
     * @param textLogic "or" or "and"
     * @param fuzzyPct 0 for exact, 1-50 for fuzzy
     * @param tagQueries List of tag queries for filtering
     * @param tagsLogic "or" or "and" for tag matching
     * @param negativeTagQueries Tags to exclude
     * @param customRules Sanskrit equivalence rules
     * @param maxPerBook Max results per book (0 = unlimited)
     * @param onProgress Callback for progress updates
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
        onProgress: ((searched: Int, total: Int, bookName: String) -> Unit)? = null
    ): List<SearchResult> {
        val allResults = mutableListOf<SearchResult>()
        val total = granthaTexts.size
        var searched = 0

        // Determine effective max per book
        val matchingCount = granthaTexts.count { (name, triple) ->
            val tags = triple.second.lowercase()
            val bookNameLower = name.lowercase()
            matchesTags(tags, bookNameLower, tagQueries, tagsLogic, negativeTagQueries)
        }
        val effectiveMax = if (matchingCount == 1) 0 else maxPerBook

        for ((granthaName, triple) in granthaTexts) {
            val (textContent, tags, booksRaw) = triple
            val tagsLower = tags.lowercase()
            val bookNameLower = granthaName.lowercase()

            // Check tag filters
            if (!matchesTags(tagsLower, bookNameLower, tagQueries, tagsLogic, negativeTagQueries)) {
                searched++
                onProgress?.invoke(searched, total, granthaName)
                continue
            }

            val subBooks = parseSubBooks(booksRaw)

            if (textQueries.isNotEmpty()) {
                if (textLogic == "and") {
                    // AND logic: all queries must match
                    val allQueryResults = mutableListOf<SearchResult>()
                    var hasAll = true

                    for (q in textQueries) {
                        val matches = if (fuzzyPct > 0) {
                            fuzzySearch(textContent, q, fuzzyPct, granthaName, subBooks, customRules, effectiveMax)
                        } else {
                            smartSearch(textContent, q, granthaName, subBooks, customRules, effectiveMax)
                        }
                        if (matches.isEmpty()) {
                            hasAll = false
                            break
                        }
                        allQueryResults.addAll(matches)
                    }

                    if (hasAll) {
                        allResults.addAll(allQueryResults.sortedBy { it.page })
                    }
                } else {
                    // OR logic: any query can match
                    for (q in textQueries) {
                        val matches = if (fuzzyPct > 0) {
                            fuzzySearch(textContent, q, fuzzyPct, granthaName, subBooks, customRules, effectiveMax)
                        } else {
                            smartSearch(textContent, q, granthaName, subBooks, customRules, effectiveMax)
                        }
                        allResults.addAll(matches)
                    }
                }
            }

            searched++
            onProgress?.invoke(searched, total, granthaName)
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
