package com.example.myapplication1.asr

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.k2fsa.sherpa.onnx.*
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlin.math.max

/**
 * ASR engine that records audio, uses Silero VAD for accurate speech detection,
 * and sends speech segments to a Whisper-compatible API (OpenAI or Groq).
 */
class WhisperApiAsr(
    private val context: Context,
    private val apiKey: String,
    private val provider: Provider = Provider.OPENAI
) {
    enum class Provider(val baseUrl: String, val model: String) {
        OPENAI("https://api.openai.com/v1/audio/transcriptions", "whisper-1"),
        GROQ("https://api.groq.com/openai/v1/audio/transcriptions", "whisper-large-v3-turbo"),
        GPT4O("https://api.openai.com/v1/audio/transcriptions", "gpt-4o-transcribe"),
        GPT4O_MINI("https://api.openai.com/v1/audio/transcriptions", "gpt-4o-mini-transcribe")
    }

    companion object {
        private const val TAG = "WhisperApiAsr"
        private const val SAMPLE_RATE = 16000
        private const val VAD_URL = "https://huggingface.co/csukuangfj/vad/resolve/main/silero_vad.onnx"

        /**
         * Find an existing Silero VAD model file anywhere in filesDir.
         * Checks shared location first, then any sherpa-whisper-* model dir.
         */
        fun findVadFile(context: Context): File? {
            // Shared location
            val shared = File(context.filesDir, "silero_vad.onnx")
            if (shared.exists() && shared.length() > 100) return shared
            // Check any whisper model dir
            context.filesDir.listFiles()?.forEach { dir ->
                if (dir.isDirectory && dir.name.startsWith("sherpa-whisper")) {
                    val f = File(dir, "silero_vad.onnx")
                    if (f.exists() && f.length() > 100) return f
                }
            }
            return null
        }

        /**
         * Download the Silero VAD model to the shared location if not present.
         * Returns the file on success, null on failure.
         */
        fun ensureVadDownloaded(context: Context): File? {
            findVadFile(context)?.let { return it }
            // Download to shared location
            val outFile = File(context.filesDir, "silero_vad.onnx")
            val tmpFile = File(context.filesDir, "silero_vad.onnx.tmp")
            return try {
                val dlClient = OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(60, TimeUnit.SECONDS)
                    .followRedirects(true)
                    .build()
                val request = Request.Builder().url(VAD_URL).build()
                val response = dlClient.newCall(request).execute()
                if (!response.isSuccessful) {
                    Log.e(TAG, "VAD download failed: HTTP ${response.code}")
                    return null
                }
                response.body?.byteStream()?.use { input ->
                    java.io.FileOutputStream(tmpFile).use { output ->
                        input.copyTo(output)
                    }
                }
                tmpFile.renameTo(outFile)
                Log.i(TAG, "VAD model downloaded to ${outFile.absolutePath}")
                outFile
            } catch (e: Throwable) {
                Log.e(TAG, "VAD download error: ${e.message}")
                tmpFile.delete()
                null
            }
        }

        /** Check if VAD model is available (without downloading). */
        fun isVadAvailable(context: Context): Boolean = findVadFile(context) != null
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    interface Callback {
        fun onListening()
        fun onSpeechDetected()
        fun onProcessing()
        fun onResult(text: String)
        fun onError(message: String)
    }

    @Volatile private var recording = false
    private var audioRecord: AudioRecord? = null
    private var job: Job? = null
    private var vad: Vad? = null
    private var aec: android.media.audiofx.AcousticEchoCanceler? = null

    private fun ensureVad(): Boolean {
        if (vad != null) return true
        // Search for VAD model in shared location or any whisper model dir
        val vadFile = findVadFile(context) ?: return false
        try {
            val vadConfig = VadModelConfig().apply {
                sileroVadModelConfig = SileroVadModelConfig(
                    model = vadFile.absolutePath,
                    threshold = 0.5f,
                    minSilenceDuration = 0.3f,
                    minSpeechDuration = 0.25f,
                    windowSize = 512,
                    maxSpeechDuration = 15f
                )
                sampleRate = SAMPLE_RATE
                numThreads = 1
                provider = "cpu"
            }
            vad = Vad(null, vadConfig)
            Log.i(TAG, "Silero VAD initialized from: ${vadFile.absolutePath}")
            return true
        } catch (e: Throwable) {
            Log.e(TAG, "VAD init failed: ${e.message}")
            return false
        }
    }

    @SuppressLint("MissingPermission")
    fun start(scope: CoroutineScope, callback: Callback) {
        if (recording) return
        if (apiKey.isBlank()) {
            callback.onError("请先设置 API Key")
            return
        }

        val useVad = ensureVad()

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
            if (useVad) {
                runWithVad(callback)
            } else {
                runWithSimpleVad(callback)
            }
        }
    }

    private suspend fun CoroutineScope.runWithVad(callback: Callback) {
        val buffer = ShortArray(512)  // match VAD window size
        val localVad = vad ?: return

        while (isActive && recording) {
            val read = audioRecord?.read(buffer, 0, buffer.size) ?: break
            if (read <= 0) continue

            val floatSamples = FloatArray(read) { buffer[it] / 32768.0f }
            localVad.acceptWaveform(floatSamples)

            if (localVad.isSpeechDetected()) {
                withContext(Dispatchers.Main) { callback.onSpeechDetected() }
            }

            while (!localVad.empty()) {
                val segment = localVad.front()
                localVad.pop()

                // Launch API call concurrently so the audio reading loop is NOT blocked.
                val pcm = floatToPcm(segment.samples)
                launch { sendToApi(pcm, callback) }
            }
        }

        // Flush remaining VAD buffer — concurrent API calls, wait for all to finish
        localVad.flush()
        val flushJobs = mutableListOf<Job>()
        while (!localVad.empty()) {
            val segment = localVad.front()
            localVad.pop()
            val pcm = floatToPcm(segment.samples)
            flushJobs += launch { sendToApi(pcm, callback) }
        }
        flushJobs.forEach { it.join() }
        localVad.reset()
    }

    /** Fallback: simple energy-based VAD when Silero VAD model not available */
    private suspend fun CoroutineScope.runWithSimpleVad(callback: Callback) {
        val buffer = ShortArray(1024)
        val speechBuffer = ByteArrayOutputStream()
        var inSpeech = false
        var silenceFrames = 0
        var speechFrames = 0
        val msPerFrame = (buffer.size * 1000) / SAMPLE_RATE
        val silenceFramesNeeded = 800 / msPerFrame    // 800ms silence = end
        val minPcmBytes = 300 * SAMPLE_RATE * 2 / 1000  // 300ms min

        while (isActive && recording) {
            val read = audioRecord?.read(buffer, 0, buffer.size) ?: break
            if (read <= 0) continue

            val rms = buffer.take(read).fold(0.0) { acc, s -> acc + s * s } / read
            val db = 10 * kotlin.math.log10(rms + 1e-9)
            val isSpeech = db > -35.0

            if (isSpeech) {
                if (!inSpeech) {
                    inSpeech = true
                    speechBuffer.reset()
                    speechFrames = 0
                    withContext(Dispatchers.Main) { callback.onSpeechDetected() }
                }
                silenceFrames = 0
                speechFrames++
                val byteBuffer = ByteBuffer.allocate(read * 2).order(ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until read) byteBuffer.putShort(buffer[i])
                speechBuffer.write(byteBuffer.array())
            } else if (inSpeech) {
                silenceFrames++
                val byteBuffer = ByteBuffer.allocate(read * 2).order(ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until read) byteBuffer.putShort(buffer[i])
                speechBuffer.write(byteBuffer.array())

                if (silenceFrames >= silenceFramesNeeded) {
                    inSpeech = false
                    val pcm = speechBuffer.toByteArray()
                    speechBuffer.reset()
                    speechFrames = 0
                    silenceFrames = 0

                    if (pcm.size > minPcmBytes) {
                        withContext(Dispatchers.Main) { callback.onProcessing() }
                        sendToApi(pcm, callback)
                        withContext(Dispatchers.Main) { callback.onListening() }
                    }
                }
            }
        }

        if (inSpeech && speechBuffer.size() > minPcmBytes) {
            val pcm = speechBuffer.toByteArray()
            withContext(Dispatchers.Main) { callback.onProcessing() }
            sendToApi(pcm, callback)
        }
    }

    /**
     * Graceful stop: stops the microphone, lets the VAD flush remaining buffered
     * audio and send it to the API, then cleans up.  The returned Job (if non-null)
     * completes when all buffered audio has been processed.
     */
    fun stopGracefully(): Job? {
        recording = false                       // loop exits naturally
        try { audioRecord?.stop() } catch (_: Throwable) {}   // stop mic immediately
        val j = job                             // let flush code in coroutine run
        // Attach cleanup to coroutine completion
        j?.invokeOnCompletion { releaseResources() }
        return j
    }

    /** Immediate hard stop – cancels the coroutine (flush will NOT run). */
    fun stop() {
        recording = false
        job?.cancel()
        job = null
        releaseResources()
    }

    private fun releaseResources() {
        job = null
        try { aec?.release() } catch (_: Throwable) {}; aec = null
        try { audioRecord?.stop() } catch (_: Throwable) {}
        try { audioRecord?.release() } catch (_: Throwable) {}
        audioRecord = null
    }

    fun close() {
        stop()
        try { vad?.release() } catch (_: Throwable) {}
        vad = null
        client.dispatcher.executorService.shutdown()
    }

    private fun floatToPcm(samples: FloatArray): ByteArray {
        val buf = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) {
            val v = (s * 32767f).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            buf.putShort(v.toShort())
        }
        return buf.array()
    }

    private suspend fun sendToApi(pcmData: ByteArray, callback: Callback) {
        try {
            val wav = pcmToWav(pcmData, SAMPLE_RATE, 1, 16)
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "file", "audio.wav",
                    wav.toRequestBody("audio/wav".toMediaType())
                )
                .addFormDataPart("model", provider.model)
                .addFormDataPart("language", "en")
                .addFormDataPart("response_format", "json")
                .build()

            val request = Request.Builder()
                .url(provider.baseUrl)
                .header("Authorization", "Bearer $apiKey")
                .post(body)
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()

            if (response.isSuccessful && responseBody != null) {
                val text = JSONObject(responseBody).optString("text", "").trim()
                if (text.isNotBlank()) {
                    withContext(Dispatchers.Main) { callback.onResult(text) }
                }
            } else {
                val error = responseBody?.let {
                    try { JSONObject(it).optJSONObject("error")?.optString("message") } catch (_: Throwable) { null }
                } ?: "HTTP ${response.code}"
                Log.e(TAG, "API error: $error")
                withContext(Dispatchers.Main) { callback.onError("API: $error") }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Send failed: ${e.message}")
            withContext(Dispatchers.Main) { callback.onError("网络: ${e.message}") }
        }
    }

    private fun pcmToWav(pcm: ByteArray, sampleRate: Int, channels: Int, bitsPerSample: Int): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val dataSize = pcm.size
        val totalSize = 36 + dataSize

        val out = ByteArrayOutputStream(44 + dataSize)
        val buf = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)

        buf.put("RIFF".toByteArray())
        buf.putInt(totalSize)
        buf.put("WAVE".toByteArray())
        buf.put("fmt ".toByteArray())
        buf.putInt(16)
        buf.putShort(1)
        buf.putShort(channels.toShort())
        buf.putInt(sampleRate)
        buf.putInt(byteRate)
        buf.putShort(blockAlign.toShort())
        buf.putShort(bitsPerSample.toShort())
        buf.put("data".toByteArray())
        buf.putInt(dataSize)

        out.write(buf.array())
        out.write(pcm)
        return out.toByteArray()
    }
}
