package com.sdl.grantha.domain.search

/**
 * Sanskrit text normalization utilities.
 * Port of sanskrit_utils.py from the web app.
 */
object SanskritUtils {
    
    enum class SanskritSearchMode {
        CONTAINS, EXACT, STARTS_WITH, ENDS_WITH
    }

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

}
