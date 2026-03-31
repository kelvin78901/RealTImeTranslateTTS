package com.example.myapplication1.tts

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * OpenAI TTS via /v1/audio/speech API.
 * Supports tts-1 (fast) and tts-1-hd (high quality).
 */
class OpenAiTts(private val apiKey: String, private val cacheDir: File) : AutoCloseable {

    companion object {
        private const val TAG = "OpenAiTts"

        val VOICES = listOf(
            "alloy" to "Alloy (中性)",
            "echo" to "Echo (男·低沉)",
            "fable" to "Fable (男·叙事)",
            "onyx" to "Onyx (男·深沉)",
            "nova" to "Nova (女·温暖)",
            "shimmer" to "Shimmer (女·清亮)",
        )
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    @Volatile private var currentPlayer: MediaPlayer? = null

    /**
     * Synthesize and play text. Speed range: 0.25 – 4.0.
     */
    suspend fun speak(text: String, voice: String = "nova", speed: Float = 1.0f, model: String = "tts-1") {
        if (text.isBlank() || apiKey.isBlank()) return
        val file = synthesize(text, voice, speed, model) ?: return
        playFile(file)
        file.delete()
    }

    private suspend fun synthesize(text: String, voice: String, speed: Float, model: String): File? =
        withContext(Dispatchers.IO) {
            try {
                val json = JSONObject().apply {
                    put("model", model)
                    put("input", text)
                    put("voice", voice)
                    put("speed", speed.toDouble())
                }
                val request = Request.Builder()
                    .url("https://api.openai.com/v1/audio/speech")
                    .addHeader("Authorization", "Bearer $apiKey")
                    .post(json.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    Log.e(TAG, "API error: ${response.code}")
                    return@withContext null
                }
                val body = response.body ?: return@withContext null
                val outFile = File(cacheDir, "openai_tts_${System.currentTimeMillis()}.mp3")
                FileOutputStream(outFile).use { fos -> body.byteStream().use { it.copyTo(fos) } }
                outFile
            } catch (e: Throwable) {
                Log.e(TAG, "Synthesis failed: ${e.message}")
                null
            }
        }

    private suspend fun playFile(file: File) {
        stopPlayer()
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
                    mp.setDataSource(file.absolutePath)
                    mp.setOnPreparedListener { it.start() }
                    mp.setOnCompletionListener {
                        it.release(); if (currentPlayer === it) currentPlayer = null
                        if (cont.isActive) cont.resume(Unit)
                    }
                    mp.setOnErrorListener { mp2, _, _ ->
                        mp2.release(); if (currentPlayer === mp2) currentPlayer = null
                        if (cont.isActive) cont.resume(Unit)
                        true
                    }
                    mp.prepareAsync()
                    cont.invokeOnCancellation {
                        try { mp.release() } catch (_: Throwable) {}
                        if (currentPlayer === mp) currentPlayer = null
                    }
                } catch (t: Throwable) {
                    try { mp.release() } catch (_: Throwable) {}
                    if (currentPlayer === mp) currentPlayer = null
                    if (cont.isActive) cont.resume(Unit)
                }
            }
        }
    }

    fun stopPlayer() {
        try { currentPlayer?.let { if (it.isPlaying) it.stop(); it.release() } } catch (_: Throwable) {}
        currentPlayer = null
    }

    override fun close() {
        stopPlayer()
        client.dispatcher.executorService.shutdown()
    }
}
