package com.example.myapplication1.asr

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.k2fsa.sherpa.onnx.*
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import kotlin.math.max

/**
 * Local Whisper ASR using sherpa-onnx with Silero VAD.
 * Supports multiple Whisper model sizes.
 */
class SherpaWhisperAsr(private val context: Context) {

    companion object {
        private const val TAG = "SherpaWhisperAsr"
        private const val SAMPLE_RATE = 16000
        private const val VAD_FILE = "silero_vad.onnx"
        private const val VAD_URL = "https://huggingface.co/csukuangfj/vad/resolve/main/silero_vad.onnx"
    }

    /**
     * Available Whisper model variants for sherpa-onnx.
     * All use int8 quantization for mobile efficiency.
     */
    enum class WhisperModel(
        val label: String,
        val desc: String,
        val dirName: String,
        val hfRepo: String,
        val encoderFile: String,
        val decoderFile: String,
        val tokensFile: String,
        val approxSizeMB: Int,
    ) {
        TINY_EN(
            label = "tiny.en",
            desc = "最小最快，仅英语，适合实时",
            dirName = "sherpa-whisper-tiny-en",
            hfRepo = "csukuangfj/sherpa-onnx-whisper-tiny.en",
            encoderFile = "tiny.en-encoder.int8.onnx",
            decoderFile = "tiny.en-decoder.int8.onnx",
            tokensFile = "tiny.en-tokens.txt",
            approxSizeMB = 40,
        ),
        BASE_EN(
            label = "base.en",
            desc = "较小，仅英语，精度更好",
            dirName = "sherpa-whisper-base-en",
            hfRepo = "csukuangfj/sherpa-onnx-whisper-base.en",
            encoderFile = "base.en-encoder.int8.onnx",
            decoderFile = "base.en-decoder.int8.onnx",
            tokensFile = "base.en-tokens.txt",
            approxSizeMB = 80,
        ),
        SMALL_EN(
            label = "small.en",
            desc = "中等，仅英语，精度高",
            dirName = "sherpa-whisper-small-en",
            hfRepo = "csukuangfj/sherpa-onnx-whisper-small.en",
            encoderFile = "small.en-encoder.int8.onnx",
            decoderFile = "small.en-decoder.int8.onnx",
            tokensFile = "small.en-tokens.txt",
            approxSizeMB = 250,
        ),
        MEDIUM_EN(
            label = "medium.en",
            desc = "较大，仅英语，精度最高",
            dirName = "sherpa-whisper-medium-en",
            hfRepo = "csukuangfj/sherpa-onnx-whisper-medium.en",
            encoderFile = "medium.en-encoder.int8.onnx",
            decoderFile = "medium.en-decoder.int8.onnx",
            tokensFile = "medium.en-tokens.txt",
            approxSizeMB = 500,
        ),
        TINY(
            label = "tiny (多语言)",
            desc = "最小，支持多语言",
            dirName = "sherpa-whisper-tiny",
            hfRepo = "csukuangfj/sherpa-onnx-whisper-tiny",
            encoderFile = "tiny-encoder.int8.onnx",
            decoderFile = "tiny-decoder.int8.onnx",
            tokensFile = "tiny-tokens.txt",
            approxSizeMB = 40,
        ),
        BASE(
            label = "base (多语言)",
            desc = "较小，支持多语言",
            dirName = "sherpa-whisper-base",
            hfRepo = "csukuangfj/sherpa-onnx-whisper-base",
            encoderFile = "base-encoder.int8.onnx",
            decoderFile = "base-decoder.int8.onnx",
            tokensFile = "base-tokens.txt",
            approxSizeMB = 80,
        ),
    }

    interface Callback {
        fun onListening()
        fun onSpeechDetected()
        fun onProcessing()
        fun onResult(text: String)
        fun onError(message: String)
        fun onDownloadProgress(file: String, percent: Int)
        fun onModelReady()
    }

    @Volatile private var recording = false
    private var audioRecord: AudioRecord? = null
    private var job: Job? = null
    private var recognizer: OfflineRecognizer? = null
    private var vad: Vad? = null
    private var aec: android.media.audiofx.AcousticEchoCanceler? = null
    private var modelReady = false
    private var currentModel: WhisperModel? = null

    fun isModelDownloaded(model: WhisperModel): Boolean {
        val dir = File(context.filesDir, model.dirName)
        val hfBase = "https://huggingface.co/${model.hfRepo}/resolve/main"
        return listOf(model.encoderFile, model.decoderFile, model.tokensFile)
            .all { File(dir, it).let { f -> f.exists() && f.length() > 100 } }
            && File(dir, VAD_FILE).let { it.exists() && it.length() > 100 }
    }

    /** Get list of all downloaded models */
    fun downloadedModels(): List<WhisperModel> = WhisperModel.entries.filter { isModelDownloaded(it) }

    /** Delete a downloaded model to free storage */
    fun deleteModel(model: WhisperModel) {
        // Release if this is the active model
        if (currentModel == model) release()
        val dir = File(context.filesDir, model.dirName)
        try { dir.deleteRecursively() } catch (_: Throwable) {}
        Log.i(TAG, "Deleted model: ${model.label}")
    }

    /** Get storage size of a downloaded model in MB */
    fun modelSizeMB(model: WhisperModel): Int {
        val dir = File(context.filesDir, model.dirName)
        if (!dir.exists()) return 0
        return (dir.walkTopDown().filter { it.isFile }.sumOf { it.length() } / (1024 * 1024)).toInt()
    }

    /** Get Vosk model size in MB */
    fun voskModelSizeMB(): Int {
        val dir = File(context.filesDir, "model-en")
        if (!dir.exists()) return 0
        return (dir.walkTopDown().filter { it.isFile }.sumOf { it.length() } / (1024 * 1024)).toInt()
    }

    /** Delete Vosk model */
    fun deleteVoskModel() {
        val dir = File(context.filesDir, "model-en")
        try { dir.deleteRecursively() } catch (_: Throwable) {}
        Log.i(TAG, "Deleted Vosk model")
    }

    /** Check if Vosk model exists */
    fun isVoskModelPresent(): Boolean = File(context.filesDir, "model-en").exists()

    suspend fun downloadModel(model: WhisperModel, callback: Callback): Boolean = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, model.dirName).apply { mkdirs() }
        val hfBase = "https://huggingface.co/${model.hfRepo}/resolve/main"
        val files = listOf(
            model.encoderFile to "$hfBase/${model.encoderFile}",
            model.decoderFile to "$hfBase/${model.decoderFile}",
            model.tokensFile to "$hfBase/${model.tokensFile}",
            VAD_FILE to VAD_URL,
        )

        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()

        for ((name, url) in files) {
            val outFile = File(dir, name)
            if (outFile.exists() && outFile.length() > 100) {
                withContext(Dispatchers.Main) { callback.onDownloadProgress(name, 100) }
                continue
            }

            try {
                withContext(Dispatchers.Main) { callback.onDownloadProgress(name, 0) }
                val request = Request.Builder().url(url).build()
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    withContext(Dispatchers.Main) { callback.onError("下载失败: $name (${response.code})") }
                    return@withContext false
                }

                val body = response.body ?: run {
                    withContext(Dispatchers.Main) { callback.onError("下载失败: $name (空响应)") }
                    return@withContext false
                }

                val totalBytes = body.contentLength()
                val tmpFile = File(dir, "$name.tmp")
                var downloaded = 0L

                body.byteStream().use { input ->
                    FileOutputStream(tmpFile).use { output ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            if (totalBytes > 0) {
                                val pct = (downloaded * 100 / totalBytes).toInt()
                                withContext(Dispatchers.Main) { callback.onDownloadProgress(name, pct) }
                            }
                        }
                    }
                }
                tmpFile.renameTo(outFile)
                withContext(Dispatchers.Main) { callback.onDownloadProgress(name, 100) }
            } catch (e: Throwable) {
                withContext(Dispatchers.Main) { callback.onError("下载失败: $name - ${e.message}") }
                return@withContext false
            }
        }
        true
    }

    fun initModel(model: WhisperModel): Boolean {
        // If switching models, release previous
        if (currentModel != model) {
            release()
        }
        if (modelReady && currentModel == model) return true

        for (provider in com.example.myapplication1.AccelerationConfig.providerChain(context)) {
            if (tryInitModel(model, provider)) {
                Log.i(TAG, "Whisper [${model.label}] + VAD 初始化成功 (provider=$provider)")
                return true
            }
            if (provider != com.example.myapplication1.AccelerationConfig.CPU) {
                Log.w(TAG, "Whisper init with provider=$provider failed, falling back to CPU")
            }
        }
        return false
    }

    private fun tryInitModel(model: WhisperModel, provider: String): Boolean {
        val dir = File(context.filesDir, model.dirName)
        return try {
            val whisperConfig = OfflineWhisperModelConfig(
                encoder = File(dir, model.encoderFile).absolutePath,
                decoder = File(dir, model.decoderFile).absolutePath,
                language = "en",
                task = "transcribe"
            )
            val mCfg = OfflineModelConfig()
            mCfg.whisper = whisperConfig
            mCfg.tokens = File(dir, model.tokensFile).absolutePath
            mCfg.numThreads = 4
            mCfg.provider = provider

            val rCfg = OfflineRecognizerConfig()
            rCfg.modelConfig = mCfg
            rCfg.featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80)
            recognizer = OfflineRecognizer(null, rCfg)

            // Silero VAD
            val silero = SileroVadModelConfig(
                model = File(dir, VAD_FILE).absolutePath,
                threshold = 0.5f,
                minSilenceDuration = 0.3f,
                minSpeechDuration = 0.25f,
                windowSize = 512,
                maxSpeechDuration = 30f
            )
            val vCfg = VadModelConfig()
            vCfg.sileroVadModelConfig = silero
            vCfg.sampleRate = SAMPLE_RATE
            vCfg.numThreads = 1
            vCfg.provider = provider
            vad = Vad(null, vCfg)

            modelReady = true
            currentModel = model
            true
        } catch (e: Throwable) {
            Log.e(TAG, "模型初始化失败 (provider=$provider): ${e.message}", e)
            try { recognizer?.release() } catch (_: Throwable) {}
            try { vad?.release() } catch (_: Throwable) {}
            recognizer = null
            vad = null
            false
        }
    }

    @SuppressLint("MissingPermission")
    fun start(scope: CoroutineScope, callback: Callback) {
        if (recording) return
        if (!modelReady) {
            callback.onError("模型未就绪")
            return
        }

        val bufSize = max(
            AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT),
            4096
        )

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize
            )
            try {
                if (android.media.audiofx.AcousticEchoCanceler.isAvailable()) {
                    aec = android.media.audiofx.AcousticEchoCanceler.create(audioRecord!!.audioSessionId)
                    aec?.enabled = true
                }
            } catch (_: Throwable) {}
            audioRecord?.startRecording()
        } catch (e: Throwable) {
            callback.onError("录音启动失败: ${e.message}")
            return
        }

        recording = true
        callback.onListening()

        job = scope.launch(Dispatchers.IO) {
            val buffer = ShortArray(512)
            val localVad = vad ?: return@launch
            val localRecognizer = recognizer ?: return@launch

            // Channel for VAD segments → decode.  Decode MUST be sequential because
            // sherpa-onnx OfflineRecognizer is NOT thread-safe.  But using a channel
            // decouples audio reading (fast) from decode (slow) so the mic loop never stalls.
            val segmentCh = kotlinx.coroutines.channels.Channel<FloatArray>(kotlinx.coroutines.channels.Channel.UNLIMITED)

            // Decoder coroutine: reads segments from channel, decodes one at a time
            val decoderJob = launch(Dispatchers.IO) {
                for (samples in segmentCh) {
                    try {
                        Log.d(TAG, "开始decode: ${samples.size} samples")
                        val t0 = System.currentTimeMillis()
                        val stream = localRecognizer.createStream()
                        stream.acceptWaveform(samples, SAMPLE_RATE)
                        localRecognizer.decode(stream)
                        val result = localRecognizer.getResult(stream)
                        stream.release()
                        val ms = System.currentTimeMillis() - t0

                        val text = result.text.trim()
                        Log.d(TAG, "decode完成 ${ms}ms: [${text.take(60)}]")
                        if (text.isNotBlank()) {
                            withContext(Dispatchers.Main) { callback.onResult(text) }
                        }
                    } catch (e: Throwable) {
                        Log.e(TAG, "Decode error: ${e.message}", e)
                    }
                }
                Log.d(TAG, "decoder coroutine ended")
            }

            // Audio loop: reads mic → feeds VAD → sends segments to channel
            try {
                while (isActive && recording) {
                    val read = try {
                        audioRecord?.read(buffer, 0, buffer.size) ?: break
                    } catch (_: Throwable) { break }
                    if (read <= 0) continue

                    val floatSamples = FloatArray(read) { buffer[it] / 32768.0f }
                    localVad.acceptWaveform(floatSamples)

                    if (localVad.isSpeechDetected()) {
                        withContext(Dispatchers.Main) { callback.onSpeechDetected() }
                    }

                    while (!localVad.empty()) {
                        val segment = localVad.front()
                        localVad.pop()
                        val copied = segment.samples.copyOf()
                        Log.d(TAG, "VAD segment: ${copied.size} samples (${copied.size * 1000 / SAMPLE_RATE}ms)")
                        segmentCh.trySend(copied)
                    }
                }
            } finally {
                // ALWAYS flush — even if the loop exited due to audioRecord error.
                // This ensures no speech is lost when recording stops.
                Log.d(TAG, "audio loop ended, flushing VAD")
                localVad.flush()
                var flushed = 0
                while (!localVad.empty()) {
                    val segment = localVad.front()
                    localVad.pop()
                    segmentCh.trySend(segment.samples.copyOf())
                    flushed++
                }
                Log.d(TAG, "VAD flushed $flushed segments")
                segmentCh.close()
                decoderJob.join()
                localVad.reset()
            }
        }
    }

    /** Graceful stop: let VAD flush remaining audio, then clean up. */
    fun stopGracefully(): Job? {
        recording = false
        try { audioRecord?.stop() } catch (_: Throwable) {}
        val j = job
        j?.invokeOnCompletion { releaseAudioResources() }
        return j
    }

    /** Hard stop – cancels immediately. */
    fun stop() {
        recording = false
        job?.cancel()
        job = null
        releaseAudioResources()
    }

    private fun releaseAudioResources() {
        job = null
        try { aec?.release() } catch (_: Throwable) {}; aec = null
        try { audioRecord?.stop() } catch (_: Throwable) {}
        try { audioRecord?.release() } catch (_: Throwable) {}
        audioRecord = null
        vad?.reset()
    }

    fun release() {
        stop()
        try { recognizer?.release() } catch (_: Throwable) {}
        try { vad?.release() } catch (_: Throwable) {}
        recognizer = null
        vad = null
        modelReady = false
        currentModel = null
    }
}
