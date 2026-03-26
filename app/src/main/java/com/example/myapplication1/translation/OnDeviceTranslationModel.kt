package com.example.myapplication1.translation

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Downloadable on-device translation model definitions.
 * Each model includes ONNX encoder/decoder files and a tokenizer.
 */
enum class OnDeviceTranslationModel(
    val label: String,
    val desc: String,
    val approxSizeMB: Int,
    val encoderUrl: String,
    val decoderUrl: String,
    val tokenizerUrl: String,
    val configUrl: String,
    val srcLangToken: String,
    val tgtLangToken: String,
    /** true = tokenizer is vocab.json (plain dict), false = tokenizer.json (HuggingFace format) */
    val useVocabJson: Boolean = false,
    /** SentencePiece source model URL (for Viterbi tokenization) */
    val sourceSpmUrl: String = "",
    /** SentencePiece target model URL (for detokenization) */
    val targetSpmUrl: String = "",
    /** Beam search width (1 = greedy) */
    val defaultBeamSize: Int = 1
) {
    OPUS_MT_EN_ZH(
        label = "Opus-MT EN→ZH",
        desc = "Helsinki-NLP · 轻量高效 (IR8)",
        approxSizeMB = 452,
        encoderUrl = "https://huggingface.co/xun/opus-mt-en-zh-onnx/resolve/main/encoder_model.onnx",
        decoderUrl = "https://huggingface.co/xun/opus-mt-en-zh-onnx/resolve/main/decoder_model.onnx",
        tokenizerUrl = "https://huggingface.co/xun/opus-mt-en-zh-onnx/resolve/main/vocab.json",
        configUrl = "https://huggingface.co/xun/opus-mt-en-zh-onnx/resolve/main/config.json",
        srcLangToken = "",
        tgtLangToken = "",
        useVocabJson = true,
        sourceSpmUrl = "https://huggingface.co/xun/opus-mt-en-zh-onnx/resolve/main/source.spm",
        targetSpmUrl = "https://huggingface.co/xun/opus-mt-en-zh-onnx/resolve/main/target.spm",
        defaultBeamSize = 4
    ),
    NLLB_600M_INT8(
        label = "NLLB-600M",
        desc = "Meta · 高质量多语言 (IR8)",
        approxSizeMB = 1150,
        encoderUrl = "https://huggingface.co/felerminoali/onnx_nllb_quantized/resolve/main/encoder_model.onnx",
        decoderUrl = "https://huggingface.co/felerminoali/onnx_nllb_quantized/resolve/main/decoder_model.onnx",
        tokenizerUrl = "https://huggingface.co/felerminoali/onnx_nllb_quantized/resolve/main/tokenizer.json",
        configUrl = "https://huggingface.co/felerminoali/onnx_nllb_quantized/resolve/main/config.json",
        srcLangToken = "eng_Latn",
        tgtLangToken = "zho_Hans",
        defaultBeamSize = 1
    );

    val tokenizerFileName: String get() = if (useVocabJson) "vocab.json" else "tokenizer.json"
    val hasSpm: Boolean get() = sourceSpmUrl.isNotBlank()

    fun modelDir(ctx: Context): File = File(ctx.filesDir, "translation_models/${name.lowercase()}")

    /** Version marker — bump to force re-download when URLs or file set changes */
    val modelVersion: String get() = "ir8_v3"

    fun isDownloaded(ctx: Context): Boolean {
        val dir = modelDir(ctx)
        return dir.exists() &&
            File(dir, "encoder.onnx").exists() &&
            File(dir, "decoder.onnx").exists() &&
            File(dir, tokenizerFileName).exists() &&
            (!hasSpm || File(dir, "source.spm").exists()) &&
            File(dir, ".version").let { it.exists() && it.readText().trim() == modelVersion }
    }
}

class TranslationModelManager(private val context: Context) {

    companion object {
        private const val TAG = "TransModelMgr"
        private val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    interface DownloadCallback {
        fun onProgress(file: String, percent: Int)
        fun onComplete(success: Boolean, error: String?)
    }

    suspend fun download(model: OnDeviceTranslationModel, callback: DownloadCallback) {
        withContext(Dispatchers.IO) {
            val dir = model.modelDir(context)

            // If stale download from old URLs exists, clean it first
            if (dir.exists() && !model.isDownloaded(context)) {
                Log.i(TAG, "Cleaning stale model directory: ${dir.name}")
                try { dir.deleteRecursively() } catch (_: Throwable) {}
            }
            dir.mkdirs()

            try {
                downloadFile(model.encoderUrl, File(dir, "encoder.onnx"), "encoder") { p ->
                    callback.onProgress("encoder.onnx", p)
                }
                downloadFile(model.decoderUrl, File(dir, "decoder.onnx"), "decoder") { p ->
                    callback.onProgress("decoder.onnx", p)
                }
                downloadFile(model.tokenizerUrl, File(dir, model.tokenizerFileName), "tokenizer") { p ->
                    callback.onProgress(model.tokenizerFileName, p)
                }
                downloadFile(model.configUrl, File(dir, "config.json"), "config") { p ->
                    callback.onProgress("config.json", p)
                }

                // Download SentencePiece models if available
                if (model.sourceSpmUrl.isNotBlank()) {
                    downloadFile(model.sourceSpmUrl, File(dir, "source.spm"), "source.spm") { p ->
                        callback.onProgress("source.spm", p)
                    }
                }
                if (model.targetSpmUrl.isNotBlank()) {
                    downloadFile(model.targetSpmUrl, File(dir, "target.spm"), "target.spm") { p ->
                        callback.onProgress("target.spm", p)
                    }
                }

                // Write version marker
                File(dir, ".version").writeText(model.modelVersion)

                withContext(Dispatchers.Main) {
                    callback.onComplete(true, null)
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Download failed: ${e.message}")
                // Clean up partial download
                try { dir.deleteRecursively() } catch (_: Throwable) {}
                withContext(Dispatchers.Main) {
                    callback.onComplete(false, e.message)
                }
            }
        }
    }

    fun deleteModel(model: OnDeviceTranslationModel) {
        try { model.modelDir(context).deleteRecursively() } catch (_: Throwable) {}
    }

    private fun downloadFile(
        url: String,
        dst: File,
        label: String,
        onProgress: (Int) -> Unit
    ) {
        if (dst.exists() && dst.length() > 0) {
            Log.i(TAG, "$label already exists, skipping")
            onProgress(100)
            return
        }

        Log.i(TAG, "Downloading $label from $url")
        val request = Request.Builder().url(url).build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) throw Exception("HTTP ${response.code} for $label")

        val body = response.body ?: throw Exception("Empty response for $label")
        val total = body.contentLength()
        var downloaded = 0L

        val tmpFile = File(dst.parent, "${dst.name}.tmp")
        tmpFile.outputStream().use { out ->
            body.byteStream().use { input ->
                val buf = ByteArray(8192)
                var n: Int
                while (input.read(buf).also { n = it } != -1) {
                    out.write(buf, 0, n)
                    downloaded += n
                    if (total > 0) {
                        onProgress((downloaded * 100 / total).toInt())
                    }
                }
            }
        }
        tmpFile.renameTo(dst)
        onProgress(100)
        Log.i(TAG, "$label downloaded: ${dst.length()} bytes")
    }
}
