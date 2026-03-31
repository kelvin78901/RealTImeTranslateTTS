package com.example.myapplication1

import java.util.LinkedList

object AsrTextFilter {

    data class FilterConfig(
        val filterFillers: Boolean = true,
        val filterEcho: Boolean = true,
        val filterNoise: Boolean = true,
        val filterMusic: Boolean = true
    )

    private const val ECHO_WINDOW_SIZE = 20
    private const val ECHO_TIME_WINDOW_MS = 8000L

    /**
     * Similarity threshold for near-duplicate suppression.
     * Two texts are considered duplicates if their character overlap ratio >= this value.
     */
    private const val SIMILARITY_THRESHOLD = 0.85f

    private data class TimestampedText(val text: String, val timestamp: Long)

    private val recentTexts = LinkedList<TimestampedText>()

    // ---- Filler words (single words) ----
    private val FILLER_WORDS = setOf(
        "um", "uh", "hmm", "hm", "mm", "ugh", "eh", "ah", "oh",
        "er", "err", "uhh", "umm", "uhm", "mhm"
    )

    // ---- Filler phrases (multi-word; matched in order, longest first) ----
    private val FILLER_PHRASES = listOf(
        "you know what i mean",
        "if you know what i mean",
        "if you will",
        "to be honest",
        "to be fair",
        "at the end of the day",
        "kind of sort of",
        "sort of kind of",
        "more or less",
        "uh huh",
        "you know",
        "i mean",
        "basically",
        "actually",
        "literally",
        "kind of",
        "sort of",
        "like i said",
        "as i said",
        "in other words",
        "you see",
        "i guess",
        "i suppose",
        "i think",
        "i feel like",
        "at this point in time",
        "going forward",
        "moving forward"
    ).sortedByDescending { it.length }  // longest first to avoid partial matches

    private val NOISE_WORDS = setOf(
        "the", "a", "an", "and", "or", "but", "is", "it", "in", "on", "to", "of"
    )

    // Pattern for repeated syllables like "la la la", "na na na", "oh oh oh"
    private val REPEATED_SYLLABLE_REGEX =
        Regex("""^(\w{1,4})(\s+\1){2,}$""", RegexOption.IGNORE_CASE)

    // Pattern for "like" used as filler: between content words (not at sentence boundaries).
    // Uses \s to handle spaces, tabs, and other whitespace between the surrounding words.
    // E.g. "it was like really good" → removes "like".
    private val FILLER_LIKE_REGEX =
        Regex("""(?<=\w\s)(like)(?=\s\w)""", RegexOption.IGNORE_CASE)

    /**
     * Returns filtered text, or null if the entire text should be skipped.
     */
    fun filter(text: String, config: FilterConfig = FilterConfig()): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null

        // Check echo first (exact match or near-duplicate) before any text transformation
        if (config.filterEcho && isEchoOrDuplicate(trimmed)) {
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
     * Record recently seen text for echo/duplicate detection.
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

    /**
     * Check for exact echo OR near-duplicate (high character overlap).
     * Near-duplicate detection catches cases where ASR outputs slightly
     * different text for the same audio (e.g. "hello world" vs "hello world.").
     */
    private fun isEchoOrDuplicate(text: String): Boolean {
        val normalized = text.trim().lowercase()
        val now = System.currentTimeMillis()

        synchronized(recentTexts) {
            // Remove entries outside the time window
            while (recentTexts.isNotEmpty() &&
                now - recentTexts.first().timestamp > ECHO_TIME_WINDOW_MS
            ) {
                recentTexts.removeFirst()
            }

            return recentTexts.any { recent ->
                recent.text == normalized || similarityRatio(recent.text, normalized) >= SIMILARITY_THRESHOLD
            }
        }
    }

    /**
     * Computes a simple character-overlap similarity ratio between two strings.
     * Uses the Dice coefficient on character bigrams.
     * Returns a value in [0.0, 1.0].
     */
    private fun similarityRatio(a: String, b: String): Float {
        if (a.isEmpty() || b.isEmpty()) return 0f
        if (a == b) return 1f
        // Avoid expensive comparison for strings with very different lengths
        val lenRatio = minOf(a.length, b.length).toFloat() / maxOf(a.length, b.length)
        if (lenRatio < 0.5f) return 0f

        // Bigram Dice coefficient
        fun bigrams(s: String): Map<String, Int> {
            val map = mutableMapOf<String, Int>()
            for (i in 0 until s.length - 1) {
                val bg = s.substring(i, i + 2)
                map[bg] = (map[bg] ?: 0) + 1
            }
            return map
        }
        val ba = bigrams(a)
        val bb = bigrams(b)
        var intersection = 0
        for ((k, v) in ba) {
            val common = minOf(v, bb[k] ?: 0)
            intersection += common
        }
        val totalA = ba.values.sum()
        val totalB = bb.values.sum()
        if (totalA + totalB == 0) return 0f
        return (2.0f * intersection) / (totalA + totalB)
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

        // Remove filler phrases first (multi-word, longest first to avoid partial matches)
        for (phrase in FILLER_PHRASES) {
            result = result.replace(Regex("""\b${Regex.escape(phrase)}\b""", RegexOption.IGNORE_CASE), " ")
        }

        // Remove standalone filler words
        for (filler in FILLER_WORDS) {
            result = result.replace(Regex("""\b${Regex.escape(filler)}\b""", RegexOption.IGNORE_CASE), " ")
        }

        // Remove "like" when used as filler (between content words)
        result = FILLER_LIKE_REGEX.replace(result, " ")

        // Remove "so" at the start of a sentence (filler usage)
        result = result.replace(Regex("""^\s*\bso\b\s*[,]?\s*""", RegexOption.IGNORE_CASE), "")

        // Remove "well" at the start of a sentence
        result = result.replace(Regex("""^\s*\bwell\b\s*[,]?\s*""", RegexOption.IGNORE_CASE), "")

        // Remove "now" as sentence-starter filler (e.g. "Now, what I want to say...")
        result = result.replace(Regex("""^\s*\bnow\b\s*[,]?\s*""", RegexOption.IGNORE_CASE), "")

        // Remove "right" at the end as a filler tag (e.g. "we should go, right")
        result = result.replace(Regex("""\s*[,]?\s*\bright\b\s*[?.!]?\s*$""", RegexOption.IGNORE_CASE), "")

        // Remove "okay" / "ok" as a sentence-starter filler
        result = result.replace(Regex("""^\s*\b(okay|ok)\b\s*[,.]?\s*""", RegexOption.IGNORE_CASE), "")

        // Collapse multiple spaces
        result = result.replace(Regex("""\s{2,}"""), " ")

        return result.trim()
    }
}
