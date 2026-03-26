package com.example.myapplication1.translation

import android.util.Log
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicInteger

/**
 * Minimal translation pipeline: translate each sentence ASAP, TTS ASAP.
 * No ordering, no queuing, no semaphores — pure speed.
 *
 * Paragraph refinement is fire-and-forget background work that only updates display.
 */
class TranslationPipeline(private val scope: CoroutineScope) {

    companion object { private const val TAG = "TransPipeline" }

    interface Callback {
        fun onTranslationStarted(seqId: Int, en: String)
        fun onTranslationResult(seqId: Int, en: String, zh: String)
        fun onTranslationError(seqId: Int, en: String, error: String)
        fun onTtsReady(zh: String)
        fun onLatencyMeasured(translationMs: Long)
        fun onParagraphRefined(paragraphId: Int, refinedZh: String)
    }

    private val seqCounter = AtomicInteger(0)
    private val pendingCount = AtomicInteger(0)

    // Paragraph: just stores (en,zh) pairs for later refinement — no blocking
    private val paragraphData = mutableMapOf<Int, MutableList<Pair<String, String>>>()

    @Volatile private var callback: Callback? = null
    private var engine: TranslationEngine? = null
    private var refiner: TranslationRefiner? = null

    val pendingTranslations: Int get() = pendingCount.get()

    fun setCallback(cb: Callback?) { callback = cb }
    fun setEngine(eng: TranslationEngine?) { engine = eng }
    fun setRefiner(ref: TranslationRefiner?) { refiner = ref }

    /** Allocate a seqId so caller can update UI state before translation starts. */
    fun allocateSeqId(): Int = seqCounter.getAndIncrement()

    /**
     * Start translating a sentence with a pre-allocated seqId.
     * Caller MUST have already updated UI state with this seqId.
     */
    fun submitSentence(seqId: Int, paragraphId: Int, en: String) {
        pendingCount.incrementAndGet()

        scope.launch(Dispatchers.IO) {
            try {
                val eng = engine ?: throw Exception("No translation engine")
                val t0 = System.currentTimeMillis()
                val zh = eng.translate(en)
                val ms = System.currentTimeMillis() - t0

                // Store for later paragraph refinement
                synchronized(paragraphData) {
                    paragraphData.getOrPut(paragraphId) { mutableListOf() }.add(en to zh)
                }

                // Fire callbacks immediately — no ordering wait
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

    fun reset() {
        callback = null
        seqCounter.set(0)
        pendingCount.set(0)
        synchronized(paragraphData) { paragraphData.clear() }
    }

    fun close() {
        callback = null; engine = null; refiner = null
        seqCounter.set(0); pendingCount.set(0)
        synchronized(paragraphData) { paragraphData.clear() }
    }
}
