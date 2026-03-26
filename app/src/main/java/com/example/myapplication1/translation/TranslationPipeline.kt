package com.example.myapplication1.translation

import android.util.Log
import kotlinx.coroutines.*
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Minimal translation pipeline: translate each sentence ASAP, TTS ASAP.
 * No ordering, no queuing, no semaphores — pure speed.
 *
 * Features:
 * - LRU translation cache: repeated phrases hit cache instantly (0ms latency).
 * - Paragraph refinement is fire-and-forget background work that only updates display.
 */
class TranslationPipeline(private val scope: CoroutineScope) {

    companion object {
        private const val TAG = "TransPipeline"

        /** Maximum number of entries in the translation LRU cache. */
        private const val CACHE_MAX_SIZE = 200

        /** Normalize English text for cache key: lowercase, collapse whitespace. */
        @JvmStatic
        fun normalizeCacheKey(text: String): String =
            text.trim().lowercase().replace(Regex("""\s+"""), " ")
    }

    interface Callback {
        fun onTranslationStarted(seqId: Int, en: String)
        fun onTranslationResult(seqId: Int, en: String, zh: String)
        fun onTranslationError(seqId: Int, en: String, error: String)
        fun onTtsReady(zh: String)
        fun onLatencyMeasured(translationMs: Long)
        fun onCacheHit(seqId: Int)
        fun onParagraphRefined(paragraphId: Int, refinedZh: String)
    }

    private val seqCounter = AtomicInteger(0)
    private val pendingCount = AtomicInteger(0)

    // LRU cache: normalized English → Chinese translation
    private val translationCache: LinkedHashMap<String, String> =
        object : LinkedHashMap<String, String>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) =
                size > CACHE_MAX_SIZE
        }

    // Cache statistics
    private val cacheHits = AtomicLong(0)
    private val cacheMisses = AtomicLong(0)

    // Paragraph: just stores (en,zh) pairs for later refinement — no blocking
    private val paragraphData = mutableMapOf<Int, MutableList<Pair<String, String>>>()

    @Volatile private var callback: Callback? = null
    private var engine: TranslationEngine? = null
    private var refiner: TranslationRefiner? = null

    val pendingTranslations: Int get() = pendingCount.get()
    val cacheHitCount: Long get() = cacheHits.get()
    val cacheMissCount: Long get() = cacheMisses.get()

    fun setCallback(cb: Callback?) { callback = cb }
    fun setEngine(eng: TranslationEngine?) { engine = eng }
    fun setRefiner(ref: TranslationRefiner?) { refiner = ref }

    /** Allocate a seqId so caller can update UI state before translation starts. */
    fun allocateSeqId(): Int = seqCounter.getAndIncrement()

    /**
     * Evict the translation cache. Call when engine changes or context shifts significantly.
     */
    fun clearCache() {
        synchronized(translationCache) { translationCache.clear() }
        cacheHits.set(0)
        cacheMisses.set(0)
        Log.d(TAG, "Translation cache cleared")
    }

    /**
     * Start translating a sentence with a pre-allocated seqId.
     * Caller MUST have already updated UI state with this seqId.
     *
     * Cache lookup is performed on the calling thread (Main) for zero latency on hit.
     * On a cache miss the translation is dispatched to IO and results are delivered async.
     */
    fun submitSentence(seqId: Int, paragraphId: Int, en: String) {
        // ---- Fast path: cache lookup on calling thread ----
        val key = normalizeCacheKey(en)
        val cached: String? = synchronized(translationCache) { translationCache[key] }
        if (cached != null) {
            cacheHits.incrementAndGet()
            Log.d(TAG, "Cache hit [$key] → $cached")
            // Deliver on Main (we are already on Main, but stay consistent with async path)
            scope.launch(Dispatchers.Main) {
                callback?.onTranslationResult(seqId, en, cached)
                callback?.onLatencyMeasured(0L)
                callback?.onCacheHit(seqId)
                callback?.onTtsReady(cached)
            }
            synchronized(paragraphData) {
                paragraphData.getOrPut(paragraphId) { mutableListOf() }.add(en to cached)
            }
            return
        }

        // ---- Slow path: call translation engine ----
        cacheMisses.incrementAndGet()
        pendingCount.incrementAndGet()

        scope.launch(Dispatchers.IO) {
            try {
                val eng = engine ?: throw Exception("No translation engine")
                val t0 = System.currentTimeMillis()
                val zh = eng.translate(en)
                val ms = System.currentTimeMillis() - t0

                // Populate cache for future identical sentences
                if (zh.isNotBlank()) {
                    synchronized(translationCache) { translationCache[key] = zh }
                }

                // Store for later paragraph refinement
                synchronized(paragraphData) {
                    paragraphData.getOrPut(paragraphId) { mutableListOf() }.add(en to zh)
                }

                // Fire callbacks immediately — no ordering wait
                Log.d(TAG, "翻译完成 seq=$seqId ${ms}ms: ${en.take(30)} → ${zh.take(30)}")
                withContext(Dispatchers.Main) {
                    callback?.onTranslationResult(seqId, en, zh)
                    callback?.onLatencyMeasured(ms)
                    callback?.onTtsReady(zh)
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Translation failed: ${e.message}")
                withContext(Dispatchers.Main) {
                    callback?.onTranslationError(seqId, en, e.message ?: "error")
                }
                synchronized(paragraphData) {
                    paragraphData.getOrPut(paragraphId) { mutableListOf() }.add(en to "")
                }
            } finally {
                pendingCount.decrementAndGet()
            }
        }
    }

    /**
     * Fire-and-forget paragraph refinement. Runs in background.
     * Only updates display text — TTS already played per-sentence.
     * ALWAYS fires onParagraphRefined (even with empty string on failure)
     * so the caller can clear the refining flag.
     */
    fun closeParagraph(paragraphId: Int) {
        val ref = refiner
        val pairs = synchronized(paragraphData) {
            paragraphData[paragraphId]?.filter { it.second.isNotBlank() }?.toList()
        }

        // No refiner or no data → clear refining flag immediately
        if (ref == null || pairs.isNullOrEmpty()) {
            scope.launch(Dispatchers.Main) {
                callback?.onParagraphRefined(paragraphId, "")
            }
            return
        }

        scope.launch(Dispatchers.IO) {
            val refined = try {
                ref.refineParagraph(pairs)
            } catch (e: Throwable) {
                Log.w(TAG, "Refinement failed: ${e.message}")
                ""
            }
            withContext(Dispatchers.Main) {
                callback?.onParagraphRefined(paragraphId, refined)
            }
        }
    }

    /**
     * Reset state for a new session (e.g. "clear all" button).
     * Keeps the LRU translation cache alive: common phrases from prior sessions
     * are still valid translations regardless of session context.
     */
    fun reset() {
        // NOTE: Do NOT null callback — it must survive session resets.
        // The callback is the bridge to MainActivity's UI; nulling it
        // causes all subsequent translations to be silently discarded.
        seqCounter.set(0)
        pendingCount.set(0)
        synchronized(paragraphData) { paragraphData.clear() }
    }

    /**
     * Release all resources.  Unlike [reset], this also clears the cache because
     * the Activity is being destroyed and the pipeline will be reconstructed.
     * Clearing avoids returning stale results to a future pipeline instance
     * that may use a different engine.
     */
    fun close() {
        callback = null; engine = null; refiner = null
        seqCounter.set(0); pendingCount.set(0)
        synchronized(paragraphData) { paragraphData.clear() }
        synchronized(translationCache) { translationCache.clear() }
    }
}
