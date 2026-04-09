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
 * Kokoro TTS engine using sherpa-onnx OfflineTts.
 *
 * Kokoro multi-lang v1.1 (103 voices, Chinese + English).
 *
 * Threading contract:
 *   - speak() is serialized via speakLock (native OfflineTts is NOT thread-safe)
 *   - stopPlayback() only sets a volatile flag (no AudioTrack access from outside)
 *   - AudioTrack is created/used/released entirely within speak() (no shared state)
 */
class KokoroTts(private val context: Context) {

    companion object {
        private const val TAG = "KokoroTts"
        private const val DIR_NAME = "sherpa-onnx-kokoro-multi-v11"
        private val OLD_DIRS = listOf("sherpa-onnx-kokoro", "sherpa-onnx-kokoro-en-v019")

        private const val HF_REPO = "csukuangfj/kokoro-multi-lang-v1_1"
        private const val MODEL_FILE  = "model.onnx"
        private const val VOICES_FILE = "voices.bin"
        private const val TOKENS_FILE = "tokens.txt"

        private val LEXICON_FILES = listOf("lexicon-us-en.txt", "lexicon-zh.txt")
        private val FST_FILES    = listOf("date-zh.fst", "number-zh.fst", "phone-zh.fst")
        private val DICT_FILES   = listOf(
            "dict/jieba.dict.utf8", "dict/hmm_model.utf8", "dict/idf.utf8",
            "dict/user.dict.utf8", "dict/stop_words.utf8",
            "dict/pos_dict/char_state_tab.utf8", "dict/pos_dict/prob_emit.utf8",
            "dict/pos_dict/prob_start.utf8", "dict/pos_dict/prob_trans.utf8",
        )

        val VOICES = listOf(
            0  to "Default · 美式女声 (af)",
            1  to "Bella · 美式女声 · 温柔",
            2  to "Nicole · 美式女声 · 活泼",
            3  to "Sarah · 美式女声 · 沉稳",
            4  to "Sky · 美式女声 · 清新",
            5  to "Adam · 美式男声 · 沉稳",
            6  to "Michael · 美式男声 · 温暖",
            7  to "Emma · 英式女声 · 优雅",
            8  to "Isabella · 英式女声 · 温柔",
            9  to "George · 英式男声 · 沉稳",
            10 to "Lewis · 英式男声 · 清朗",
            50 to "小贝 · 中文女声 (zf_xiaobei)",
            51 to "小妮 · 中文女声 (zf_xiaoni)",
            52 to "小萱 · 中文女声 (zf_xiaoxuan)",
            53 to "小颜 · 中文女声 (zf_xiaoyan)",
            54 to "小怡 · 中文女声 (zf_xiaoyi)",
            55 to "云峰 · 中文男声 (zm_yunfeng)",
            56 to "云皓 · 中文男声 (zm_yunhao)",
            57 to "云建 · 中文男声 (zm_yunjian)",
            58 to "云夏 · 中文男声 (zm_yunxia)",
            59 to "云扬 · 中文男声 (zm_yunyang)",
        )

        fun normalizeForTts(text: String): String {
            var s = text
            s = s.replace(Regex("[。！？]{2,}"), "。")
            s = s.replace(Regex("[，、]{2,}"), "，")
            s = s.replace(Regex("[\\x00-\\x08\\x0b\\x0c\\x0e-\\x1f]"), "")
            s = s.replace(Regex("\\s+"), " ")
            return s.trim()
        }
    }

    interface Callback {
        fun onDownloadProgress(file: String, percent: Int)
        fun onError(message: String)
    }

    @Volatile private var ready = false
    private var tts: OfflineTts? = null
    @Volatile private var stopRequested = false
    private val speakLock = Any()

    fun isReady() = ready

    fun isModelDownloaded(): Boolean {
        val dir = File(context.filesDir, DIR_NAME)
        val coreOk = listOf(MODEL_FILE, VOICES_FILE, TOKENS_FILE)
            .all { File(dir, it).let { f -> f.exists() && f.length() > 100 } }
        val lexiconOk = LEXICON_FILES.all { File(dir, it).let { f -> f.exists() && f.length() > 100 } }
        val dictOk = File(dir, "dict").let { d -> d.exists() && d.isDirectory }
        val espeakOk = File(dir, "espeak-ng-data").let { d ->
            d.exists() && d.isDirectory && (d.list()?.size ?: 0) >= 10
        }
        return coreOk && lexiconOk && dictOk && espeakOk
    }

    fun modelSizeMB(): Int {
        val dir = File(context.filesDir, DIR_NAME)
        if (!dir.exists()) return 0
        return (dir.walkTopDown().filter { it.isFile }.sumOf { it.length() } / (1024 * 1024)).toInt()
    }

    fun deleteModel() {
        release()
        try { File(context.filesDir, DIR_NAME).deleteRecursively() } catch (_: Throwable) {}
        Log.i(TAG, "Deleted Kokoro model")
    }

    // ==================== Download ====================

    suspend fun downloadModel(callback: Callback): Boolean = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, DIR_NAME).apply { mkdirs() }
        val base = "https://huggingface.co/$HF_REPO/resolve/main"

        OLD_DIRS.forEach { try { File(context.filesDir, it).deleteRecursively() } catch (_: Throwable) {} }

        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .followRedirects(true).followSslRedirects(true)
            .build()

        val allFiles = mutableListOf<Triple<String, String, Long>>()
        allFiles.add(Triple(MODEL_FILE, "$base/$MODEL_FILE", 100_000_000L))
        allFiles.add(Triple(VOICES_FILE, "$base/$VOICES_FILE", 1_000_000L))
        allFiles.add(Triple(TOKENS_FILE, "$base/$TOKENS_FILE", 100L))
        LEXICON_FILES.forEach { allFiles.add(Triple(it, "$base/$it", 10_000L)) }
        FST_FILES.forEach     { allFiles.add(Triple(it, "$base/$it", 1_000L)) }
        DICT_FILES.forEach    { allFiles.add(Triple(it, "$base/$it", 100L)) }

        for ((name, url, minSize) in allFiles) {
            val outFile = File(dir, name)
            if (outFile.exists() && outFile.length() >= minSize) {
                withContext(Dispatchers.Main) { callback.onDownloadProgress(name.substringAfterLast('/'), 100) }
                continue
            }
            outFile.parentFile?.mkdirs(); outFile.delete()
            if (!downloadFile(client, url, outFile, name.substringAfterLast('/'), callback)) return@withContext false
        }

        val espeakDest = File(dir, "espeak-ng-data")
        if (!espeakDest.exists() || !espeakDest.isDirectory || (espeakDest.list()?.size ?: 0) < 10) {
            withContext(Dispatchers.Main) { callback.onDownloadProgress("espeak-ng-data", 0) }
            try {
                copyAssetDir("espeak-ng-data", espeakDest)
                withContext(Dispatchers.Main) { callback.onDownloadProgress("espeak-ng-data", 100) }
            } catch (e: Throwable) {
                Log.e(TAG, "Copy espeak-ng-data failed: ${e.message}", e)
                withContext(Dispatchers.Main) { callback.onError("复制 espeak-ng-data 失败: ${e.message}") }
                return@withContext false
            }
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
            val body = response.body ?: run {
                withContext(Dispatchers.Main) { callback.onError("下载失败: $displayName (空响应)") }
                return@withContext false
            }
            val totalBytes = body.contentLength()
            val tmpFile = File(outFile.parent, "${outFile.name}.tmp")
            var downloaded = 0L
            body.byteStream().use { input ->
                FileOutputStream(tmpFile).use { output ->
                    val buf = ByteArray(8192)
                    while (true) {
                        val read = input.read(buf)
                        if (read == -1) break
                        output.write(buf, 0, read)
                        downloaded += read
                        if (totalBytes > 0) {
                            val pct = (downloaded * 100 / totalBytes).toInt()
                            withContext(Dispatchers.Main) { callback.onDownloadProgress(displayName, pct) }
                        }
                    }
                }
            }
            tmpFile.renameTo(outFile)
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
            try {
                context.assets.open(src).use { i -> FileOutputStream(dest).use { o -> i.copyTo(o) } }
            } catch (_: java.io.FileNotFoundException) { copyAssetDir(src, dest) }
        }
    }

    // ==================== Init ====================

    fun initModel(): Boolean {
        if (ready) return true
        val dir = File(context.filesDir, DIR_NAME)
        return try {
            val espeakDir = File(dir, "espeak-ng-data")
            val dictDir   = File(dir, "dict")

            val lexicon = LEXICON_FILES
                .map { File(dir, it).absolutePath }
                .filter { File(it).exists() }
                .joinToString(",")

            // v1.1 (model version '2') REQUIRES both lexicon AND dictDir — native aborts without them.
            // Official example is for v1.0 which doesn't need dictDir, but v1.1 does.
            // NO ruleFsts — those are for Matcha, cause unnatural prosody in Kokoro.
            val kokoroConfig = OfflineTtsKokoroModelConfig(
                model   = File(dir, MODEL_FILE).absolutePath,
                voices  = File(dir, VOICES_FILE).absolutePath,
                tokens  = File(dir, TOKENS_FILE).absolutePath,
                dataDir = if (espeakDir.exists()) espeakDir.absolutePath else "",
                lexicon = lexicon,
                dictDir = if (dictDir.exists()) dictDir.absolutePath else "",
            )
            val ttsConfig = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    kokoro = kokoroConfig, numThreads = 2,
                    debug = false, provider = "cpu",
                ),
            )
            tts = OfflineTts(null, ttsConfig)
            ready = true
            Log.i(TAG, "Kokoro initialized, sr=${tts!!.sampleRate()}, speakers=${tts!!.numSpeakers()}")
            true
        } catch (e: Throwable) {
            Log.e(TAG, "Init failed: ${e.message}", e)
            ready = false
            false
        }
    }

    fun warmUp() {
        if (!ready || tts == null) return
        try { tts!!.generate("hello", sid = 0, speed = 1.0f); Log.d(TAG, "Warm-up done") }
        catch (_: Throwable) {}
    }

    // ==================== Speak ====================

    /**
     * Generate audio and play it. Blocks until playback finishes.
     * All AudioTrack operations happen on the calling thread — no cross-thread access.
     * Serialized by speakLock — safe to call from multiple coroutines.
     */
    fun speak(text: String, voiceSid: Int = 0, speed: Float = 1.0f): Boolean {
        if (!ready) return false
        val localTts = tts ?: return false

        val normalized = normalizeForTts(text)
        if (normalized.isBlank()) return false

        synchronized(speakLock) {
            stopRequested = false
            try {
                val audio = localTts.generate(normalized, sid = voiceSid, speed = speed)
                if (audio.samples.isEmpty() || stopRequested) return false
                playAndWait(audio.samples, audio.sampleRate)
                return true
            } catch (e: Throwable) {
                Log.e(TAG, "Speak failed: ${e.message}", e)
                return false
            }
        }
    }

    /** Only sets a flag — no AudioTrack touch. Safe from any thread. */
    fun stopPlayback() {
        stopRequested = true
    }

    /**
     * Create a temporary AudioTrack, write all samples, wait for playback, release.
     * Entirely self-contained — no shared AudioTrack state.
     */
    private fun playAndWait(samples: FloatArray, sr: Int) {
        val bufSize = AudioTrack.getMinBufferSize(
            sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT
        )
        if (bufSize <= 0) { Log.e(TAG, "Invalid buffer size: $bufSize"); return }

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sr)
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(bufSize)
            .build()

        try {
            track.play()

            var off = 0
            val chunk = 2048
            while (off < samples.size && !stopRequested) {
                val len = min(chunk, samples.size - off)
                val wrote = track.write(samples, off, len, AudioTrack.WRITE_BLOCKING)
                if (wrote <= 0) break
                off += wrote
            }

            // Wait for hardware to finish playing buffered audio
            if (!stopRequested) {
                val totalFrames = off
                val maxWaitMs = (totalFrames.toLong() * 1000 / sr) + 500
                val deadline = System.currentTimeMillis() + maxWaitMs
                while (track.playbackHeadPosition < totalFrames
                    && System.currentTimeMillis() < deadline
                    && !stopRequested
                ) {
                    Thread.sleep(20)
                }
            }
            track.stop()
        } catch (e: Throwable) {
            Log.e(TAG, "Playback error: ${e.message}")
        } finally {
            try { track.release() } catch (_: Throwable) {}
        }
    }

    fun release() {
        ready = false
        stopRequested = true
        synchronized(speakLock) {
            // Lock ensures no speak() is in progress before releasing native resources
            try { tts?.release() } catch (_: Throwable) {}
            tts = null
        }
    }
}
