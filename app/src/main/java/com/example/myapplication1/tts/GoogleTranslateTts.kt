package com.example.myapplication1.tts

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.net.URLEncoder
import kotlin.coroutines.resume

/**
 * Fallback TTS using Google Translate's public TTS endpoint.
 * Requires INTERNET permission. Splits long text into chunks to stay within URL limits.
 */
class GoogleTranslateTts : AutoCloseable {

    companion object {
        private const val TAG = "GoogleTranslateTts"
        private const val MAX_CHUNK_LEN = 180
    }

    @Volatile
    private var currentPlayer: MediaPlayer? = null

    suspend fun speak(text: String, lang: String = "zh-CN") {
        if (text.isBlank()) return
        val chunks = splitText(text, MAX_CHUNK_LEN)
        for (chunk in chunks) {
            playChunk(chunk, lang)
        }
    }

    private suspend fun playChunk(text: String, lang: String) {
        stop()
        val encoded = withContext(Dispatchers.IO) {
            URLEncoder.encode(text, "UTF-8")
        }
        val url = "https://translate.google.com/translate_tts?ie=UTF-8&client=tw-ob&tl=$lang&q=$encoded"

        withContext(Dispatchers.IO) {
            suspendCancellableCoroutine { cont ->
                val mp = MediaPlayer()
                currentPlayer = mp
                try {
                    mp.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANT)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    mp.setDataSource(url)
                    mp.setOnPreparedListener { it.start() }
                    mp.setOnCompletionListener {
                        it.release()
                        if (currentPlayer === it) currentPlayer = null
                        if (cont.isActive) cont.resume(Unit)
                    }
                    mp.setOnErrorListener { mp2, what, extra ->
                        Log.e(TAG, "MediaPlayer error: what=$what extra=$extra")
                        mp2.release()
                        if (currentPlayer === mp2) currentPlayer = null
                        if (cont.isActive) cont.resume(Unit)
                        true
                    }
                    mp.prepareAsync()
                    cont.invokeOnCancellation {
                        try { mp.release() } catch (_: Throwable) {}
                        if (currentPlayer === mp) currentPlayer = null
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "Failed to play TTS: ${t.message}")
                    try { mp.release() } catch (_: Throwable) {}
                    if (currentPlayer === mp) currentPlayer = null
                    if (cont.isActive) cont.resume(Unit)
                }
            }
        }
    }

    fun stop() {
        try {
            currentPlayer?.let {
                if (it.isPlaying) it.stop()
                it.release()
            }
        } catch (_: Throwable) {}
        currentPlayer = null
    }

    override fun close() { stop() }

    private fun splitText(text: String, maxLen: Int): List<String> {
        if (text.length <= maxLen) return listOf(text)
        val chunks = mutableListOf<String>()
        val delimiters = charArrayOf('。', '！', '？', '；', '.', '!', '?', ';', ',', '，')
        var remaining = text
        while (remaining.length > maxLen) {
            var splitIdx = -1
            for (i in (maxLen - 1) downTo (maxLen / 2)) {
                if (i < remaining.length && remaining[i] in delimiters) {
                    splitIdx = i + 1; break
                }
            }
            if (splitIdx == -1) splitIdx = maxLen
            chunks.add(remaining.substring(0, splitIdx))
            remaining = remaining.substring(splitIdx)
        }
        if (remaining.isNotEmpty()) chunks.add(remaining)
        return chunks
    }
}
