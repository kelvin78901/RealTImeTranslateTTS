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

    /**
     * Submit a sentence. Returns seqId.
     * Translation runs immediately in a coroutine — no queue, no semaphore.
     * As soon as translation is done: UI update + TTS, zero delay.
     */
    fun submitSentence(paragraphId: Int, en: String): Int {
        val seqId = seqCounter.getAndIncrement()
        pendingCount.incrementAndGet()
        callback?.onTranslationStarted(seqId, en)

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
        return seqId
    }

    /**
     * Fire-and-forget paragraph refinement. Runs in background.
     * Only updates display text — TTS already played per-sentence.
     */
    fun closeParagraph(paragraphId: Int) {
        val ref = refiner ?: return
        val pairs = synchronized(paragraphData) {
            paragraphData[paragraphId]?.filter { it.second.isNotBlank() }?.toList()
        }
        if (pairs.isNullOrEmpty()) return

        scope.launch(Dispatchers.IO) {
            try {
                val refined = ref.refineParagraph(pairs)
                if (refined.isNotBlank()) {
                    withContext(Dispatchers.Main) {
                        callback?.onParagraphRefined(paragraphId, refined)
                    }
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Refinement failed: ${e.message}")
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
