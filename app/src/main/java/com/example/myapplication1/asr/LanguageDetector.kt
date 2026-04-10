package com.example.myapplication1.asr

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * Spoken language identification using sherpa-onnx Whisper tiny model.
 * Detects the language of an audio segment and returns ISO 639-1 code.
 *
 * Usage: accumulate ~3 seconds of audio, then call [detect] once per utterance.
 */
class LanguageDetector(private val context: Context) {

    companion object {
        private const val TAG = "LanguageDetector"
        private const val MODEL_DIR = "sherpa-lid-whisper-tiny"
        private const val ENCODER_FILE = "tiny-encoder.int8.onnx"
        private const val DECODER_FILE = "tiny-decoder.int8.onnx"
        private const val HF_REPO = "csukuangfj/sherpa-onnx-whisper-tiny"
        /** Samples needed for reliable detection (3 seconds @ 16kHz) */
        const val DETECTION_SAMPLES = 16000 * 3
    }

    interface Callback {
        fun onDownloadProgress(file: String, percent: Int)
        fun onError(message: String)
    }

    @Volatile private var lid: SpokenLanguageIdentification? = null
    @Volatile private var ready = false

    fun isReady() = ready

    fun isModelDownloaded(): Boolean {
        val dir = File(context.filesDir, MODEL_DIR)
        return File(dir, ENCODER_FILE).let { it.exists() && it.length() > 1_000_000 }
            && File(dir, DECODER_FILE).let { it.exists() && it.length() > 1_000_000 }
    }

    fun init(): Boolean {
        if (ready) return true
        for (provider in com.example.myapplication1.AccelerationConfig.providerChain(context)) {
            if (tryInit(provider)) {
                Log.i(TAG, "Language detector initialized (provider=$provider)")
                return true
            }
            if (provider != com.example.myapplication1.AccelerationConfig.CPU) {
                Log.w(TAG, "LID init with provider=$provider failed, falling back to CPU")
            }
        }
        return false
    }

    private fun tryInit(provider: String): Boolean {
        val dir = File(context.filesDir, MODEL_DIR)
        return try {
            val config = SpokenLanguageIdentificationConfig(
                whisper = SpokenLanguageIdentificationWhisperConfig(
                    encoder = File(dir, ENCODER_FILE).absolutePath,
                    decoder = File(dir, DECODER_FILE).absolutePath,
                ),
                numThreads = 2,
                debug = false,
                provider = provider,
            )
            lid = SpokenLanguageIdentification(null, config)
            ready = true
            true
        } catch (e: Throwable) {
            Log.e(TAG, "Init failed (provider=$provider): ${e.message}", e)
            try { lid?.release() } catch (_: Throwable) {}
            lid = null
            false
        }
    }

    /**
     * Detect spoken language from audio samples.
     * @param samples PCM float samples at 16kHz
     * @return ISO 639-1 language code (e.g. "en", "zh", "ja", "ko", "fr", "de")
     */
    fun detect(samples: FloatArray, sampleRate: Int = 16000): String {
        val l = lid ?: return "en"
        return try {
            val stream = l.createStream()
            stream.acceptWaveform(samples, sampleRate)
            val lang = l.compute(stream)
            stream.release()
            Log.d(TAG, "Detected language: $lang")
            lang
        } catch (e: Throwable) {
            Log.w(TAG, "Detection failed: ${e.message}")
            "en"
        }
    }

    suspend fun downloadModel(callback: Callback): Boolean = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, MODEL_DIR).apply { mkdirs() }
        val base = "https://huggingface.co/$HF_REPO/resolve/main"
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .followRedirects(true).followSslRedirects(true)
            .build()

        for (name in listOf(ENCODER_FILE, DECODER_FILE)) {
            val outFile = File(dir, name)
            if (outFile.exists() && outFile.length() > 1_000_000) {
                withContext(Dispatchers.Main) { callback.onDownloadProgress(name, 100) }
                continue
            }
            outFile.delete()
            if (!downloadFile(client, "$base/$name", outFile, name, callback))
                return@withContext false
        }
        true
    }

    private suspend fun downloadFile(
        client: OkHttpClient, url: String, outFile: File,
        displayName: String, callback: Callback
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            withContext(Dispatchers.Main) { callback.onDownloadProgress(displayName, 0) }
            val response = client.newCall(Request.Builder().url(url).build()).execute()
            if (!response.isSuccessful) {
                withContext(Dispatchers.Main) { callback.onError("下载失败: $displayName (${response.code})") }
                return@withContext false
            }
            val body = response.body ?: return@withContext false
            val total = body.contentLength()
            val tmp = File(outFile.parent, "${outFile.name}.tmp")
            var dl = 0L
            body.byteStream().use { i ->
                FileOutputStream(tmp).use { o ->
                    val buf = ByteArray(8192)
                    while (true) {
                        val n = i.read(buf); if (n == -1) break
                        o.write(buf, 0, n); dl += n
                        if (total > 0) withContext(Dispatchers.Main) {
                            callback.onDownloadProgress(displayName, (dl * 100 / total).toInt())
                        }
                    }
                }
            }
            tmp.renameTo(outFile)
            withContext(Dispatchers.Main) { callback.onDownloadProgress(displayName, 100) }
            true
        } catch (e: Throwable) {
            withContext(Dispatchers.Main) { callback.onError("下载失败: $displayName - ${e.message}") }
            false
        }
    }

    fun release() {
        try { lid?.release() } catch (_: Throwable) {}
        lid = null; ready = false
    }
}
