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

    fun extractContext(textContent: String, origIdx: Int): String {
        val contextStart = maxOf(0, origIdx - 100)
        val contextEnd = minOf(textContent.length, origIdx + 100)
        val rawContext = textContent.substring(contextStart, contextEnd)
        return rawContext.replace(PAGE_PATTERN, " ").replace(WHITESPACE_PATTERN, " ").trim()
    }

    /**
     * Compressed mapping from clean index to original index.
     * Uses a list of "OffsetPoints" to avoid storing an Int for every character.
     */
    class IndexMapper(private val offsetPoints: IntArray) {
        fun getOriginalIndex(cleanIdx: Int): Int {
            if (offsetPoints.isEmpty()) return cleanIdx
            
            // Fast binary search
            var low = 0
            var high = (offsetPoints.size / 2) - 1
            var bestIdx = -1
            
            while (low <= high) {
                val mid = (low + high) ushr 1
                if (offsetPoints[mid shl 1] <= cleanIdx) {
                    bestIdx = mid
                    low = mid + 1
                } else {
                    high = mid - 1
                }
            }
            
            return if (bestIdx == -1) cleanIdx else cleanIdx + offsetPoints[(bestIdx shl 1) + 1]
        }
    }

    class PreparedText(
        val cleanString: String,
        val cleanChars: CharArray, // Keep CharArray for fast fuzzy search
        val mapper: IndexMapper,
        val pageMap: List<Pair<Int, Int>>
    )

    // Hybrid cache: Hard references for the 3 most recent books, Soft for others
    private val hardCache = mutableMapOf<String, PreparedText>()
    private val softCache = mutableMapOf<String, java.lang.ref.SoftReference<PreparedText>>()
    private val MAX_HARD_CACHE = 3
    private val MAX_SOFT_CACHE = 15

    fun hasCache(cacheKey: String): Boolean {
        synchronized(hardCache) {
            if (hardCache.containsKey(cacheKey)) return true
            return softCache[cacheKey]?.get() != null
        }
    }

    fun prepareText(cacheKey: String, textContent: String): PreparedText {
        synchronized(hardCache) {
            hardCache[cacheKey]?.let { return it }
            softCache[cacheKey]?.get()?.let { 
                // Promote to hard cache
                hardCache[cacheKey] = it
                return it 
            }
        }

        if (textContent.isEmpty()) {
            return PreparedText("", CharArray(0), IndexMapper(IntArray(0)), emptyList())
        }

        val pageMap = mutableListOf<Pair<Int, Int>>()
        val textLen = textContent.length
        val cleanCharsArr = CharArray(textLen)
        
        // Use primitive array for offset points to avoid boxing
        var offsetPoints = IntArray(maxOf(100, textLen / 4))
        var offsetCount = 0
        var currentOffset = 0
        var cleanCount = 0

        var i = 0
        while (i < textLen) {
            val ch = textContent[i]

            if (ch == '{' && i + 5 < textLen && textContent[i+1] == '[' && textContent[i+2] == '(') {
                val end = textContent.indexOf(")]}", i + 3)
                if (end != -1) {
                    val pageStr = textContent.substring(i + 3, end)
                    val pageNum = pageStr.toIntOrNull()
                    if (pageNum != null) {
                        pageMap.add(Pair(end + 3, pageNum))
                        val markerLen = (end + 3) - i
                        currentOffset += markerLen
                        i = end + 3
                        continue
                    }
                }
            }

            if (ch == ' ' || ch == '-' || ch == '\n' || ch == '\r' || ch == '\t' || ch.isWhitespace()) {
                currentOffset++
                i++
                continue
            }

            val lowerCh = if (ch in 'A'..'Z') (ch.code + 32).toChar() else ch.lowercaseChar()
            cleanCharsArr[cleanCount] = lowerCh
            
            if (offsetCount == 0 || offsetPoints[offsetCount - 1] != currentOffset) {
                if (offsetCount + 1 >= offsetPoints.size) {
                    offsetPoints = offsetPoints.copyOf(offsetPoints.size * 2)
                }
                offsetPoints[offsetCount++] = cleanCount
                offsetPoints[offsetCount++] = currentOffset
            }
            
            cleanCount++
            i++
        }

        val finalCleanChars = cleanCharsArr.copyOf(cleanCount)
        val result = PreparedText(
            cleanString = String(finalCleanChars),
            cleanChars = finalCleanChars,
            mapper = IndexMapper(offsetPoints.copyOf(offsetCount)),
            pageMap = pageMap
        )

        synchronized(hardCache) {
            if (hardCache.size >= MAX_HARD_CACHE) {
                val firstKey = hardCache.keys.first()
                val removed = hardCache.remove(firstKey)
                if (removed != null) {
                    softCache[firstKey] = java.lang.ref.SoftReference(removed)
                }
            }
            hardCache[cacheKey] = result
            // Remove from soft cache if it was there
            softCache.remove(cacheKey)
            
            if (softCache.size >= MAX_SOFT_CACHE) {
                softCache.clear()
            }
        }

        return result
    }

    /**
     * Legacy version that hashes content (slower but safe if no key is available)
     */
    fun prepareText(textContent: String): PreparedText {
        return prepareText("hash_${textContent.hashCode()}", textContent)
    }

    /**
     * Build a map of character indices to page numbers.
     * (Deprecated: Use prepareText for better performance)
     */
    fun getPageMap(textContent: String): List<Pair<Int, Int>> {
        val pageMap = mutableListOf<Pair<Int, Int>>()
        for (match in PAGE_PATTERN.findAll(textContent)) {
            val group = match.groups[1] ?: continue
            val pageNum = group.value.toIntOrNull() ?: continue
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
        val prepared = prepareText(granthaName, textContent)
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

        // Use the CACHED cleanString instead of creating a new one
        val cleanString = prepared.cleanString

        for (qClean in queryVariantsClean) {
            var startPos = 0
            while (true) {
                val matchIdx = cleanString.indexOf(qClean, startPos)
                if (matchIdx == -1) break
                
                val origIdx = prepared.mapper.getOriginalIndex(matchIdx)
                val pageNum = getPageForIndex(origIdx, prepared.pageMap)
                
                val regionKey = (pageNum.toLong() shl 32) or (origIdx / 200).toLong()
                if (regionKey !in seenRegions) {
                    seenRegions.add(regionKey)
                    
                    val contextStart = max(0, origIdx - 100)
                    val origEnd = prepared.mapper.getOriginalIndex(matchIdx + qClean.length - 1)
                    val contextEnd = min(textContent.length, origEnd + 100)
                    
                    val cleanContext = if (textContent.isNotEmpty()) {
                        val rawContext = textContent.substring(contextStart, contextEnd)
                        rawContext.replace(PAGE_PATTERN, " ").replace(WHITESPACE_PATTERN, " ").trim()
                    } else {
                        "MATCH_FOUND_WITHOUT_TEXT" // Signal that we need to decrypt to get context
                    }
                    
                    val matchedPage = getPageForIndex(origIdx, prepared.pageMap)
                    val subBook = getSubBookForPage(matchedPage, subBooks)

                    results.add(
                        SearchResult(
                            granthaName = granthaName,
                            page = matchedPage,
                            contextText = if (cleanContext == "MATCH_FOUND_WITHOUT_TEXT") cleanContext else "...$cleanContext...",
                            subBook = subBook,
                            matchOffset = origIdx // Store offset for lazy context extraction
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
        val prepared = prepareText(granthaName, textContent)
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

        val cleanChars = prepared.cleanChars
        val cleanCount = cleanChars.size
        
        // Reuse rows to avoid millions of allocations per book
        val prevRow = IntArray(windowMax + 1)
        val currRow = IntArray(windowMax + 1)

        for (winSize in windowMin..windowMax) {
            val limit = cleanCount - winSize
            if (limit < 0) continue
            
            var i = 0
            while (i <= limit) {
                var foundMatch = false
                for (qClean in queryVariantsClean) {
                    if (kotlin.math.abs(winSize - qClean.length) > maxErrors) continue
                    
                    val dist = SanskritUtils.levenshteinDistance(qClean, cleanChars, i, i + winSize, maxErrors, prevRow, currRow)
                    if (dist <= maxErrors) {
                        foundMatch = true
                        val origIdx = prepared.mapper.getOriginalIndex(i)
                        val matchedPage = getPageForIndex(origIdx, prepared.pageMap)
                        val regionKey = (matchedPage.toLong() shl 32) or (origIdx / 200).toLong()
                        if (regionKey !in seenRegions) {
                            seenRegions.add(regionKey)

                            val contextStart = max(0, origIdx - 100)
                            val origEnd = prepared.mapper.getOriginalIndex(i + winSize - 1)
                            val contextEnd = min(textContent.length, origEnd + 100)
                            
                            val cleanContext = if (textContent.isNotEmpty()) {
                                val rawContext = textContent.substring(contextStart, contextEnd)
                                rawContext.replace(PAGE_PATTERN, " ").replace(Regex("\\s+"), " ").trim()
                            } else {
                                "MATCH_FOUND_WITHOUT_TEXT"
                            }
                            
                            val subBook = getSubBookForPage(matchedPage, subBooks)

                            results.add(
                                SearchResult(
                                    granthaName = granthaName,
                                    page = matchedPage,
                                    contextText = if (cleanContext == "MATCH_FOUND_WITHOUT_TEXT") cleanContext else "...$cleanContext...",
                                    subBook = subBook,
                                    matchOffset = origIdx
                                )
                            )
                            if (maxResults > 0 && results.size >= maxResults) return results
                        }
                        break
                    }
                }
                
                if (foundMatch) {
                    i += maxOf(1, winSize / 2)
                } else {
                    i++
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
                val prepared = prepareText(granthaName, textContent)
                if (textLogic == "and") {
                    val allQueryResults = mutableListOf<SearchResult>()
                    var hasAll = true
                    for (q in textQueries) {
                        val matches = if (fuzzyPct > 0) {
                            fuzzySearchInternal(prepared, textContent, q, fuzzyPct, granthaName, subBooks, customRules, if (stopAtFirstMatch) 1 else maxPerBook)
                        } else {
                            smartSearchInternal(prepared, textContent, q, granthaName, subBooks, customRules, if (stopAtFirstMatch) 1 else maxPerBook)
                        }
                        if (matches.isEmpty()) { 
                            hasAll = false
                            break 
                        }
                        allQueryResults.addAll(matches)
                    }
                    if (hasAll) {
                        if (stopAtFirstMatch) {
                            // If we only want one result, just take the first one found
                            allResults.add(allQueryResults.first())
                        } else {
                            allResults.addAll(allQueryResults.sortedBy { it.page })
                        }
                    }
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
        if (negativeTagQueries.isNotEmpty()) {
            if (negativeTagQueries.any { it in tags || it in bookName }) return false
        }
        if (tagQueries.isEmpty()) return true
        return if (tagsLogic == "and") {
            tagQueries.all { it in tags || it in bookName }
        } else {
            tagQueries.any { it in tags || it in bookName }
        }
    }

    /**
     * Add highlighting markup to search result text.
     */
    fun highlightText(
        text: String,
        searchTerms: List<String>,
        fuzzyPct: Int = 0,
        customRules: Map<String, String>? = null
    ): String {
        if (searchTerms.isEmpty()) return text
        val allTerms = if (customRules != null) {
            searchTerms.flatMap { SanskritUtils.getCustomVariants(it, customRules) }.distinct()
        } else {
            searchTerms
        }

        if (fuzzyPct > 0) {
            var result = text
            for (term in allTerms) {
                val termClean = SanskritUtils.removeSpacesAndHyphens(term).lowercase()
                if (termClean.length < 2) continue
                val maxErrors = max(1, ceil(termClean.length * fuzzyPct / 100.0).toInt())
                val textLower = result.lowercase()
                val windowSize = termClean.length
                val replacements = mutableListOf<Pair<IntRange, String>>()

                for (winSize in max(1, windowSize - maxErrors)..(windowSize + maxErrors)) {
                    for (i in 0..(textLower.length - winSize)) {
                        val window = textLower.substring(i, i + winSize)
                        val dist = SanskritUtils.levenshteinDistance(termClean, window, maxErrors)
                        if (dist <= maxErrors) {
                            val original = result.substring(i, i + winSize)
                            val range = i until (i + winSize)
                            if (replacements.none { !it.first.intersectRange(range).isEmpty() }) {
                                replacements.add(Pair(range, original))
                            }
                        }
                    }
                }
                for ((range, original) in replacements.sortedByDescending { it.first.first }) {
                    result = result.take(range.first) + "【$original】" + result.substring(range.last + 1)
                }
            }
            return result
        }

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
            val combinedRegex = Regex(regexes.joinToString("|") { "($it)" }, setOf(RegexOption.IGNORE_CASE))
            combinedRegex.replace(text) { "【${it.value}】" }
        } catch (_: Exception) { text }
    }

    private fun IntRange.intersectRange(other: IntRange): IntRange {
        val start = maxOf(this.first, other.first)
        val end = minOf(this.last, other.last)
        return if (start <= end) start..end else IntRange.EMPTY
    }
}
