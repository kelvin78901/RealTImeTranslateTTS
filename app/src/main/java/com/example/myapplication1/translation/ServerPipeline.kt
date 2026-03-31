package com.example.myapplication1.translation

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.ParcelFileDescriptor
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.*
import okhttp3.*
import okio.ByteString
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Client that offloads the entire translation pipeline to a PC/server.
 *
 * Phone sends raw PCM audio → Server does ASR + Translation + TTS →
 * Phone receives translated text + TTS audio chunks for playback.
 *
 * This replaces TranslationPipeline, TranslationEngine, TranslationRefiner,
 * and TTS when server mode is enabled.
 */
class ServerPipeline(
    private val scope: CoroutineScope,
    private val cacheDir: File
) {
    companion object {
        private const val TAG = "ServerPipeline"
    }

    interface Callback {
        fun onConnected()
        fun onDisconnected()
        fun onPartialAsr(text: String)
        fun onTranslation(en: String, zh: String, merged: Boolean, latencyMs: Map<String, Int>)
        fun onTtsChunk(audioData: ByteArray, isFinal: Boolean)
        fun onError(message: String)
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)  // no read timeout for WebSocket
        .writeTimeout(10, TimeUnit.SECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    private var ws: WebSocket? = null
    private var callback: Callback? = null
    @Volatile var isConnected = false
        private set

    // TTS playback state
    @Volatile private var currentPlayer: MediaPlayer? = null
    private var ttsFileStream: FileOutputStream? = null
    private var ttsFile: File? = null
    private var ttsPlayJob: Job? = null

    fun setCallback(cb: Callback?) { callback = cb }

    fun connect(serverUrl: String, config: JSONObject) {
        disconnect()

        val request = Request.Builder()
            .url(serverUrl)
            .build()

        ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "Connected to server")
                isConnected = true
                callback?.onConnected()
                // Send config
                config.put("type", "config")
                webSocket.send(config.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    handleMessage(JSONObject(text))
                } catch (e: Throwable) {
                    Log.e(TAG, "Error handling message: ${e.message}")
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "Disconnected from server")
                isConnected = false
                callback?.onDisconnected()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure: ${t.message}")
                isConnected = false
                callback?.onDisconnected()
                callback?.onError("Server connection failed: ${t.message}")
            }
        })
    }

    private fun handleMessage(msg: JSONObject) {
        when (msg.optString("type")) {
            "partial" -> {
                callback?.onPartialAsr(msg.optString("text", ""))
            }
            "translation" -> {
                val latencyObj = msg.optJSONObject("latency_ms")
                val latencyMap = mutableMapOf<String, Int>()
                latencyObj?.let {
                    for (key in it.keys()) {
                        latencyMap[key] = it.optInt(key)
                    }
                }
                callback?.onTranslation(
                    msg.optString("en", ""),
                    msg.optString("zh", ""),
                    msg.optBoolean("merged", false),
                    latencyMap
                )
            }
            "tts_audio" -> {
                val b64 = msg.optString("data", "")
                val isFinal = msg.optBoolean("final", false)
                if (b64.isNotEmpty()) {
                    val audioData = Base64.decode(b64, Base64.DEFAULT)
                    callback?.onTtsChunk(audioData, isFinal)
                    // Accumulate to file for playback
                    appendTtsChunk(audioData)
                }
                if (isFinal) {
                    finalizeTtsPlayback()
                }
            }
            "error" -> {
                callback?.onError(msg.optString("message", "Unknown server error"))
            }
            "ready" -> {
                Log.i(TAG, "Server ready")
            }
            "reset_ack" -> {
                Log.i(TAG, "Server reset acknowledged")
            }
        }
    }

    /**
     * Send raw PCM audio (16kHz 16-bit mono) to the server.
     */
    fun sendAudio(pcmData: ByteArray) {
        if (!isConnected) return
        val b64 = Base64.encodeToString(pcmData, Base64.NO_WRAP)
        val msg = JSONObject().apply {
            put("type", "audio")
            put("data", b64)
        }
        ws?.send(msg.toString())
    }

    /**
     * Force server to transcribe whatever audio it has buffered.
     */
    fun forceTranscribe() {
        if (!isConnected) return
        ws?.send("""{"type":"force_transcribe"}""")
    }

    fun reset() {
        if (!isConnected) return
        ws?.send("""{"type":"reset"}""")
        stopPlayer()
    }

    fun disconnect() {
        stopPlayer()
        ws?.close(1000, "bye")
        ws = null
        isConnected = false
    }

    // ─── TTS Playback ───

    private fun appendTtsChunk(data: ByteArray) {
        if (ttsFileStream == null) {
            ttsFile = File(cacheDir, "server_tts_${System.currentTimeMillis()}.mp3")
            ttsFileStream = FileOutputStream(ttsFile!!)
        }
        try {
            ttsFileStream?.write(data)
            ttsFileStream?.flush()
        } catch (e: Throwable) {
            Log.e(TAG, "TTS write error: ${e.message}")
        }
    }

    private fun finalizeTtsPlayback() {
        try { ttsFileStream?.close() } catch (_: Throwable) {}
        ttsFileStream = null

        val file = ttsFile ?: return
        ttsFile = null

        ttsPlayJob = scope.launch {
            playFile(file)
            file.delete()
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
                        it.release()
                        if (currentPlayer === it) currentPlayer = null
                        if (cont.isActive) cont.resume(Unit)
                    }
                    mp.setOnErrorListener { mp2, what, extra ->
                        Log.e(TAG, "Playback error: what=$what extra=$extra")
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
                    Log.e(TAG, "Play failed: ${t.message}")
                    try { mp.release() } catch (_: Throwable) {}
                    if (currentPlayer === mp) currentPlayer = null
                    if (cont.isActive) cont.resume(Unit)
                }
            }
        }
    }

    fun stopPlayer() {
        try {
            currentPlayer?.let {
                if (it.isPlaying) it.stop()
                it.release()
            }
        } catch (_: Throwable) {}
        currentPlayer = null
    }

    fun close() {
        disconnect()
        client.dispatcher.executorService.shutdown()
    }
}
