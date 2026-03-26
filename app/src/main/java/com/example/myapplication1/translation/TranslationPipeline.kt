package com.example.myapplication1.translation

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger

/**
 * Two-stage translation pipeline with paragraph-level refinement.
 *
 * **Stage 1 – Per-sentence translation** (low latency):
 *   Each sentence is translated independently and delivered to TTS in strict order.
 *   This gives the user immediate audio feedback.
 *
 * **Stage 2 – Paragraph refinement** (quality):
 *   When a paragraph is "closed" (all sentences translated), the refiner is called
 *   with all (EN, rawZH) pairs to produce a single polished Chinese paragraph.
 *   This updates the display but does NOT re-trigger TTS (audio was already played
 *   from Stage 1).
 *
 * Ordered delivery: every sentence is delivered to TTS in submission sequence.
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

    // ---- Per-sentence tracking ----
    private val sequenceCounter = AtomicInteger(0)
    private val pendingCount = AtomicInteger(0)
    private val resultBuffer = mutableMapOf<Int, String?>()
    private val bufferMutex = Mutex()
    private var nextDeliverSeqId = 0
    private val translationSemaphore = kotlinx.coroutines.sync.Semaphore(maxConcurrentTranslations)

    // ---- Paragraph tracking ----
    data class SentenceRecord(val seqId: Int, val en: String, var zh: String = "", var done: Boolean = false)
    private val paragraphSentences = mutableMapOf<Int, MutableList<SentenceRecord>>()
    private val paragraphMutex = Mutex()

    private var callback: Callback? = null
    private var engine: TranslationEngine? = null
    private var refiner: TranslationRefiner? = null

    val pendingTranslations: Int get() = pendingCount.get()

    fun setCallback(cb: Callback?) { callback = cb }
    fun setEngine(eng: TranslationEngine?) { engine = eng }
    fun setRefiner(ref: TranslationRefiner?) { refiner = ref }

    // ===================== Stage 1: per-sentence translation =====================

    fun submitSentence(paragraphId: Int, en: String): Int {
        val seqId = sequenceCounter.getAndIncrement()
        pendingCount.incrementAndGet()
        callback?.onTranslationStarted(seqId, en)

        scope.launch {
            paragraphMutex.withLock {
                paragraphSentences.getOrPut(paragraphId) { mutableListOf() }
                    .add(SentenceRecord(seqId, en))
            }

            bufferMutex.withLock { resultBuffer[seqId] = null }

            translationSemaphore.acquire()
            try {
                val eng = engine ?: throw Exception("No translation engine")
                val transStart = System.currentTimeMillis()

                val zh = if (refiner != null && eng.isLlmBased) {
                    refiner!!.translateAndRefine(en).displayText
                } else {
                    eng.translate(en)
                }
                val translationMs = System.currentTimeMillis() - transStart

                callback?.onTranslationResult(seqId, en, zh)
                callback?.onLatencyMeasured(translationMs, 0)

                paragraphMutex.withLock {
                    paragraphSentences[paragraphId]?.find { it.seqId == seqId }?.apply {
                        this.zh = zh; this.done = true
                    }
                }

                bufferMutex.withLock { resultBuffer[seqId] = zh }
                tryDeliverOrdered()
                pendingCount.decrementAndGet()
            } catch (e: Throwable) {
                Log.e(TAG, "Translation failed seq=$seqId: ${e.message}")
                callback?.onTranslationError(seqId, en, e.message ?: "Unknown error")
                bufferMutex.withLock { resultBuffer[seqId] = "[翻译失败]" }
                paragraphMutex.withLock {
                    paragraphSentences[paragraphId]?.find { it.seqId == seqId }?.apply {
                        this.zh = ""; this.done = true
                    }
                }
                tryDeliverOrdered()
                pendingCount.decrementAndGet()
            } finally {
                translationSemaphore.release()
            }
        }
        return seqId
    }

    // ===================== TTS delivery =====================

    /** Deliver completed translations to TTS in strict order. */
    private suspend fun tryDeliverOrdered() {
        val toDeliver = mutableListOf<String>()
        bufferMutex.withLock {
            while (true) {
                val zh = resultBuffer[nextDeliverSeqId]
                if (zh != null) {
                    resultBuffer.remove(nextDeliverSeqId)
                    nextDeliverSeqId++
                    if (zh != "[翻译失败]") toDeliver.add(zh)
                } else break
            }
        }
        for (zh in toDeliver) {
            callback?.onTtsReady(zh)
        }
    }

    // ===================== Stage 2: paragraph refinement =====================

    fun closeParagraph(paragraphId: Int) {
        scope.launch {
            // Wait for all sentences in this paragraph (max 15 s)
            var waited = 0
            while (waited < 15000) {
                val allDone = paragraphMutex.withLock {
                    paragraphSentences[paragraphId]?.all { it.done } ?: true
                }
                if (allDone) break
                delay(100); waited += 100
            }

            val ref = refiner ?: return@launch
            val sentences = paragraphMutex.withLock {
                paragraphSentences[paragraphId]?.filter { it.done }?.toList() ?: return@launch
            }
            if (sentences.isEmpty()) return@launch

            val pairs = sentences.filter { it.zh.isNotBlank() }.map { it.en to it.zh }
            if (pairs.isEmpty()) return@launch

            try {
                val refined = ref.refineParagraph(pairs)
                if (refined.isNotBlank()) {
                    callback?.onParagraphRefined(paragraphId, refined)
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
        scope.launch {
            bufferMutex.withLock { resultBuffer.clear() }
            paragraphMutex.withLock { paragraphSentences.clear() }
        }
    }

    fun close() {
        callback = null; engine = null; refiner = null
        sequenceCounter.set(0)
        nextDeliverSeqId = 0
        pendingCount.set(0)
    }
}
