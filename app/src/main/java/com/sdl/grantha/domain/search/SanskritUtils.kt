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
    fun getCustomVariants(text: String, customRules: Map<String, String>?): List<String> {
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
        return text.replace(Regex("[\\s\\-]"), "")
    }

    /**
     * Compute Levenshtein edit distance between two strings.
     * Returns Int.MAX_VALUE if distance exceeds cutoff (early exit optimization).
     *
     * @param s1 First string
     * @param s2 Second string
     * @param cutoff Maximum distance to compute. Returns Int.MAX_VALUE if exceeded.
     * @return Edit distance, or Int.MAX_VALUE if > cutoff
     */
    fun levenshteinDistance(s1: String, s2: String, cutoff: Int = Int.MAX_VALUE): Int {
        val len1 = s1.length
        val len2 = s2.length

        // Quick check: if length difference exceeds cutoff, skip
        if (kotlin.math.abs(len1 - len2) > cutoff) return Int.MAX_VALUE

        // Use single-row DP for space efficiency
        var prevRow = IntArray(len2 + 1) { it }
        var currRow = IntArray(len2 + 1)

        for (i in 1..len1) {
            currRow[0] = i
            var minInRow = currRow[0]

            for (j in 1..len2) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                currRow[j] = minOf(
                    prevRow[j] + 1,       // deletion
                    currRow[j - 1] + 1,   // insertion
                    prevRow[j - 1] + cost  // substitution
                )
                if (currRow[j] < minInRow) minInRow = currRow[j]
            }

            // Early exit if minimum in this row already exceeds cutoff
            if (minInRow > cutoff) return Int.MAX_VALUE

            // Swap rows
            val temp = prevRow
            prevRow = currRow
            currRow = temp
        }

        return if (prevRow[len2] <= cutoff) prevRow[len2] else Int.MAX_VALUE
    }
}
