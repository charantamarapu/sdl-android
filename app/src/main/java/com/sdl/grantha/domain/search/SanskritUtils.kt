package com.sdl.grantha.domain.search

/**
 * Sanskrit text normalization utilities.
 * Port of sanskrit_utils.py from the web app.
 */
object SanskritUtils {

    /**
     * Generate all valid interchangeable permutations of a text based on custom equivalence rules.
     * Treats customRules (e.g. A=B) bidirectionally: A->B and B->A are both generated.
     *
     * @param text The input text
     * @param customRules Map of variant_a to variant_b
     * @return List of variant strings including the original
     */
    fun getCustomVariants(text: String, customRules: List<Pair<String, String>>?): List<String> {
        if (customRules.isNullOrEmpty()) return listOf(text)

        val variants = mutableSetOf(text)
        for ((variantA, variantB) in customRules) {
            // A -> B
            if (variantA in text) {
                variants.add(text.replace(variantA, variantB))
            }
            // B -> A
            if (variantB in text) {
                variants.add(text.replace(variantB, variantA))
            }
        }
        return variants.toList()
    }

    /**
     * Remove spaces and hyphens from text for searching.
     */
    fun removeSpacesAndHyphens(text: String): String {
        if (text.isEmpty()) return ""
        val sb = StringBuilder(text.length)
        for (ch in text) {
            if (ch != ' ' && ch != '-' && !ch.isWhitespace()) {
                sb.append(ch)
            }
        }
        return sb.toString()
    }

    /**
     * Compute Levenshtein edit distance between a string and a portion of a CharSequence.
     * Returns Int.MAX_VALUE if distance exceeds cutoff (early exit optimization).
     *
     * @param s1 First string (usually the query)
     * @param s2 Second CharSequence (usually the book text)
     * @param s2Start Start index in s2 (inclusive)
     * @param s2End End index in s2 (exclusive)
     * @param cutoff Maximum distance to compute. Returns Int.MAX_VALUE if exceeded.
     * @return Edit distance, or Int.MAX_VALUE if > cutoff
     */
    fun levenshteinDistance(
        s1: String, 
        s2: CharArray, 
        s2Start: Int, 
        s2End: Int,
        cutoff: Int,
        prevRowIn: IntArray? = null,
        currRowIn: IntArray? = null
    ): Int {
        val len1 = s1.length
        val len2 = s2End - s2Start
        if (kotlin.math.abs(len1 - len2) > cutoff) return Int.MAX_VALUE
        if (len1 == 0) return if (len2 <= cutoff) len2 else Int.MAX_VALUE
        if (len2 == 0) return if (len1 <= cutoff) len1 else Int.MAX_VALUE

        val prevRow = prevRowIn ?: IntArray(len2 + 1)
        val currRow = currRowIn ?: IntArray(len2 + 1)
        for (j in 0..len2) prevRow[j] = j

        for (i in 1..len1) {
            currRow[0] = i
            val char1 = s1[i - 1]
            val start = maxOf(1, i - cutoff)
            val end = minOf(len2, i + cutoff)
            if (start > 1) currRow[start - 1] = Int.MAX_VALUE
            var minInRow = if (start <= i + cutoff) currRow[0] else Int.MAX_VALUE
            for (j in start..end) {
                val cost = if (char1 == s2[s2Start + j - 1]) 0 else 1
                val v1 = prevRow[j] + 1
                val v2 = currRow[j - 1] + 1
                val v3 = prevRow[j - 1] + cost
                val res = if (v1 < v2) (if (v1 < v3) v1 else v3) else (if (v2 < v3) v2 else v3)
                currRow[j] = res
                if (res < minInRow) minInRow = res
            }
            if (minInRow > cutoff) return Int.MAX_VALUE
            System.arraycopy(currRow, 0, prevRow, 0, len2 + 1)
        }
        return if (prevRow[len2] <= cutoff) prevRow[len2] else Int.MAX_VALUE
    }

    fun levenshteinDistance(
        s1: String, 
        s2: CharSequence, 
        s2Start: Int = 0, 
        s2End: Int = s2.length,
        cutoff: Int = Int.MAX_VALUE,
        prevRowIn: IntArray? = null,
        currRowIn: IntArray? = null
    ): Int {
        val len1 = s1.length
        val len2 = s2End - s2Start
        if (kotlin.math.abs(len1 - len2) > cutoff) return Int.MAX_VALUE
        if (len1 == 0) return if (len2 <= cutoff) len2 else Int.MAX_VALUE
        if (len2 == 0) return if (len1 <= cutoff) len1 else Int.MAX_VALUE

        val prevRow = prevRowIn ?: IntArray(len2 + 1)
        val currRow = currRowIn ?: IntArray(len2 + 1)
        for (j in 0..len2) prevRow[j] = j

        for (i in 1..len1) {
            currRow[0] = i
            val char1 = s1[i - 1]
            val start = maxOf(1, i - cutoff)
            val end = minOf(len2, i + cutoff)
            if (start > 1) currRow[start - 1] = Int.MAX_VALUE
            var minInRow = if (start <= i + cutoff) currRow[0] else Int.MAX_VALUE
            for (j in start..end) {
                val cost = if (char1 == s2[s2Start + j - 1]) 0 else 1
                val v1 = prevRow[j] + 1
                val v2 = currRow[j - 1] + 1
                val v3 = prevRow[j - 1] + cost
                val res = if (v1 < v2) (if (v1 < v3) v1 else v3) else (if (v2 < v3) v2 else v3)
                currRow[j] = res
                if (res < minInRow) minInRow = res
            }
            if (minInRow > cutoff) return Int.MAX_VALUE
            System.arraycopy(currRow, 0, prevRow, 0, len2 + 1)
        }
        return if (prevRow[len2] <= cutoff) prevRow[len2] else Int.MAX_VALUE
    }

    /**
     * Legacy version for backward compatibility if needed, though we should migrate all callers.
     */
    fun levenshteinDistance(s1: String, s2: String, cutoff: Int = Int.MAX_VALUE): Int {
        return levenshteinDistance(s1, s2 as CharSequence, 0, s2.length, cutoff)
    }
}
