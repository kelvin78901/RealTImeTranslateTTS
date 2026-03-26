package com.example.myapplication1

import java.util.LinkedList

object AsrTextFilter {

    data class FilterConfig(
        val filterFillers: Boolean = true,
        val filterEcho: Boolean = true,
        val filterNoise: Boolean = true,
        val filterMusic: Boolean = true
    )

    private const val ECHO_WINDOW_SIZE = 10
    private const val ECHO_TIME_WINDOW_MS = 5000L

    private data class TimestampedText(val text: String, val timestamp: Long)

    private val recentTexts = LinkedList<TimestampedText>()

    private val FILLER_WORDS = setOf("um", "uh", "hmm", "hm", "mm")

    private val FILLER_PHRASES = listOf(
        "uh huh",
        "you know",
        "i mean",
        "basically",
        "actually",
        "literally"
    )

    private val NOISE_WORDS = setOf(
        "the", "a", "an", "and", "or", "but", "is", "it", "in", "on", "to", "of"
    )

    // Pattern for repeated syllables like "la la la", "na na na", "oh oh oh"
    private val REPEATED_SYLLABLE_REGEX =
        Regex("""^(\w{1,4})(\s+\1){2,}$""", RegexOption.IGNORE_CASE)

    // Pattern for "like" used as filler: surrounded by other words (not at meaningful positions)
    // e.g. "it was like really good" -> "like" is filler
    private val FILLER_LIKE_REGEX =
        Regex("""\b(?<=\w\s)like(?=\s\w)\b""", RegexOption.IGNORE_CASE)

    /**
     * Returns filtered text, or null if the entire text should be skipped.
     */
    fun filter(text: String, config: FilterConfig = FilterConfig()): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null

        // Check echo first (before any text transformation)
        if (config.filterEcho && isEcho(trimmed)) {
            return null
        }

        // Check music patterns on raw input
        if (config.filterMusic && isMusicPattern(trimmed)) {
            return null
        }

        // Check noise on raw input
        if (config.filterNoise && isNoise(trimmed)) {
            return null
        }

        // Apply filler removal
        var result = trimmed
        if (config.filterFillers) {
            result = removeFillers(result)
        }

        // After filler removal the text may be empty or noise
        val postFilter = result.trim()
        if (postFilter.isEmpty()) return null

        if (config.filterNoise && isNoise(postFilter)) {
            return null
        }

        return postFilter
    }

    /**
     * Record recently seen text for echo detection.
     */
    fun recordText(text: String) {
        val normalized = text.trim().lowercase()
        if (normalized.isEmpty()) return

        val now = System.currentTimeMillis()
        synchronized(recentTexts) {
            recentTexts.addLast(TimestampedText(normalized, now))
            // Evict old entries beyond window size
            while (recentTexts.size > ECHO_WINDOW_SIZE) {
                recentTexts.removeFirst()
            }
        }
    }

    /**
     * Clear echo detection history.
     */
    fun reset() {
        synchronized(recentTexts) {
            recentTexts.clear()
        }
    }

    // ---- Private helpers ----

    private fun isEcho(text: String): Boolean {
        val normalized = text.trim().lowercase()
        val now = System.currentTimeMillis()

        synchronized(recentTexts) {
            // Remove entries outside the time window
            while (recentTexts.isNotEmpty() &&
                now - recentTexts.first().timestamp > ECHO_TIME_WINDOW_MS
            ) {
                recentTexts.removeFirst()
            }

            return recentTexts.any { it.text == normalized }
        }
    }

    private fun isMusicPattern(text: String): Boolean {
        val lower = text.trim().lowercase()

        // Check repeated syllable patterns: "la la la", "na na na", "da da da"
        if (REPEATED_SYLLABLE_REGEX.matches(lower)) {
            return true
        }

        // Check if text is just "oh" repeated with optional spacing/punctuation
        val ohOnly = lower.replace(Regex("""[^a-z]"""), "")
        if (ohOnly.isNotEmpty() && ohOnly.length >= 4 && ohOnly.all { it == 'o' || it == 'h' }) {
            // Must contain at least one "oh" and be predominantly oh repetitions
            val ohCount = Regex("oh").findAll(ohOnly).count()
            if (ohCount >= 2 && ohCount * 2 >= ohOnly.length * 0.8) {
                return true
            }
        }

        return false
    }

    private fun isNoise(text: String): Boolean {
        val lower = text.trim().lowercase()

        // Single character utterances
        if (lower.length <= 1) return true

        // Single character with punctuation, e.g. "a." or "I?"
        val lettersOnly = lower.replace(Regex("""[^a-z]"""), "")
        if (lettersOnly.length <= 1) return true

        // Known noise words standing alone
        if (NOISE_WORDS.contains(lettersOnly)) return true

        return false
    }

    private fun removeFillers(text: String): String {
        var result = text

        // Remove filler phrases first (multi-word)
        for (phrase in FILLER_PHRASES) {
            result = result.replace(Regex("""\b${Regex.escape(phrase)}\b""", RegexOption.IGNORE_CASE), " ")
        }

        // Remove standalone filler words
        for (filler in FILLER_WORDS) {
            result = result.replace(Regex("""\b${Regex.escape(filler)}\b""", RegexOption.IGNORE_CASE), " ")
        }

        // Remove "like" when used as filler (between other words)
        result = FILLER_LIKE_REGEX.replace(result, " ")

        // Remove "so" at the start of a sentence
        result = result.replace(Regex("""^\s*\bso\b\s*[,]?\s*""", RegexOption.IGNORE_CASE), "")

        // Remove "well" at the start of a sentence
        result = result.replace(Regex("""^\s*\bwell\b\s*[,]?\s*""", RegexOption.IGNORE_CASE), "")

        // Remove "right" at the end as filler (e.g. "we should go, right")
        result = result.replace(Regex("""\s*[,]?\s*\bright\b\s*[?.!]?\s*$""", RegexOption.IGNORE_CASE), "")

        // Collapse multiple spaces
        result = result.replace(Regex("""\s{2,}"""), " ")

        return result.trim()
    }
}
