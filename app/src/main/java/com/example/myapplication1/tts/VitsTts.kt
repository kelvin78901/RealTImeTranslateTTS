package com.example.myapplication1.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.k2fsa.sherpa.onnx.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import kotlin.math.min

/**
 * Unified sherpa-onnx TTS engine supporting all 4 model types in our AAR:
 * VITS, Matcha, Kokoro, Kitten.
 *
 * Each [Model] defines its complete configuration: engine type, files to download,
 * HuggingFace repo, and config builder parameters.
 */
class VitsTts(private val context: Context) {

    companion object {
        private const val TAG = "VitsTts"
        private const val VOCODER_BASE = "https://github.com/k2-fsa/sherpa-onnx/releases/download/vocoder-models"
        // Jieba dict files are universal (same for all Chinese models).
        // Always download from MeloTTS repo which is guaranteed to have them.
        private const val DICT_SOURCE = "https://huggingface.co/csukuangfj/vits-melo-tts-zh_en/resolve/main"

        private val DICT_FILES = listOf(
            "dict/jieba.dict.utf8", "dict/hmm_model.utf8", "dict/idf.utf8",
            "dict/user.dict.utf8", "dict/stop_words.utf8",
            "dict/pos_dict/char_state_tab.utf8", "dict/pos_dict/prob_emit.utf8",
            "dict/pos_dict/prob_start.utf8", "dict/pos_dict/prob_trans.utf8",
        )
    }

    enum class EngineType { VITS, MATCHA, KOKORO, KITTEN }

    /**
     * All sherpa-onnx TTS models supported by our AAR.
     * Organized by language; each entry fully describes download + config.
     */
    enum class Model(
        val label: String,
        val desc: String,
        val lang: String,
        val dirName: String,
        val engineType: EngineType,
        val hfRepo: String,
        val isBuiltin: Boolean,
        val approxSizeMB: Int,
        val speakers: Int,
        val modelFile: String,
        val vocoderFile: String = "",          // Matcha only
        val needsEspeakData: Boolean = false,  // piper/kokoro/kitten
        val needsDict: Boolean = false,        // MeloTTS / Kokoro multi-lang
        val needsLexicon: Boolean = true,
        val fstFiles: List<String> = emptyList(),
        val lexiconFiles: List<String> = listOf("lexicon.txt"),
    ) {
        // ==================== 中文 ====================
        AISHELL3(
            "VITS aishell3", "中文 · 174音色 · 内置", "zh",
            "tts/vits-icefall-zh-aishell3", EngineType.VITS,
            "", true, 31, 174,
            modelFile = "model.onnx",
            fstFiles = listOf("phone.fst", "date.fst", "number.fst", "new_heteronym.fst"),
        ),
        MELO_TTS(
            "MeloTTS 中英", "中英双语 · 高质量 · ~170MB", "zh",
            "sherpa-melo-tts", EngineType.VITS,
            "csukuangfj/vits-melo-tts-zh_en", false, 170, 1,
            modelFile = "model.onnx",
            needsDict = true,
            fstFiles = listOf("phone.fst", "date.fst", "number.fst", "new_heteronym.fst"),
        ),
        MATCHA_BAKER(
            "Matcha Baker", "中文 · 自然度高 · ~130MB", "zh",
            "sherpa-matcha-baker", EngineType.MATCHA,
            "csukuangfj/matcha-icefall-zh-baker", false, 130, 1,
            modelFile = "model-steps-3.onnx",
            vocoderFile = "vocos-22khz-univ.onnx",
            fstFiles = listOf("phone.fst", "date.fst", "number.fst"),
        ),
        // Matcha zh-en removed: crashes our AAR (global ONNX mutex corruption)

        FANCHEN_C(
            "Fanchen-C", "中文 · 187音色 · ~120MB", "zh",
            "sherpa-fanchen-c", EngineType.VITS,
            "csukuangfj/vits-zh-hf-fanchen-C", false, 120, 187,
            modelFile = "vits-zh-hf-fanchen-C.onnx",
            needsDict = true,  // ALL Chinese VITS (except aishell3) need jieba
        ),

        // ==================== 英文 ====================
        PIPER_AMY(
            "Piper Amy", "英文 · 美式女声 · ~16MB", "en",
            "sherpa-piper-amy", EngineType.VITS,
            "csukuangfj/vits-piper-en_US-amy-low", false, 16, 1,
            modelFile = "en_US-amy-low.onnx",
            needsEspeakData = true, needsLexicon = false,
        ),
        KITTEN_NANO(
            "Kitten Nano", "英文 · 轻量快速 · ~20MB", "en",
            "sherpa-kitten-nano", EngineType.KITTEN,
            "csukuangfj/kitten-nano-en-v0_1-fp16", false, 20, 10,
            modelFile = "model.fp16.onnx",
            needsEspeakData = true, needsLexicon = false,
        ),

        // ==================== 其他语言 ====================
        MATCHA_EN_LJ(
            "Matcha LJSpeech", "英文 · 女声 · ~130MB", "en",
            "sherpa-matcha-ljspeech", EngineType.MATCHA,
            "csukuangfj/matcha-icefall-en_US-ljspeech", false, 130, 1,
            modelFile = "model-steps-3.onnx",
            vocoderFile = "vocos-22khz-univ.onnx",
            needsEspeakData = true, needsLexicon = false,
        ),
        COQUI_DE(
            "Coqui DE", "德语 · ~30MB", "de",
            "sherpa-coqui-de", EngineType.VITS,
            "csukuangfj/vits-coqui-de-css10", false, 30, 1,
            modelFile = "model.onnx",
            needsLexicon = false,
        ),
    }

    interface Callback {
        fun onDownloadProgress(file: String, percent: Int)
        fun onError(message: String)
    }

    @Volatile private var ready = false
    private var tts: OfflineTts? = null
    private var currentModel: Model? = null
    @Volatile private var stopRequested = false
    private val speakLock = Any()

    fun isReady() = ready
    fun currentModelLabel() = currentModel?.label ?: ""

    fun isModelDownloaded(model: Model): Boolean {
        if (model.isBuiltin) return true
        val dir = File(context.filesDir, model.dirName)
        if (!File(dir, model.modelFile).let { it.exists() && it.length() > 500_000 }) return false
        if (model.vocoderFile.isNotEmpty() && !File(dir, model.vocoderFile).let { it.exists() && it.length() > 500_000 }) return false
        if (model.needsDict && !File(dir, "dict/jieba.dict.utf8").exists()) return false
        if (model.needsEspeakData && !File(dir, "espeak-ng-data").let { it.exists() && it.isDirectory }) return false
        return true
    }

    // ==================== Init ====================

    fun initModel(model: Model): Boolean {
        if (ready && currentModel == model) return true
        if (currentModel != model) release()

        // Pre-check: native code calls abort() if dict is required but missing.
        // Must check BEFORE calling OfflineTts() — abort can't be caught.
        if (!isModelDownloaded(model)) {
            Log.w(TAG, "${model.label}: required files missing, skipping init")
            return false
        }

        return try {
            val config = buildConfig(model)
            tts = if (model.isBuiltin) OfflineTts(context.assets, config) else OfflineTts(null, config)
            currentModel = model; ready = true
            Log.i(TAG, "${model.label} initialized, sr=${tts!!.sampleRate()}, speakers=${tts!!.numSpeakers()}")
            true
        } catch (e: Throwable) {
            Log.e(TAG, "${model.label} init failed: ${e.message}", e)
            ready = false; false
        }
    }

    private fun buildConfig(m: Model): OfflineTtsConfig {
        val dir = if (m.isBuiltin) m.dirName else File(context.filesDir, m.dirName).absolutePath
        fun p(name: String) = if (m.isBuiltin) "$dir/$name" else "$dir/$name"
        fun exists(name: String) = if (m.isBuiltin) true else File("$dir/$name").exists()

        val fstPaths = m.fstFiles.filter { exists(it) }.joinToString(",") { p(it) }
        val lexicon = m.lexiconFiles.filter { exists(it) }.joinToString(",") { p(it) }
        val espeakDir = if (m.needsEspeakData) {
            if (m.isBuiltin) "espeak-ng-data" else "$dir/espeak-ng-data"
        } else ""
        val dictDir = if (m.needsDict) {
            if (exists("dict")) p("dict") else ""
        } else ""

        val modelConfig = when (m.engineType) {
            EngineType.VITS -> OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = p(m.modelFile), lexicon = lexicon,
                    tokens = p("tokens.txt"), dataDir = espeakDir, dictDir = dictDir,
                ),
                numThreads = 2, debug = false, provider = "cpu",
            )
            EngineType.MATCHA -> OfflineTtsModelConfig(
                matcha = OfflineTtsMatchaModelConfig(
                    acousticModel = p(m.modelFile), vocoder = p(m.vocoderFile),
                    lexicon = lexicon, tokens = p("tokens.txt"),
                    dataDir = espeakDir, dictDir = dictDir,
                ),
                numThreads = 2, debug = false, provider = "cpu",
            )
            EngineType.KOKORO -> OfflineTtsModelConfig(
                kokoro = OfflineTtsKokoroModelConfig(
                    model = p(m.modelFile), voices = p("voices.bin"),
                    tokens = p("tokens.txt"), dataDir = espeakDir,
                    lexicon = lexicon, dictDir = dictDir,
                ),
                numThreads = 2, debug = false, provider = "cpu",
            )
            EngineType.KITTEN -> OfflineTtsModelConfig(
                kitten = OfflineTtsKittenModelConfig(
                    model = p(m.modelFile), voices = p("voices.bin"),
                    tokens = p("tokens.txt"), dataDir = espeakDir,
                ),
                numThreads = 2, debug = false, provider = "cpu",
            )
        }
        return OfflineTtsConfig(model = modelConfig, ruleFsts = fstPaths)
    }

    // ==================== Download ====================

    suspend fun downloadModel(model: Model, callback: Callback): Boolean = withContext(Dispatchers.IO) {
        if (model.isBuiltin) return@withContext true
        val dir = File(context.filesDir, model.dirName).apply { mkdirs() }
        val base = "https://huggingface.co/${model.hfRepo}/resolve/main"
        val client = buildClient()

        // Core model file
        if (!downloadIfMissing(client, "$base/${model.modelFile}", File(dir, model.modelFile),
                model.modelFile, 500_000, callback)) return@withContext false

        // Tokens
        downloadIfMissing(client, "$base/tokens.txt", File(dir, "tokens.txt"), "tokens.txt", 50, callback)

        // Lexicon
        if (model.needsLexicon) {
            for (lex in model.lexiconFiles) {
                downloadIfMissing(client, "$base/$lex", File(dir, lex), lex, 1_000, callback)
            }
        }

        // Voices (Kokoro/Kitten)
        if (model.engineType == EngineType.KOKORO || model.engineType == EngineType.KITTEN) {
            downloadIfMissing(client, "$base/voices.bin", File(dir, "voices.bin"), "voices.bin", 100_000, callback)
        }

        // Vocoder (Matcha)
        if (model.vocoderFile.isNotEmpty()) {
            val vocFile = File(dir, model.vocoderFile)
            if (!vocFile.exists() || vocFile.length() < 500_000) {
                if (!downloadFile(client, "$VOCODER_BASE/${model.vocoderFile}", vocFile, "vocoder", callback))
                    return@withContext false
            }
        }

        // FST rules
        for (fst in model.fstFiles) {
            downloadIfMissing(client, "$base/$fst", File(dir, fst), fst, 100, callback)
        }

        // espeak-ng-data (from app assets)
        if (model.needsEspeakData) {
            val espeakDest = File(dir, "espeak-ng-data")
            if (!espeakDest.exists() || !espeakDest.isDirectory || (espeakDest.list()?.size ?: 0) < 10) {
                withContext(Dispatchers.Main) { callback.onDownloadProgress("espeak-ng-data", 0) }
                try { copyAssetDir("espeak-ng-data", espeakDest) }
                catch (e: Throwable) {
                    withContext(Dispatchers.Main) { callback.onError("espeak-ng-data 复制失败") }
                    return@withContext false
                }
                withContext(Dispatchers.Main) { callback.onDownloadProgress("espeak-ng-data", 100) }
            }
        }

        // Jieba dict — download from universal source (MeloTTS repo)
        if (model.needsDict) {
            for (path in DICT_FILES) {
                val f = File(dir, path); f.parentFile?.mkdirs()
                if (f.exists() && f.length() > 100) continue
                if (!downloadFile(client, "$DICT_SOURCE/$path", f, path.substringAfterLast('/'), callback))
                    return@withContext false
            }
        }
        true
    }

    private suspend fun downloadIfMissing(
        client: OkHttpClient, url: String, file: File,
        name: String, minSize: Long, callback: Callback
    ): Boolean {
        if (file.exists() && file.length() >= minSize) {
            withContext(Dispatchers.Main) { callback.onDownloadProgress(name, 100) }
            return true
        }
        file.delete()
        return downloadFile(client, url, file, name, callback)
    }

    private fun buildClient() = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .followRedirects(true).followSslRedirects(true)
        .build()

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
            body.byteStream().use { i -> FileOutputStream(tmp).use { o ->
                val buf = ByteArray(8192)
                while (true) {
                    val n = i.read(buf); if (n == -1) break
                    o.write(buf, 0, n); dl += n
                    if (total > 0) withContext(Dispatchers.Main) { callback.onDownloadProgress(displayName, (dl * 100 / total).toInt()) }
                }
            }}
            tmp.renameTo(outFile)
            withContext(Dispatchers.Main) { callback.onDownloadProgress(displayName, 100) }
            true
        } catch (e: Throwable) {
            withContext(Dispatchers.Main) { callback.onError("下载失败: $displayName - ${e.message}") }
            false
        }
    }

    private fun copyAssetDir(assetPath: String, destDir: File) {
        destDir.mkdirs()
        val entries = context.assets.list(assetPath) ?: return
        for (entry in entries) {
            val src = "$assetPath/$entry"; val dest = File(destDir, entry)
            try { context.assets.open(src).use { i -> FileOutputStream(dest).use { o -> i.copyTo(o) } } }
            catch (_: java.io.FileNotFoundException) { copyAssetDir(src, dest) }
        }
    }

    // ==================== Speak ====================

    fun speak(text: String, sid: Int = 0, speed: Float = 1.0f): Boolean {
        if (!ready) return false
        val localTts = tts ?: return false
        val cleaned = text.trim()
        if (cleaned.isBlank()) return false
        synchronized(speakLock) {
            stopRequested = false
            try {
                val audio = localTts.generate(cleaned, sid = sid, speed = speed)
                if (audio.samples.isEmpty() || stopRequested) return false
                playAndWait(audio.samples, audio.sampleRate)
                return true
            } catch (e: Throwable) {
                Log.e(TAG, "Speak failed: ${e.message}", e)
                return false
            }
        }
    }

    fun stopPlayback() { stopRequested = true }

    private fun playAndWait(samples: FloatArray, sr: Int) {
        val bufSize = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        if (bufSize <= 0) return
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(sr)
                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(bufSize).build()
        try {
            track.play(); var off = 0
            while (off < samples.size && !stopRequested) {
                val wrote = track.write(samples, off, min(2048, samples.size - off), AudioTrack.WRITE_BLOCKING)
                if (wrote <= 0) break; off += wrote
            }
            if (!stopRequested) {
                val deadline = System.currentTimeMillis() + (off.toLong() * 1000 / sr) + 500
                while (track.playbackHeadPosition < off && System.currentTimeMillis() < deadline && !stopRequested) Thread.sleep(20)
            }
            track.stop()
        } catch (e: Throwable) { Log.e(TAG, "Playback: ${e.message}") }
        finally { try { track.release() } catch (_: Throwable) {} }
    }

    fun release() {
        ready = false; stopRequested = true
        synchronized(speakLock) { try { tts?.release() } catch (_: Throwable) {}; tts = null; currentModel = null }
    }

    fun deleteModel(model: Model) {
        if (currentModel == model) release()
        if (!model.isBuiltin) try { File(context.filesDir, model.dirName).deleteRecursively() } catch (_: Throwable) {}
    }

    fun modelSizeMB(model: Model): Int {
        if (model.isBuiltin) return model.approxSizeMB
        val dir = File(context.filesDir, model.dirName)
        if (!dir.exists()) return 0
        return (dir.walkTopDown().filter { it.isFile }.sumOf { it.length() } / (1024 * 1024)).toInt()
    }
}
