package com.example.myapplication1.translation

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger

/**
 * Two-stage translation pipeline with paragraph-level refinement.
 *
 * Stage 1: Per-sentence translation → immediate UI update + TTS.
 *   Always uses eng.translate() for lowest latency.  No refiner in this path.
 *
 * Stage 2: Paragraph refinement (lazy, after silence gap) → display polish only.
 *   Uses refiner to combine and fix all sentences.  No re-TTS.
 */
class TranslationPipeline(
    private val scope: CoroutineScope,
    private val maxConcurrentTranslations: Int = 3
) {
    companion object {
        private const val TAG = "TransPipeline"
    }

    interface Callback {
        fun onTranslationStarted(seqId: Int, en: String)
        fun onTranslationResult(seqId: Int, en: String, zh: String)
        fun onTranslationError(seqId: Int, en: String, error: String)
        fun onTtsReady(zh: String)
        fun onLatencyMeasured(translationMs: Long, refinementMs: Long)
        fun onParagraphRefined(paragraphId: Int, refinedZh: String)
    }

    // Per-sentence ordered delivery
    private val sequenceCounter = AtomicInteger(0)
    private val pendingCount = AtomicInteger(0)
    private val resultBuffer = mutableMapOf<Int, String>()  // seqId → zh (only NON-NULL results)
    private val bufferMutex = Mutex()
    @Volatile private var nextDeliverSeqId = 0
    private val translationSemaphore = kotlinx.coroutines.sync.Semaphore(maxConcurrentTranslations)

    // Paragraph tracking
    data class SentenceRecord(val seqId: Int, val en: String, var zh: String = "", var done: Boolean = false)
    private val paragraphSentences = mutableMapOf<Int, MutableList<SentenceRecord>>()
    private val paragraphMutex = Mutex()

    @Volatile private var callback: Callback? = null
    private var engine: TranslationEngine? = null
    private var refiner: TranslationRefiner? = null

    val pendingTranslations: Int get() = pendingCount.get()

    fun setCallback(cb: Callback?) { callback = cb }
    fun setEngine(eng: TranslationEngine?) { engine = eng }
    fun setRefiner(ref: TranslationRefiner?) { refiner = ref }

    // ===================== Stage 1: per-sentence fast translation =====================

    fun submitSentence(paragraphId: Int, en: String): Int {
        val seqId = sequenceCounter.getAndIncrement()
        pendingCount.incrementAndGet()

        // Register paragraph record synchronously (called from Main)
        val list = paragraphSentences.getOrPut(paragraphId) { mutableListOf() }
        list.add(SentenceRecord(seqId, en))

        callback?.onTranslationStarted(seqId, en)

        scope.launch {
            translationSemaphore.acquire()
            try {
                val eng = engine ?: throw Exception("No translation engine")
                val transStart = System.currentTimeMillis()

                // ALWAYS use eng.translate() for lowest latency per-sentence.
                // Refiner is used only at paragraph level (Stage 2).
                val zh = eng.translate(en)
                val translationMs = System.currentTimeMillis() - transStart

                // 1. Update UI immediately
                callback?.onTranslationResult(seqId, en, zh)
                callback?.onLatencyMeasured(translationMs, 0)

                // 2. Update paragraph record
                paragraphMutex.withLock {
                    paragraphSentences[paragraphId]?.find { it.seqId == seqId }?.apply {
                        this.zh = zh; this.done = true
                    }
                }

                // 3. TTS delivery (ordered)
                bufferMutex.withLock { resultBuffer[seqId] = zh }
                tryDeliverOrdered()
            } catch (e: Throwable) {
                Log.e(TAG, "Translation failed seq=$seqId: ${e.message}")
                callback?.onTranslationError(seqId, en, e.message ?: "Unknown error")

                paragraphMutex.withLock {
                    paragraphSentences[paragraphId]?.find { it.seqId == seqId }?.apply {
                        this.zh = ""; this.done = true
                    }
                }
                // Mark as failed in buffer so TTS ordering isn't blocked
                bufferMutex.withLock { resultBuffer[seqId] = "" }
                tryDeliverOrdered()
            } finally {
                pendingCount.decrementAndGet()
                translationSemaphore.release()
            }
        }
        return seqId
    }

    // ===================== TTS delivery =====================

    /**
     * Deliver completed translations to TTS in strict sequence order.
     * Only delivers if resultBuffer contains a NON-NULL entry for nextDeliverSeqId.
     * Empty strings (failed translations) are consumed but not sent to TTS.
     */
    private suspend fun tryDeliverOrdered() {
        val toDeliver = mutableListOf<String>()
        bufferMutex.withLock {
            while (true) {
                val zh = resultBuffer[nextDeliverSeqId]  // returns null if key absent
                if (zh != null) {
                    resultBuffer.remove(nextDeliverSeqId)
                    nextDeliverSeqId++
                    if (zh.isNotBlank()) toDeliver.add(zh)
                } else {
                    break  // next seqId not ready yet — stop
                }
            }
        }
        val cb = callback ?: return
        for (zh in toDeliver) {
            cb.onTtsReady(zh)
        }
    }

    // ===================== Stage 2: paragraph refinement =====================

    fun closeParagraph(paragraphId: Int) {
        scope.launch {
            // Wait for all sentences in this paragraph, max 10s
            val deadline = System.currentTimeMillis() + 10_000
            while (System.currentTimeMillis() < deadline) {
                val allDone = paragraphMutex.withLock {
                    paragraphSentences[paragraphId]?.all { it.done } ?: true
                }
                if (allDone) break
                delay(150)
            }

            val ref = refiner ?: return@launch
            val cb = callback ?: return@launch

            val pairs = paragraphMutex.withLock {
                paragraphSentences[paragraphId]
                    ?.filter { it.done && it.zh.isNotBlank() }
                    ?.map { it.en to it.zh }
                    ?: emptyList()
            }
            if (pairs.isEmpty()) return@launch

            try {
                val refined = ref.refineParagraph(pairs)
                if (refined.isNotBlank()) {
                    cb.onParagraphRefined(paragraphId, refined)
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Paragraph refinement failed: ${e.message}")
            }
        }
    }

    // ===================== Lifecycle =====================

    fun reset() {
        callback = null
        sequenceCounter.set(0)
        nextDeliverSeqId = 0
        pendingCount.set(0)
        paragraphSentences.clear()
        scope.launch {
            bufferMutex.withLock { resultBuffer.clear() }
        }
    }

    fun close() {
        callback = null; engine = null; refiner = null
        sequenceCounter.set(0)
        nextDeliverSeqId = 0
        pendingCount.set(0)
        paragraphSentences.clear()
    }
}
