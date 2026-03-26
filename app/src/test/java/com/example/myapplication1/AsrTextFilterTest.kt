package com.example.myapplication1

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [AsrTextFilter] — verifies filler removal, noise detection,
 * music-pattern detection, and the new similarity-based echo suppression.
 */
class AsrTextFilterTest {

    @Before
    fun setUp() {
        AsrTextFilter.reset()
    }

    // ---- Filler removal ----

    @Test
    fun `filler words are removed`() {
        val result = AsrTextFilter.filter("um the weather is uh nice today")
        assertNotNull(result)
        assertFalse(result!!.contains("um", ignoreCase = true))
        assertFalse(result.contains(" uh ", ignoreCase = true))
    }

    @Test
    fun `filler phrase you know is removed`() {
        val result = AsrTextFilter.filter("you know it is really great")
        assertNotNull(result)
        assertFalse(result!!.contains("you know", ignoreCase = true))
    }

    @Test
    fun `filler phrase to be honest is removed`() {
        val result = AsrTextFilter.filter("to be honest I think it works")
        assertNotNull(result)
        assertFalse(result!!.contains("to be honest", ignoreCase = true))
    }

    @Test
    fun `so at start of sentence is removed`() {
        val result = AsrTextFilter.filter("so, what do you want to do?")
        assertNotNull(result)
        assertFalse(result!!.startsWith("so", ignoreCase = true))
    }

    @Test
    fun `ok at start of sentence is removed`() {
        val result = AsrTextFilter.filter("Okay, let me explain this")
        assertNotNull(result)
        assertFalse(result!!.startsWith("okay", ignoreCase = true))
    }

    @Test
    fun `right at end of sentence is removed`() {
        val result = AsrTextFilter.filter("we should go now, right")
        assertNotNull(result)
        assertFalse(result!!.endsWith("right", ignoreCase = true))
    }

    // ---- Noise detection ----

    @Test
    fun `single character is noise`() {
        assertNull(AsrTextFilter.filter("a"))
        assertNull(AsrTextFilter.filter("I"))
    }

    @Test
    fun `standalone noise word is rejected`() {
        assertNull(AsrTextFilter.filter("the"))
        assertNull(AsrTextFilter.filter("and"))
    }

    // ---- Music pattern detection ----

    @Test
    fun `repeated syllable la la la is rejected`() {
        assertNull(AsrTextFilter.filter("la la la"))
        assertNull(AsrTextFilter.filter("na na na na"))
    }

    @Test
    fun `oh oh oh is rejected as music pattern`() {
        assertNull(AsrTextFilter.filter("oh oh oh oh"))
    }

    // ---- Echo / near-duplicate detection ----

    @Test
    fun `exact echo is rejected`() {
        val config = AsrTextFilter.FilterConfig(filterEcho = true)
        val first = AsrTextFilter.filter("Hello world", config)
        assertNotNull(first)
        AsrTextFilter.recordText(first!!)

        val echo = AsrTextFilter.filter("Hello world", config)
        assertNull(echo)
    }

    @Test
    fun `near-duplicate text above similarity threshold is rejected`() {
        val config = AsrTextFilter.FilterConfig(filterEcho = true)
        val first = AsrTextFilter.filter("The quick brown fox", config)
        assertNotNull(first)
        AsrTextFilter.recordText(first!!)

        // Slightly different punctuation / case — should still be caught as near-duplicate
        val nearDup = AsrTextFilter.filter("the quick brown fox.", config)
        assertNull(nearDup)
    }

    @Test
    fun `different text is not rejected as echo`() {
        val config = AsrTextFilter.FilterConfig(filterEcho = true)
        val first = AsrTextFilter.filter("Hello world", config)
        assertNotNull(first)
        AsrTextFilter.recordText(first!!)

        val different = AsrTextFilter.filter("This is completely different content", config)
        assertNotNull(different)
    }

    // ---- TranslationPipeline cache key normalization ----

    @Test
    fun `cache key normalization lowercases and collapses whitespace`() {
        val a = com.example.myapplication1.translation.TranslationPipeline.normalizeCacheKey("Hello   World")
        val b = com.example.myapplication1.translation.TranslationPipeline.normalizeCacheKey("hello world")
        assertEquals(a, b)
    }

    @Test
    fun `cache key trims leading and trailing whitespace`() {
        val key = com.example.myapplication1.translation.TranslationPipeline.normalizeCacheKey("  test  ")
        assertEquals("test", key)
    }
}
