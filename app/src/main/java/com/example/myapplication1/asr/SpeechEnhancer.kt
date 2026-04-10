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
 * GTCRN speech denoiser using sherpa-onnx OfflineSpeechDenoiser.
 * Processes each audio chunk independently to reduce background noise
 * before passing to the ASR engine.
 *
 * Model: gtcrn_simple.onnx (~535KB)
 */
class SpeechEnhancer(private val context: Context) {

    companion object {
        private const val TAG = "SpeechEnhancer"
        private const val MODEL_DIR = "sherpa-denoiser"
        private const val MODEL_FILE = "gtcrn_simple.onnx"
        private const val MODEL_URL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/speech-enhancement-models/gtcrn_simple.onnx"
    }

    interface Callback {
        fun onDownloadProgress(file: String, percent: Int)
        fun onError(message: String)
    }

    @Volatile private var denoiser: OfflineSpeechDenoiser? = null
    @Volatile private var ready = false

    fun isReady() = ready

    fun isModelDownloaded(): Boolean {
        val f = File(context.filesDir, "$MODEL_DIR/$MODEL_FILE")
        return f.exists() && f.length() > 10_000
    }

    fun init(): Boolean {
        if (ready) return true
        val modelPath = File(context.filesDir, "$MODEL_DIR/$MODEL_FILE")
        if (!modelPath.exists()) return false
        for (provider in com.example.myapplication1.AccelerationConfig.providerChain(context)) {
            if (tryInit(modelPath, provider)) {
                Log.i(TAG, "GTCRN denoiser initialized (provider=$provider)")
                return true
            }
            if (provider != com.example.myapplication1.AccelerationConfig.CPU) {
                Log.w(TAG, "Denoiser init with provider=$provider failed, falling back to CPU")
            }
        }
        return false
    }

    private fun tryInit(modelPath: File, provider: String): Boolean {
        return try {
            val config = OfflineSpeechDenoiserConfig(
                model = OfflineSpeechDenoiserModelConfig(
                    gtcrn = OfflineSpeechDenoiserGtcrnModelConfig(
                        model = modelPath.absolutePath
                    ),
                    numThreads = 2,
                    debug = false,
                    provider = provider,
                )
            )
            denoiser = OfflineSpeechDenoiser(null, config)
            ready = true
            true
        } catch (e: Throwable) {
            Log.e(TAG, "Init failed (provider=$provider): ${e.message}", e)
            denoiser = null
            false
        }
    }

    /** Denoise a chunk of PCM float samples. Returns original samples on failure. */
    fun denoise(samples: FloatArray, sampleRate: Int): FloatArray {
        val d = denoiser ?: return samples
        return try {
            d.run(samples, sampleRate).samples
        } catch (e: Throwable) {
            Log.w(TAG, "Denoise failed: ${e.message}")
            samples
        }
    }

    suspend fun downloadModel(callback: Callback): Boolean = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, MODEL_DIR).apply { mkdirs() }
        val outFile = File(dir, MODEL_FILE)
        if (outFile.exists() && outFile.length() > 10_000) return@withContext true

        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true).followSslRedirects(true)
            .build()
        try {
            withContext(Dispatchers.Main) { callback.onDownloadProgress(MODEL_FILE, 0) }
            val response = client.newCall(Request.Builder().url(MODEL_URL).build()).execute()
            if (!response.isSuccessful) {
                withContext(Dispatchers.Main) { callback.onError("下载失败 (${response.code})") }
                return@withContext false
            }
            val body = response.body ?: return@withContext false
            val total = body.contentLength()
            val tmp = File(dir, "$MODEL_FILE.tmp")
            var dl = 0L
            body.byteStream().use { i ->
                FileOutputStream(tmp).use { o ->
                    val buf = ByteArray(8192)
                    while (true) {
                        val n = i.read(buf); if (n == -1) break
                        o.write(buf, 0, n); dl += n
                        if (total > 0) withContext(Dispatchers.Main) {
                            callback.onDownloadProgress(MODEL_FILE, (dl * 100 / total).toInt())
                        }
                    }
                }
            }
            tmp.renameTo(outFile)
            withContext(Dispatchers.Main) { callback.onDownloadProgress(MODEL_FILE, 100) }
            true
        } catch (e: Throwable) {
            withContext(Dispatchers.Main) { callback.onError("下载失败: ${e.message}") }
            false
        }
    }

    fun release() {
        try { denoiser?.release() } catch (_: Throwable) {}
        denoiser = null; ready = false
    }
}
