package com.example.myapplication1.tts

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * High-quality TTS using Microsoft Edge's neural voice service.
 *
 * Implements the Sec-MS-GEC DRM token required since late 2024.
 * Based on https://github.com/rany2/edge-tts
 */
class EdgeTts(private val cacheDir: File) : AutoCloseable {

    companion object {
        private const val TAG = "EdgeTts"

        // ---- Constants matching edge-tts Python v7.2.7 ----
        private const val TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
        private const val BASE_URL = "speech.platform.bing.com/consumer/speech/synthesize/readaloud"
        private const val WSS_URL = "wss://$BASE_URL/edge/v1?TrustedClientToken=$TRUSTED_CLIENT_TOKEN"

        private const val CHROMIUM_FULL_VERSION = "143.0.3650.75"
        private const val CHROMIUM_MAJOR_VERSION = "143"
        private const val SEC_MS_GEC_VERSION = "1-$CHROMIUM_FULL_VERSION"

        private const val ORIGIN = "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36" +
                    " (KHTML, like Gecko) Chrome/$CHROMIUM_MAJOR_VERSION.0.0.0 Safari/537.36" +
                    " Edg/$CHROMIUM_MAJOR_VERSION.0.0.0"

        // Windows epoch offset from Unix epoch (seconds)
        private const val WIN_EPOCH = 11644473600L

        /** Available high-quality Chinese voices */
        val ZH_VOICES = listOf(
            "zh-CN-XiaoxiaoNeural" to "晓晓 (女·温暖自然)",
            "zh-CN-YunxiNeural" to "云希 (男·沉稳)",
            "zh-CN-XiaoyiNeural" to "晓伊 (女·活泼)",
            "zh-CN-YunjianNeural" to "云健 (男·播音)",
            "zh-CN-XiaochenNeural" to "晓辰 (女·轻松)",
            "zh-CN-YunyangNeural" to "云扬 (男·新闻)",
        )

        // ---- DRM: Sec-MS-GEC token generation ----

        @Volatile
        private var clockSkewSeconds: Double = 0.0

        /**
         * Generate the Sec-MS-GEC token.
         * Algorithm: SHA256(windowsFileTimeTicks + trustedClientToken).uppercase()
         * where ticks = (unix_time + WIN_EPOCH) rounded down to nearest 5 minutes,
         * then converted to 100-nanosecond intervals.
         */
        private fun generateSecMsGec(): String {
            var ticks = (System.currentTimeMillis() / 1000.0) + clockSkewSeconds
            ticks += WIN_EPOCH
            ticks -= ticks % 300       // round down to nearest 5 minutes
            ticks *= 1e9 / 100         // convert to 100-nanosecond intervals

            val strToHash = "${"%.0f".format(ticks)}$TRUSTED_CLIENT_TOKEN"
            val digest = MessageDigest.getInstance("SHA-256").digest(strToHash.toByteArray(Charsets.US_ASCII))
            return digest.joinToString("") { "%02X".format(it) }
        }

        /** Generate a random MUID cookie value. */
        private fun generateMuid(): String {
            val bytes = ByteArray(16)
            java.security.SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02X".format(it) }
        }

        private fun connectId(): String = UUID.randomUUID().toString().replace("-", "")

        /**
         * Handle 403 by adjusting clock skew from server Date header.
         */
        private fun adjustClockSkew(response: Response?) {
            val serverDate = response?.header("Date") ?: return
            try {
                val sdf = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", java.util.Locale.US)
                sdf.timeZone = java.util.TimeZone.getTimeZone("GMT")
                val serverTime = sdf.parse(serverDate)?.time ?: return
                val clientTime = System.currentTimeMillis()
                clockSkewSeconds += (serverTime - clientTime) / 1000.0
                Log.i(TAG, "Clock skew adjusted: ${clockSkewSeconds}s")
            } catch (_: Throwable) {}
        }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    @Volatile private var currentPlayer: MediaPlayer? = null

    /**
     * Synthesise and play [text] using the specified Edge neural voice.
     * Suspends until playback completes.
     */
    suspend fun speak(
        text: String,
        voice: String = "zh-CN-XiaoxiaoNeural",
        rate: String = "+0%",
        pitch: String = "+0Hz",
        volume: String = "+0%"
    ) {
        if (text.isBlank()) return
        // Retry once with clock skew adjustment on failure
        val audioFile = synthesizeToFile(text, voice, rate, pitch, volume)
            ?: synthesizeToFile(text, voice, rate, pitch, volume)
            ?: run { Log.e(TAG, "Synthesis failed after retry"); return }
        playFile(audioFile)
        audioFile.delete()
    }

    suspend fun synthesizeToFile(
        text: String, voice: String,
        rate: String = "+0%", pitch: String = "+0Hz", volume: String = "+0%"
    ): File? = withContext(Dispatchers.IO) {
        try {
            synthesizeInternal(text, voice, rate, pitch, volume)
        } catch (t: Throwable) {
            Log.e(TAG, "Synthesis error: ${t.message}")
            null
        }
    }

    private suspend fun synthesizeInternal(
        text: String, voice: String,
        rate: String, pitch: String, volume: String
    ): File? = suspendCancellableCoroutine { cont ->
        val connId = connectId()
        val secMsGec = generateSecMsGec()

        val url = "$WSS_URL&ConnectionId=$connId" +
                "&Sec-MS-GEC=$secMsGec" +
                "&Sec-MS-GEC-Version=$SEC_MS_GEC_VERSION"

        val request = Request.Builder()
            .url(url)
            .header("Pragma", "no-cache")
            .header("Cache-Control", "no-cache")
            .header("Origin", ORIGIN)
            .header("User-Agent", USER_AGENT)
            .header("Accept-Encoding", "gzip, deflate, br, zstd")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Cookie", "muid=${generateMuid()};")
            .build()

        val audioChunks = mutableListOf<ByteArray>()
        var completed = false

        val ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                // 1) Send output format config
                webSocket.send(
                    "Content-Type:application/json; charset=utf-8\r\n" +
                            "Path:speech.config\r\n\r\n" +
                            """{"context":{"synthesis":{"audio":{"metadataoptions":{""" +
                            """"sentenceBoundaryEnabled":"false","wordBoundaryEnabled":"false"},""" +
                            """"outputFormat":"audio-24khz-48kbitrate-mono-mp3"}}}}"""
                )

                // 2) Send SSML
                val escaped = text
                    .replace("&", "&amp;").replace("<", "&lt;")
                    .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
                val reqId = connectId()
                webSocket.send(
                    "X-RequestId:$reqId\r\n" +
                            "Content-Type:application/ssml+xml\r\n" +
                            "Path:ssml\r\n\r\n" +
                            "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='zh-CN'>" +
                            "<voice name='$voice'>" +
                            "<prosody rate='$rate' pitch='$pitch' volume='$volume'>" +
                            escaped +
                            "</prosody></voice></speak>"
                )
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.contains("Path:turn.end")) {
                    completed = true
                    webSocket.close(1000, "done")
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                // Binary frame: 2-byte big-endian header length + header text + MP3 audio
                val data = bytes.toByteArray()
                if (data.size < 2) return
                val headerLen = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
                val audioStart = 2 + headerLen
                if (audioStart < data.size) {
                    val audio = data.copyOfRange(audioStart, data.size)
                    if (audio.isNotEmpty()) {
                        synchronized(audioChunks) { audioChunks.add(audio) }
                    }
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                finishSynthesis(audioChunks, cont)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure: ${t.message}")
                // Adjust clock skew for next retry
                adjustClockSkew(response)
                if (cont.isActive) cont.resume(null)
            }
        })

        cont.invokeOnCancellation {
            try { ws.cancel() } catch (_: Throwable) {}
        }
    }

    private fun finishSynthesis(
        chunks: MutableList<ByteArray>,
        cont: kotlinx.coroutines.CancellableContinuation<File?>
    ) {
        if (!cont.isActive) return
        val all: List<ByteArray>
        synchronized(chunks) { all = chunks.toList() }
        if (all.isEmpty()) { cont.resume(null); return }
        try {
            val outFile = File(cacheDir, "edge_tts_${System.currentTimeMillis()}.mp3")
            FileOutputStream(outFile).use { fos -> for (chunk in all) fos.write(chunk) }
            cont.resume(outFile)
        } catch (t: Throwable) {
            Log.e(TAG, "Write audio failed: ${t.message}")
            cont.resume(null)
        }
    }

    suspend fun playFile(file: File) {
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

    /**
     * Stream synthesis + playback: starts playing as soon as audio data arrives
     * via a pipe, instead of waiting for full synthesis to complete.
     * Saves 300-800ms compared to synthesize-then-play.
     */
    suspend fun synthesizeAndPlay(
        text: String,
        voice: String = "zh-CN-XiaoxiaoNeural",
        rate: String = "+0%",
        pitch: String = "+0Hz",
        volume: String = "+0%"
    ) {
        if (text.isBlank()) return
        stopPlayer()

        withContext(Dispatchers.IO) {
            val pipe = ParcelFileDescriptor.createPipe()
            val readPfd = pipe[0]
            val writePfd = pipe[1]

            // Launch WebSocket synthesis writing to pipe
            val synthJob: Job = CoroutineScope(coroutineContext).launch(Dispatchers.IO) {
                try {
                    synthesizeToStream(text, voice, rate, pitch, volume, writePfd)
                } catch (t: Throwable) {
                    Log.e(TAG, "Stream synthesis error: ${t.message}")
                } finally {
                    try { writePfd.close() } catch (_: Throwable) {}
                }
            }

            // Play from read end of pipe — starts as soon as MP3 data arrives
            try {
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
                        mp.setDataSource(readPfd.fileDescriptor)
                        mp.setOnPreparedListener { it.start() }
                        mp.setOnCompletionListener {
                            it.release()
                            if (currentPlayer === it) currentPlayer = null
                            if (cont.isActive) cont.resume(Unit)
                        }
                        mp.setOnErrorListener { mp2, what, extra ->
                            Log.e(TAG, "Stream playback error: what=$what extra=$extra")
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
                        Log.e(TAG, "Stream play setup failed: ${t.message}")
                        try { mp.release() } catch (_: Throwable) {}
                        if (currentPlayer === mp) currentPlayer = null
                        if (cont.isActive) cont.resume(Unit)
                    }
                }
            } finally {
                try { readPfd.close() } catch (_: Throwable) {}
                synthJob.join()
            }
        }
    }

    /**
     * Synthesize audio and write MP3 chunks directly to a ParcelFileDescriptor
     * as they arrive from the WebSocket.
     */
    private suspend fun synthesizeToStream(
        text: String, voice: String,
        rate: String, pitch: String, volume: String,
        writePfd: ParcelFileDescriptor
    ) = suspendCancellableCoroutine { cont ->
        val connId = connectId()
        val secMsGec = generateSecMsGec()

        val url = "$WSS_URL&ConnectionId=$connId" +
                "&Sec-MS-GEC=$secMsGec" +
                "&Sec-MS-GEC-Version=$SEC_MS_GEC_VERSION"

        val request = Request.Builder()
            .url(url)
            .header("Pragma", "no-cache")
            .header("Cache-Control", "no-cache")
            .header("Origin", ORIGIN)
            .header("User-Agent", USER_AGENT)
            .header("Accept-Encoding", "gzip, deflate, br, zstd")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Cookie", "muid=${generateMuid()};")
            .build()

        val outStream = ParcelFileDescriptor.AutoCloseOutputStream(writePfd)

        val ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(
                    "Content-Type:application/json; charset=utf-8\r\n" +
                            "Path:speech.config\r\n\r\n" +
                            """{"context":{"synthesis":{"audio":{"metadataoptions":{""" +
                            """"sentenceBoundaryEnabled":"false","wordBoundaryEnabled":"false"},""" +
                            """"outputFormat":"audio-24khz-48kbitrate-mono-mp3"}}}}"""
                )

                val escaped = text
                    .replace("&", "&amp;").replace("<", "&lt;")
                    .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
                val reqId = connectId()
                webSocket.send(
                    "X-RequestId:$reqId\r\n" +
                            "Content-Type:application/ssml+xml\r\n" +
                            "Path:ssml\r\n\r\n" +
                            "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='zh-CN'>" +
                            "<voice name='$voice'>" +
                            "<prosody rate='$rate' pitch='$pitch' volume='$volume'>" +
                            escaped +
                            "</prosody></voice></speak>"
                )
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.contains("Path:turn.end")) {
                    webSocket.close(1000, "done")
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val data = bytes.toByteArray()
                if (data.size < 2) return
                val headerLen = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
                val audioStart = 2 + headerLen
                if (audioStart < data.size) {
                    try {
                        outStream.write(data, audioStart, data.size - audioStart)
                        outStream.flush()
                    } catch (e: Throwable) {
                        Log.w(TAG, "Pipe write error: ${e.message}")
                        webSocket.cancel()
                    }
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                try { outStream.close() } catch (_: Throwable) {}
                if (cont.isActive) cont.resume(Unit)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "Stream WebSocket failure: ${t.message}")
                adjustClockSkew(response)
                try { outStream.close() } catch (_: Throwable) {}
                if (cont.isActive) cont.resume(Unit)
            }
        })

        cont.invokeOnCancellation {
            try { ws.cancel() } catch (_: Throwable) {}
            try { outStream.close() } catch (_: Throwable) {}
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

    override fun close() {
        stopPlayer()
        client.dispatcher.executorService.shutdown()
    }
}
