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
 * Streaming ASR using sherpa-onnx OnlineRecognizer (Zipformer Transducer).
 *
 * Key differences from SherpaWhisperAsr (offline/batch):
 * - No VAD pre-segmentation needed — audio is fed continuously
 * - Partial results emitted in real-time via onPartial()
 * - Built-in endpoint detection fires onResult() after trailing silence
 */
class SherpaStreamingAsr(private val context: Context) {

    companion object {
        private const val TAG = "SherpaStreamingAsr"
        private const val SAMPLE_RATE = 16000
    }

    enum class ModelType { TRANSDUCER, PARAFORMER, ZIPFORMER2_CTC, NEMO_CTC }

    enum class StreamingModel(
        val label: String,
        val desc: String,
        val dirName: String,
        val hfRepo: String,
        val modelType: ModelType,
        val encoderFile: String,
        val decoderFile: String = "",
        val joinerFile: String = "",
        val modelFile: String = "",  // for CTC single-file models
        val tokensFile: String = "tokens.txt",
        val approxSizeMB: Int,
    ) {
        // ---- 中英双语 ----
        ZIPFORMER_BILINGUAL(
            "Zipformer 中英双语", "中英双语 · Transducer · 推荐 · ~70MB",
            "sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20",
            "csukuangfj/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20",
            ModelType.TRANSDUCER,
            encoderFile = "encoder-epoch-99-avg-1.int8.onnx",
            decoderFile = "decoder-epoch-99-avg-1.onnx",
            joinerFile = "joiner-epoch-99-avg-1.int8.onnx",
            approxSizeMB = 70,
        ),
        PARAFORMER_BILINGUAL(
            "Paraformer 中英双语", "中英双语 · Paraformer · ~220MB",
            "sherpa-onnx-streaming-paraformer-bilingual-zh-en",
            "csukuangfj/sherpa-onnx-streaming-paraformer-bilingual-zh-en",
            ModelType.PARAFORMER,
            encoderFile = "encoder.int8.onnx",
            decoderFile = "decoder.int8.onnx",
            approxSizeMB = 220,
        ),

        // ---- 中文 ----
        ZIPFORMER_ZH_14M(
            "Zipformer 中文 14M", "中文 · 超轻量 · ~14MB",
            "sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23",
            "csukuangfj/sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23",
            ModelType.TRANSDUCER,
            encoderFile = "encoder-epoch-99-avg-1.int8.onnx",
            decoderFile = "decoder-epoch-99-avg-1.onnx",
            joinerFile = "joiner-epoch-99-avg-1.int8.onnx",
            approxSizeMB = 14,
        ),
        ZIPFORMER_CTC_ZH(
            "Zipformer CTC 中文", "中文 · CTC · ~30MB",
            "sherpa-onnx-streaming-zipformer-small-ctc-zh-int8-2025-04-01",
            "csukuangfj/sherpa-onnx-streaming-zipformer-small-ctc-zh-int8-2025-04-01",
            ModelType.ZIPFORMER2_CTC,
            encoderFile = "", modelFile = "model.int8.onnx",
            approxSizeMB = 30,
        ),

        // ---- 英文 ----
        ZIPFORMER_EN(
            "Zipformer 英语", "纯英语 · Transducer · ~50MB",
            "sherpa-onnx-streaming-zipformer-en-2023-06-26",
            "csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-06-26",
            ModelType.TRANSDUCER,
            encoderFile = "encoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx",
            decoderFile = "decoder-epoch-99-avg-1-chunk-16-left-128.onnx",
            joinerFile = "joiner-epoch-99-avg-1-chunk-16-left-128.int8.onnx",
            approxSizeMB = 50,
        ),
        ZIPFORMER_EN_20M(
            "Zipformer 英语 20M", "英语 · 超轻量 · ~20MB",
            "sherpa-onnx-streaming-zipformer-en-20M-2023-02-17",
            "csukuangfj/sherpa-onnx-streaming-zipformer-en-20M-2023-02-17",
            ModelType.TRANSDUCER,
            encoderFile = "encoder-epoch-99-avg-1.int8.onnx",
            decoderFile = "decoder-epoch-99-avg-1.onnx",
            joinerFile = "joiner-epoch-99-avg-1.int8.onnx",
            approxSizeMB = 20,
        ),
        NEMO_CTC_EN(
            "NeMo Conformer EN", "英语 · NeMo CTC · ~130MB",
            "sherpa-onnx-nemo-streaming-fast-conformer-ctc-en-80ms",
            "csukuangfj/sherpa-onnx-nemo-streaming-fast-conformer-ctc-en-80ms",
            ModelType.NEMO_CTC,
            encoderFile = "", modelFile = "model.onnx",
            approxSizeMB = 130,
        ),

        // ---- 其他语言 ----
        ZIPFORMER_FR(
            "Zipformer 法语", "法语 · ~70MB",
            "sherpa-onnx-streaming-zipformer-fr-2023-04-14",
            "csukuangfj/sherpa-onnx-streaming-zipformer-fr-2023-04-14",
            ModelType.TRANSDUCER,
            encoderFile = "encoder-epoch-29-avg-9-with-averaged-model.int8.onnx",
            decoderFile = "decoder-epoch-29-avg-9-with-averaged-model.onnx",
            joinerFile = "joiner-epoch-29-avg-9-with-averaged-model.int8.onnx",
            approxSizeMB = 70,
        ),
        ZIPFORMER_KO(
            "Zipformer 韩语", "韩语 · ~70MB",
            "sherpa-onnx-streaming-zipformer-korean-2024-06-16",
            "csukuangfj/sherpa-onnx-streaming-zipformer-korean-2024-06-16",
            ModelType.TRANSDUCER,
            encoderFile = "encoder-epoch-99-avg-1.int8.onnx",
            decoderFile = "decoder-epoch-99-avg-1.onnx",
            joinerFile = "joiner-epoch-99-avg-1.int8.onnx",
            approxSizeMB = 70,
        ),
    }

    /** Files needed for download/check. */
    fun modelFiles(model: StreamingModel): List<String> = when (model.modelType) {
        ModelType.TRANSDUCER -> listOf(model.encoderFile, model.decoderFile, model.joinerFile, model.tokensFile)
        ModelType.PARAFORMER -> listOf(model.encoderFile, model.decoderFile, model.tokensFile)
        ModelType.ZIPFORMER2_CTC, ModelType.NEMO_CTC -> listOf(model.modelFile, model.tokensFile)
    }

    interface Callback {
        fun onPartial(text: String)
        fun onResult(text: String)
        fun onStateChanged(ready: Boolean)
        fun onDownloadProgress(file: String, percent: Int)
        fun onError(message: String)
        /** Called when language is detected from first ~3s of audio. */
        fun onLanguageDetected(lang: String) {}
    }

    // ---- Iter-3: Speech denoiser ----
    var speechEnhancer: SpeechEnhancer? = null
    @Volatile var denoiserEnabled = false

    // ---- Iter-4: Language detection ----
    var languageDetector: LanguageDetector? = null
    @Volatile var languageDetectionEnabled = false

    @Volatile private var recording = false
    private var recognizer: OnlineRecognizer? = null
    private var stream: OnlineStream? = null
    private var audioRecord: AudioRecord? = null
    private var aec: android.media.audiofx.AcousticEchoCanceler? = null
    private var asrJob: Job? = null
    private var modelReady = false
    private var currentModel: StreamingModel? = null

    fun isModelDownloaded(model: StreamingModel): Boolean {
        val dir = File(context.filesDir, model.dirName)
        return modelFiles(model).all { File(dir, it).let { f -> f.exists() && f.length() > 100 } }
    }

    fun downloadedModels(): List<StreamingModel> = StreamingModel.entries.filter { isModelDownloaded(it) }

    fun deleteModel(model: StreamingModel) {
        if (currentModel == model) release()
        try { File(context.filesDir, model.dirName).deleteRecursively() } catch (_: Throwable) {}
        Log.i(TAG, "Deleted model: ${model.label}")
    }

    fun modelSizeMB(model: StreamingModel): Int {
        val dir = File(context.filesDir, model.dirName)
        if (!dir.exists()) return 0
        return (dir.walkTopDown().filter { it.isFile }.sumOf { it.length() } / (1024 * 1024)).toInt()
    }

    suspend fun downloadModel(model: StreamingModel, callback: Callback): Boolean = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, model.dirName).apply { mkdirs() }
        val hfBase = "https://huggingface.co/${model.hfRepo}/resolve/main"
        val files = modelFiles(model).map { it to "$hfBase/$it" }

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

    fun initModel(model: StreamingModel): Boolean {
        if (currentModel == model && modelReady) return true
        if (currentModel != model) release()

        for (provider in com.example.myapplication1.AccelerationConfig.providerChain(context)) {
            if (tryInitModel(model, provider)) {
                Log.i(TAG, "Streaming ASR [${model.label}] initialized (provider=$provider)")
                return true
            }
            if (provider != com.example.myapplication1.AccelerationConfig.CPU) {
                Log.w(TAG, "Streaming ASR init with provider=$provider failed, falling back to CPU")
            }
        }
        modelReady = false
        return false
    }

    private fun tryInitModel(model: StreamingModel, provider: String): Boolean {
        val dir = File(context.filesDir, model.dirName)
        return try {
            fun p(f: String) = File(dir, f).absolutePath

            val modelConfig = when (model.modelType) {
                ModelType.TRANSDUCER -> OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = p(model.encoderFile), decoder = p(model.decoderFile), joiner = p(model.joinerFile),
                    ),
                    tokens = p(model.tokensFile), numThreads = 2, debug = false, provider = provider,
                )
                ModelType.PARAFORMER -> OnlineModelConfig(
                    paraformer = OnlineParaformerModelConfig(
                        encoder = p(model.encoderFile), decoder = p(model.decoderFile),
                    ),
                    tokens = p(model.tokensFile), numThreads = 2, debug = false, provider = provider,
                )
                ModelType.ZIPFORMER2_CTC -> OnlineModelConfig(
                    zipformer2Ctc = OnlineZipformer2CtcModelConfig(model = p(model.modelFile)),
                    tokens = p(model.tokensFile), numThreads = 2, debug = false, provider = provider,
                )
                ModelType.NEMO_CTC -> OnlineModelConfig(
                    neMoCtc = OnlineNeMoCtcModelConfig(model = p(model.modelFile)),
                    tokens = p(model.tokensFile), numThreads = 2, debug = false, provider = provider,
                )
            }

            val config = OnlineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                modelConfig = modelConfig,
                endpointConfig = EndpointConfig(
                    rule1 = EndpointRule(mustContainNonSilence = false, minTrailingSilence = 2.4f, minUtteranceLength = 0.0f),
                    rule2 = EndpointRule(mustContainNonSilence = true,  minTrailingSilence = 1.2f, minUtteranceLength = 0.0f),
                    rule3 = EndpointRule(mustContainNonSilence = false, minTrailingSilence = 0.0f, minUtteranceLength = 20.0f),
                ),
                enableEndpoint = true,
                decodingMethod = "greedy_search",
            )
            // null AssetManager = file mode (absolute paths)
            recognizer = OnlineRecognizer(null, config)
            stream = recognizer!!.createStream("")
            modelReady = true
            currentModel = model
            true
        } catch (e: Throwable) {
            Log.e(TAG, "Init with provider=$provider failed: ${e.message}", e)
            try { stream?.release() } catch (_: Throwable) {}
            try { recognizer?.release() } catch (_: Throwable) {}
            stream = null
            recognizer = null
            false
        }
    }

    fun isReady() = modelReady

    @SuppressLint("MissingPermission")
    fun start(scope: CoroutineScope, callback: Callback) {
        if (recording) return
        if (!modelReady || recognizer == null || stream == null) {
            callback.onError("模型未就绪")
            return
        }

        val bufSize = max(
            AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT),
            3200  // 100ms @ 16kHz × 2 bytes
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

        asrJob = scope.launch(Dispatchers.IO) {
            val buf = ShortArray(1600)  // 100ms @ 16kHz
            val localRecognizer = recognizer ?: return@launch
            val localStream = stream ?: return@launch

            // Iter-4: language detection state (accumulate first ~3s after each endpoint)
            val lidBuffer = mutableListOf<FloatArray>()
            var lidSampleCount = 0
            var lidDetected = false

            try {
                while (isActive && recording) {
                    val n = try { audioRecord?.read(buf, 0, buf.size) ?: break } catch (_: Throwable) { break }
                    if (n <= 0) continue

                    val floats = FloatArray(n) { buf[it] / 32768.0f }

                    // Iter-3: denoise before ASR
                    val samples = if (denoiserEnabled && speechEnhancer?.isReady() == true) {
                        speechEnhancer!!.denoise(floats, SAMPLE_RATE)
                    } else floats

                    localStream.acceptWaveform(samples, SAMPLE_RATE)

                    // Iter-4: accumulate audio for language detection
                    if (languageDetectionEnabled && languageDetector?.isReady() == true && !lidDetected) {
                        lidBuffer.add(samples.copyOf())
                        lidSampleCount += samples.size
                        if (lidSampleCount >= LanguageDetector.DETECTION_SAMPLES) {
                            lidDetected = true
                            val allSamples = FloatArray(lidSampleCount)
                            var off = 0
                            for (chunk in lidBuffer) { chunk.copyInto(allSamples, off); off += chunk.size }
                            lidBuffer.clear()
                            val lang = languageDetector!!.detect(allSamples, SAMPLE_RATE)
                            withContext(Dispatchers.Main) { callback.onLanguageDetected(lang) }
                        }
                    }

                    // Decode all ready frames
                    while (localRecognizer.isReady(localStream)) {
                        localRecognizer.decode(localStream)
                    }

                    // Emit partial result
                    val partial = normalizeCase(localRecognizer.getResult(localStream).text)
                    if (partial.isNotBlank()) {
                        withContext(Dispatchers.Main) { callback.onPartial(partial) }
                    }

                    // Check for endpoint
                    if (localRecognizer.isEndpoint(localStream)) {
                        val finalText = normalizeCase(localRecognizer.getResult(localStream).text)
                        if (finalText.isNotBlank()) {
                            withContext(Dispatchers.Main) { callback.onResult(finalText) }
                        }
                        // Reset for next utterance + reset LID for next segment
                        localRecognizer.reset(localStream)
                        lidBuffer.clear(); lidSampleCount = 0; lidDetected = false
                    }
                }
            } finally {
                // Flush remaining audio on stop
                try {
                    val finalText = normalizeCase(localRecognizer.getResult(localStream).text)
                    if (finalText.isNotBlank()) {
                        withContext(Dispatchers.Main) { callback.onResult(finalText) }
                    }
                    localRecognizer.reset(localStream)
                } catch (_: Throwable) {}
                releaseAudioResources()
            }
        }
    }

    /**
     * Normalise ASR output to sentence case.
     * The Zipformer English model produces ALL-CAPS tokens; apply only when ≥70% of
     * letters are uppercase so Chinese/bilingual output is left untouched.
     */
    private fun normalizeCase(text: String): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return trimmed
        val letterCount = trimmed.count { it.isLetter() }
        if (letterCount == 0) return trimmed
        val upperRatio = trimmed.count { it.isLetter() && it.isUpperCase() }.toFloat() / letterCount
        if (upperRatio < 0.7f) return trimmed
        return trimmed.lowercase().replaceFirstChar { it.uppercaseChar() }
    }

    fun stopGracefully(): Job? {
        recording = false
        try { audioRecord?.stop() } catch (_: Throwable) {}
        return asrJob
    }

    fun stop() {
        recording = false
        asrJob?.cancel()
        asrJob = null
        releaseAudioResources()
    }

    private fun releaseAudioResources() {
        try { aec?.release() } catch (_: Throwable) {}; aec = null
        try { audioRecord?.stop() } catch (_: Throwable) {}
        try { audioRecord?.release() } catch (_: Throwable) {}
        audioRecord = null
    }

    fun release() {
        stop()
        try { stream?.release() } catch (_: Throwable) {}
        try { recognizer?.release() } catch (_: Throwable) {}
        stream = null
        recognizer = null
        modelReady = false
        currentModel = null
    }
}
