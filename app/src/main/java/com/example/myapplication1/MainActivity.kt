package com.example.myapplication1

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.*
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.example.myapplication1.asr.SherpaWhisperAsr
import com.example.myapplication1.asr.WhisperApiAsr
import com.example.myapplication1.tts.EdgeTts
import com.example.myapplication1.translation.*
import com.example.myapplication1.translation.OnDeviceTranslationModel
import com.example.myapplication1.translation.TranslationModelManager
import com.example.myapplication1.tts.GoogleTranslateTts
import com.example.myapplication1.tts.OpenAiTts
import com.example.myapplication1.ui.theme.MyApplication1Theme
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.log10
import kotlin.math.max

class MainActivity : ComponentActivity() {

    // ---- 段落模型 ----
    data class Segment(val seqId: Int = -1, val en: String, val zh: String = "", val translating: Boolean = true)
    data class Paragraph(
        val id: Int,
        val segments: List<Segment> = emptyList(),
        val refinedZh: String = "",
        val refining: Boolean = false,
        val refinedAtCount: Int = 0   // segment count when refinement was done
    ) {
        val combinedEn: String get() = segments.joinToString(" ") { it.en }
        val rawZh: String get() = segments.filter { it.zh.isNotBlank() }.joinToString("") { it.zh }
        /** Show refined text only if no new segments arrived after refinement. */
        val displayZh: String get() =
            if (refinedZh.isNotBlank() && segments.size == refinedAtCount) refinedZh else rawZh
        val anyTranslating: Boolean get() = segments.any { it.translating }
        val allDone: Boolean get() = segments.isNotEmpty() && segments.none { it.translating }
    }

    private val _paragraphs = mutableStateListOf<Paragraph>()
    private var _nextParagraphId = 0
    private var _currentPartial by mutableStateOf("")
    private var _recording by mutableStateOf(false)
    private var _micHeardVoice by mutableStateOf(false)

    // ---- 设置 ----
    private var _autoSpeak by mutableStateOf(true)
    // ASR: 0=系统, 1=Vosk, 2=OpenAI, 3=Groq, 4=本地Whisper
    private var _asrEngine by mutableStateOf(0)
    // TTS: 0=Edge, 1=系统, 2=Google, 3=OpenAI
    private var _ttsEngine by mutableStateOf(0)
    private var _edgeVoiceIdx by mutableStateOf(0)
    private var _openaiVoiceIdx by mutableStateOf(4) // nova
    private var _openaiKey by mutableStateOf("")
    private var _groqKey by mutableStateOf("")
    private var _whisperModelIdx by mutableStateOf(0)

    // 翻译引擎: 0=MLKit, 1=OpenAI, 2=Groq, 3=DeepL, 4=本地服务器, 5=Opus-MT, 6=NLLB
    private var _translationEngineType by mutableStateOf(0)
    private var _deeplKey by mutableStateOf("")
    private var _localServerUrl by mutableStateOf("http://192.168.1.100:11434/v1")
    private var _localServerModel by mutableStateOf("qwen2.5:7b")

    // AI 润色: 用快速 LLM API 修正离线翻译结果
    // 0=关闭, 1=Groq, 2=OpenAI, 3=本地服务器, 4=手机本机
    private var _refineProvider by mutableStateOf(0)
    private var _refineModel by mutableStateOf("llama-3.3-70b-versatile")
    private var _refineServerUrl by mutableStateOf("http://192.168.1.100:11434/v1")

    // ---- 音频设备 ----
    private var _inputDevices = mutableStateListOf<AudioDeviceInfo>()
    private var _outputDevices = mutableStateListOf<AudioDeviceInfo>()
    private var _selectedInputId by mutableStateOf(0)   // 0 = default
    private var _selectedOutputId by mutableStateOf(0)

    // ---- 媒体转译 ----
    private var _mediaCaptureActive by mutableStateOf(false)
    private var _mediaCaptureStatus by mutableStateOf("")
    private var _mediaSourceApp by mutableStateOf("")
    private var _mediaCaptureError by mutableStateOf("")

    // ---- 段落管理 ----
    private var paragraphGapJob: Job? = null       // fires when speaker pauses → close paragraph
    private val PARAGRAPH_GAP_MS = 4000L           // 4s silence = new paragraph

    // ---- 翻译引擎缓存 ----
    private var cachedTransEngine: TranslationEngine? = null
    private var cachedTransEngineType = -1

    // ---- 翻译历史 ----
    private lateinit var translationHistory: TranslationHistory
    private var _showHistory by mutableStateOf(false)

    // ---- TTS 语速自适应 ----
    private val ttsPendingCount = AtomicInteger(0)

    // ---- 翻译流水线 ----
    private lateinit var translationPipeline: TranslationPipeline

    // ---- 延迟指标 ----
    private var _asrLatencyMs by mutableStateOf(0L)
    private var _transLatencyMs by mutableStateOf(0L)
    private var _refineLatencyMs by mutableStateOf(0L)
    private var _ttsLatencyMs by mutableStateOf(0L)
    private var _ttsQueueSize by mutableStateOf(0)
    private var _ttsSpeedPct by mutableStateOf(0)

    // ---- TTS 回声抑制 ----
    @Volatile private var _isTtsSpeaking = false
    private var _ttsSpeakEndTime = 0L
    private val TTS_ECHO_GRACE_MS = 300L  // ASR结果在TTS结束后此时间内仍被抑制

    // ---- 设备指标 ----
    private var _cpuUsagePct by mutableStateOf(0f)
    private var _memoryUsageMB by mutableStateOf(0)
    private var _batteryPct by mutableStateOf(0)
    private var _batteryTempC by mutableStateOf(0f)
    private var deviceMetricsJob: Job? = null
    private var lastCpuTime = 0L
    private var lastWallTime = 0L

    // ---- 会话管理 ----
    private var _showSessionDialog by mutableStateOf(false)
    private var _historySessions = mutableStateListOf<TranslationHistory.Session>()
    private var _historySearchQuery by mutableStateOf("")
    private var _editingSessionId by mutableStateOf<String?>(null)
    private var _editingTitle by mutableStateOf("")
    private var _autoFloatingOnPause by mutableStateOf(false)
    // ---- 离线翻译模型 ----
    private var _offlineTransModelIdx by mutableStateOf(0)
    private var _offlineTransDownloading by mutableStateOf(false)
    private var _offlineTransProgress by mutableStateOf("")
    private lateinit var translationModelManager: TranslationModelManager

    private var _smartFilterEnabled by mutableStateOf(true)
    private var _filterFillers by mutableStateOf(true)
    private var _filterEcho by mutableStateOf(true)
    private var _filterNoise by mutableStateOf(true)
    private var _filterMusic by mutableStateOf(true)

    // ---- API 测试 ----
    private val apiTestManager = ApiTestManager()
    private var _apiTestResults = mutableStateListOf<ApiTestManager.ApiTestResult>()
    private var _apiTestRunning by mutableStateOf(false)
    private var _apiTestProgress by mutableStateOf("")
    // API Key 管理
    private var _showApiKeyManager by mutableStateOf(false)
    private var _newKeyName by mutableStateOf("")
    private var _newKeyValue by mutableStateOf("")

    // ---- 状态 ----
    private var _voskReady by mutableStateOf(false)
    private var _translatorReady by mutableStateOf(false)
    private var _ttsReady by mutableStateOf(false)
    private var _ttsLangOk by mutableStateOf(false)
    private var _systemAsrAvailable by mutableStateOf(false)
    private var _localWhisperReady by mutableStateOf(false)
    private var _localWhisperDownloading by mutableStateOf(false)
    private var _downloadProgress by mutableStateOf("")
    private var _logs = mutableStateListOf<String>()

    // ---- Vosk ----
    private var audioRecord: AudioRecord? = null
    private var recognizer: Recognizer? = null
    private var voskModel: Model? = null
    private var asrJob: Job? = null
    private var aec: android.media.audiofx.AcousticEchoCanceler? = null

    // ---- System ASR ----
    private var speechRecognizer: SpeechRecognizer? = null
    private var systemAsrContinue = false

    // ---- Whisper ----
    private var whisperAsr: WhisperApiAsr? = null
    private lateinit var sherpaWhisperAsr: SherpaWhisperAsr

    // ---- TTS ----
    private var systemTts: TextToSpeech? = null
    private lateinit var edgeTts: EdgeTts
    private val googleTts = GoogleTranslateTts()
    private var openAiTts: OpenAiTts? = null
    private val ttsQueue = Channel<String>(Channel.UNLIMITED)
    private var ttsConsumerJob: Job? = null

    // ---- 翻译 ----
    private val translator by lazy {
        Translation.getClient(
            com.google.mlkit.nl.translate.TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.ENGLISH)
                .setTargetLanguage(TranslateLanguage.CHINESE).build()
        )
    }

    private val requestAudioPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { if (it) { log("麦克风权限已授予"); prepareAll() } else log("未授予麦克风权限") }

    private val requestMediaProjection = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            // Pass result to MediaCaptureService (foreground service already started)
            val svcIntent = Intent(this, MediaCaptureService::class.java).apply {
                action = MediaCaptureService.ACTION_START
                putExtra(MediaCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(MediaCaptureService.EXTRA_RESULT_DATA, result.data)
            }
            startService(svcIntent)
            _mediaCaptureActive = true
            _mediaCaptureStatus = "启动中…"
        } else {
            log("用户拒绝了媒体投影权限")
            _mediaCaptureActive = false
            try { stopService(Intent(this, MediaCaptureService::class.java)) } catch (_: Throwable) {}
        }
    }

    // ===================== Lifecycle =====================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        edgeTts = EdgeTts(cacheDir)
        sherpaWhisperAsr = SherpaWhisperAsr(this)
        translationModelManager = TranslationModelManager(this)
        translationHistory = TranslationHistory(filesDir)
        _historySessions.addAll(translationHistory.load())
        loadSettings()
        refreshAudioDevices()
        // Initialize translation pipeline with ordered delivery
        translationPipeline = TranslationPipeline(lifecycleScope)
        translationPipeline.setCallback(pipelineCallback)
        translationPipeline.setEngine(getTranslationEngine())
        translationPipeline.setRefiner(buildRefiner())
        // Show session dialog if there are previous sessions
        if (_historySessions.isNotEmpty()) _showSessionDialog = true
        else translationHistory.newSession()
        FloatingTranslateService.translationCallback = floatingTranslationCb
        MediaCaptureService.asrCallback = mediaCaptureAsrCb
        setContent { MyApplication1Theme { AppUI() } }
        requestAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
        startTtsConsumer()
    }

    override fun onPause() {
        super.onPause()
        // Auto-launch floating window when leaving app if recording or media capture active
        if ((_recording || _mediaCaptureActive) && !FloatingTranslateService.isRunning) {
            if (Settings.canDrawOverlays(this)) {
                FloatingTranslateService.autoLaunched = true
                FloatingTranslateService.appWasRecording = _recording
                val svcIntent = Intent(this, FloatingTranslateService::class.java)
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(svcIntent) else startService(svcIntent)
                _autoFloatingOnPause = true
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Stop auto-launched floating window when returning
        if (_autoFloatingOnPause && FloatingTranslateService.autoLaunched) {
            try { stopService(Intent(this, FloatingTranslateService::class.java)) } catch (_: Throwable) {}
            FloatingTranslateService.autoLaunched = false
            FloatingTranslateService.appWasRecording = false
            _autoFloatingOnPause = false
        }
        // Reload history to catch floating window additions
        _historySessions.clear()
        _historySessions.addAll(translationHistory.load())
        // Re-register callbacks
        FloatingTranslateService.translationCallback = floatingTranslationCb
        // Only set asrCallback if floating service hasn't chained it
        if (!FloatingTranslateService.isRunning) {
            MediaCaptureService.asrCallback = mediaCaptureAsrCb
        }
        // Sync media capture state from service
        _mediaCaptureActive = MediaCaptureService.isCapturing
        if (_mediaCaptureActive) {
            _mediaCaptureStatus = MediaCaptureService.captureStatus
            _mediaSourceApp = MediaCaptureService.sourceAppName
        }
    }

    private val floatingTranslationCb = object : FloatingTranslateService.Companion.TranslationCallback {
        override fun onFloatingTranslation(en: String, zh: String) {
            runOnUiThread {
                // Add floating window translation to current paragraph
                if (_paragraphs.isEmpty()) _paragraphs.add(Paragraph(id = _nextParagraphId++))
                val pi = _paragraphs.lastIndex
                val p = _paragraphs[pi]
                _paragraphs[pi] = p.copy(segments = p.segments + Segment(en = en, zh = zh, translating = false))
                _historySessions.clear()
                _historySessions.addAll(translationHistory.allSessions())
            }
        }
        override fun onRecordingStateChanged(isRecording: Boolean) {
            // Sync floating window recording state (informational)
            runOnUiThread {
                log(if (isRecording) "悬浮窗开始录音" else "悬浮窗停止录音")
            }
        }
    }

    private val mediaCaptureAsrCb = object : MediaCaptureService.Companion.AsrCallback {
        override fun onPartial(text: String) {
            _currentPartial = text
        }
        override fun onResult(text: String) { onAsrResult(text) }
        override fun onStateChanged(capturing: Boolean, status: String, sourceApp: String) {
            _mediaCaptureActive = capturing
            _mediaCaptureStatus = status
            _mediaSourceApp = sourceApp
            if (!capturing && status.startsWith("错误")) {
                _mediaCaptureError = status
            }
        }
        override fun onError(msg: String) {
            _mediaCaptureError = msg
            log("媒体捕获: $msg")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        FloatingTranslateService.translationCallback = null
        MediaCaptureService.asrCallback = null
        ttsConsumerJob?.cancel(); ttsQueue.close(); stopDeviceMetrics()
        if (::translationPipeline.isInitialized) translationPipeline.close()
        stopAllAsr(); stopMediaCapture()
        try { speechRecognizer?.destroy() } catch (_: Throwable) {}
        try { recognizer?.close() } catch (_: Throwable) {}
        try { voskModel?.close() } catch (_: Throwable) {}
        try { translator.close() } catch (_: Throwable) {}
        try { systemTts?.stop(); systemTts?.shutdown() } catch (_: Throwable) {}
        sherpaWhisperAsr.release(); edgeTts.close(); googleTts.close()
        try { openAiTts?.close() } catch (_: Throwable) {}
        cachedTransEngine?.close(); translationHistory.close()
    }

    // ===================== Init =====================

    private fun prepareAll() {
        translator.downloadModelIfNeeded(DownloadConditions.Builder().requireWifi().build())
            .addOnSuccessListener { _translatorReady = true; log("翻译模型就绪") }
            .addOnFailureListener { log("翻译模型下载失败: ${it.message}") }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val dst = File(filesDir, "model-en")
                if (!dst.exists()) copyAssetDir("model-en", dst)
                voskModel = Model(dst.absolutePath)
                withContext(Dispatchers.Main) { _voskReady = true; log("Vosk 模型就绪") }
            } catch (e: Throwable) { withContext(Dispatchers.Main) { log("Vosk: ${e.message}") } }
        }

        _systemAsrAvailable = SpeechRecognizer.isRecognitionAvailable(this)
        log(if (_systemAsrAvailable) "系统ASR可用" else "系统ASR不可用")

        systemTts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val r = systemTts?.setLanguage(Locale.SIMPLIFIED_CHINESE)
                _ttsLangOk = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
                _ttsReady = true
                // 使用 USAGE_ASSISTANT 避免被 MediaCapture 捕获
                systemTts?.setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build())
                log(if (_ttsLangOk) "系统TTS就绪" else "系统TTS不支持中文")
            } else { _ttsReady = false; log("系统TTS初始化失败") }
        }
        log("Edge TTS 就绪")

        val selModel = selectedWhisperModel()
        if (sherpaWhisperAsr.isModelDownloaded(selModel)) {
            lifecycleScope.launch(Dispatchers.IO) {
                val ok = sherpaWhisperAsr.initModel(selModel)
                withContext(Dispatchers.Main) {
                    _localWhisperReady = ok
                    log(if (ok) "本地Whisper [${selModel.label}] 就绪" else "本地Whisper失败")
                }
            }
        }
    }

    // ===================== Audio Devices =====================

    private fun refreshAudioDevices() {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        _inputDevices.clear()
        _inputDevices.addAll(am.getDevices(AudioManager.GET_DEVICES_INPUTS))
        _outputDevices.clear()
        _outputDevices.addAll(am.getDevices(AudioManager.GET_DEVICES_OUTPUTS))
    }

    private fun deviceLabel(d: AudioDeviceInfo): String {
        val name = d.productName?.toString()?.takeIf { it.isNotBlank() } ?: ""
        val type = when (d.type) {
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "内置麦克风"
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "内置扬声器"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "有线耳机"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "有线耳机"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "蓝牙SCO"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "蓝牙A2DP"
            AudioDeviceInfo.TYPE_USB_DEVICE -> "USB设备"
            AudioDeviceInfo.TYPE_USB_HEADSET -> "USB耳机"
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "听筒"
            AudioDeviceInfo.TYPE_TELEPHONY -> "电话"
            else -> "设备${d.type}"
        }
        return if (name.isNotBlank() && !type.contains(name)) "$type ($name)" else type
    }

    private fun findInputDevice(): AudioDeviceInfo? {
        if (_selectedInputId == 0) return null
        return _inputDevices.firstOrNull { it.id == _selectedInputId }
    }

    private fun findOutputDevice(): AudioDeviceInfo? {
        if (_selectedOutputId == 0) return null
        return _outputDevices.firstOrNull { it.id == _selectedOutputId }
    }

    // ===================== TTS =====================

    private fun startTtsConsumer() {
        ttsConsumerJob = lifecycleScope.launch {
            // Edge TTS prefetch state: synthesize next sentence during current playback
            var prefetchedFile: java.io.File? = null
            var pendingText: String? = null  // text consumed from queue during prefetch

            while (true) {
                // Get next text: either from prefetch buffer or from queue
                val text = if (pendingText != null) {
                    pendingText.also { pendingText = null }
                } else {
                    ttsQueue.receiveCatching().getOrNull() ?: break
                }

                _ttsQueueSize = ttsPendingCount.get()
                if (_autoSpeak) {
                    val ttsStart = System.currentTimeMillis()
                    if (_ttsEngine == 0) {
                        // Edge TTS with prefetch overlap and streaming fallback
                        val v = EdgeTts.ZH_VOICES.getOrNull(_edgeVoiceIdx)?.first ?: "zh-CN-XiaoxiaoNeural"
                        val rate = dynamicEdgeRate()

                        if (prefetchedFile != null) {
                            // Have prefetched file — play it + prefetch next
                            val audioFile = prefetchedFile!!
                            prefetchedFile = null
                            val prefetchJob = launch {
                                val next = ttsQueue.tryReceive().getOrNull()
                                if (next != null) {
                                    pendingText = next
                                    prefetchedFile = edgeTts.synthesizeToFile(next, voice = v, rate = rate)
                                }
                            }
                            _isTtsSpeaking = true
                            try {
                                edgeTts.playFile(audioFile)
                            } finally {
                                _isTtsSpeaking = false
                                _ttsSpeakEndTime = System.currentTimeMillis()
                            }
                            audioFile.delete()
                            prefetchJob.join()
                        } else {
                            // No prefetch — use streaming synthesis+playback (starts playing immediately)
                            // Also prefetch next sentence during streaming playback
                            val prefetchJob = launch {
                                val next = ttsQueue.tryReceive().getOrNull()
                                if (next != null) {
                                    pendingText = next
                                    prefetchedFile = edgeTts.synthesizeToFile(next, voice = v, rate = rate)
                                }
                            }
                            _isTtsSpeaking = true
                            try {
                                edgeTts.synthesizeAndPlay(text, voice = v, rate = rate)
                            } finally {
                                _isTtsSpeaking = false
                                _ttsSpeakEndTime = System.currentTimeMillis()
                            }
                            prefetchJob.join()
                        }
                    } else {
                        speakZh(text)
                    }
                    _ttsLatencyMs = System.currentTimeMillis() - ttsStart
                }
                ttsPendingCount.decrementAndGet()
                _ttsQueueSize = ttsPendingCount.get()
            }
            prefetchedFile?.delete()
        }
    }

    private fun startDeviceMetrics() {
        deviceMetricsJob?.cancel()
        deviceMetricsJob = lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                // CPU usage from /proc/self/stat
                try {
                    val statLine = java.io.File("/proc/self/stat").readText().split(" ")
                    val utime = statLine[13].toLong()
                    val stime = statLine[14].toLong()
                    val cpuTime = utime + stime
                    val wallTime = android.os.SystemClock.elapsedRealtime()
                    if (lastWallTime > 0) {
                        val dWall = wallTime - lastWallTime
                        val dCpu = (cpuTime - lastCpuTime) * 10 // jiffies → ms (assuming HZ=100)
                        if (dWall > 0) _cpuUsagePct = (dCpu.toFloat() / dWall * 100f).coerceIn(0f, 100f * Runtime.getRuntime().availableProcessors())
                    }
                    lastCpuTime = cpuTime
                    lastWallTime = wallTime
                } catch (_: Throwable) {}

                // Memory
                val rt = Runtime.getRuntime()
                _memoryUsageMB = ((rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)).toInt()

                // Battery
                try {
                    val bm = getSystemService(BATTERY_SERVICE) as android.os.BatteryManager
                    _batteryPct = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
                    val intent = registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
                    val temp = intent?.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
                    _batteryTempC = temp / 10f
                } catch (_: Throwable) {}

                delay(2000)
            }
        }
    }

    private fun stopDeviceMetrics() {
        deviceMetricsJob?.cancel()
        deviceMetricsJob = null
    }

    private fun dynamicSpeedPct(): Int {
        val p = ttsPendingCount.get()
        return when { p <= 1 -> 0; p == 2 -> 15; p == 3 -> 25; p == 4 -> 35; else -> 50 }
    }

    private fun dynamicEdgeRate(): String {
        val pct = dynamicSpeedPct()
        _ttsSpeedPct = pct
        return "+${pct}%"
    }

    private fun dynamicSystemRate(): Float {
        val pct = dynamicSpeedPct()
        _ttsSpeedPct = pct
        return 1.0f + pct / 100f
    }

    private fun dynamicOpenAiSpeed(): Float {
        val pct = dynamicSpeedPct()
        _ttsSpeedPct = pct
        return (1.0f + pct / 100f).coerceIn(0.25f, 4.0f)
    }

    private suspend fun speakZh(text: String) {
        _isTtsSpeaking = true
        try {
            when (_ttsEngine) { 0 -> speakEdge(text); 1 -> speakSystem(text); 2 -> speakGoogle(text); 3 -> speakOpenAi(text) }
        } finally {
            _isTtsSpeaking = false
            _ttsSpeakEndTime = System.currentTimeMillis()
        }
    }

    private suspend fun speakEdge(text: String) {
        val v = EdgeTts.ZH_VOICES.getOrNull(_edgeVoiceIdx)?.first ?: "zh-CN-XiaoxiaoNeural"
        try { edgeTts.speak(text, voice = v, rate = dynamicEdgeRate()) }
        catch (_: Throwable) { speakSystem(text) }
    }

    private suspend fun speakSystem(text: String) {
        if (_ttsReady && _ttsLangOk) {
            val rate = dynamicSystemRate()
            systemTts?.setSpeechRate(rate)
            systemTts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "u-${System.currentTimeMillis()}")
            delay((text.length * 280L / rate).toLong().coerceIn(500, 12000)); return
        }
        speakGoogle(text)
    }

    private suspend fun speakGoogle(text: String) {
        try { googleTts.speak(text, "zh-CN") } catch (e: Throwable) { log("TTS: ${e.message}") }
    }

    private suspend fun speakOpenAi(text: String) {
        if (_openaiKey.isBlank()) { speakEdge(text); return }
        try {
            if (openAiTts == null) openAiTts = OpenAiTts(_openaiKey, cacheDir)
            val voice = OpenAiTts.VOICES.getOrNull(_openaiVoiceIdx)?.first ?: "nova"
            openAiTts?.speak(text, voice = voice, speed = dynamicOpenAiSpeed())
        } catch (e: Throwable) { log("OpenAI TTS: ${e.message}"); speakEdge(text) }
    }

    private fun speakManual(text: String) { lifecycleScope.launch { speakZh(text) } }

    // ===================== ASR dispatch =====================

    private fun toggleRecording() {
        if (_mediaCaptureActive) {
            log("媒体捕获中，请先停止媒体捕获再使用麦克风")
            return
        }
        if (_recording) stopAllAsr() else startAsr()
    }

    private fun startAsr() {
        if (_recording) return
        _currentPartial = ""; _micHeardVoice = false
        startDeviceMetrics()
        when (_asrEngine) {
            0 -> startSystemAsr(); 1 -> startVoskAsr()
            2 -> startWhisperApi(WhisperApiAsr.Provider.OPENAI, _openaiKey)
            3 -> startWhisperApi(WhisperApiAsr.Provider.GROQ, _groqKey)
            4 -> startLocalWhisper()
            5 -> startWhisperApi(WhisperApiAsr.Provider.GPT4O_MINI, _openaiKey)
        }
    }

    private fun stopAllAsr() {
        if (!_recording) return
        _recording = false
        paragraphGapJob?.cancel()
        log("停止录音")

        val lastParaId = _paragraphs.lastOrNull()?.id

        // Stop ASR — graceful flush for remaining buffered audio
        when (_asrEngine) {
            0 -> stopSystemAsr()
            1 -> { stopVosk(); flushVosk() }
            2, 3, 5 -> whisperAsr?.stopGracefully()
            4 -> sherpaWhisperAsr.stopGracefully()
        }

        // Trigger paragraph refinement in background (non-blocking)
        if (lastParaId != null) {
            closeParagraphById(lastParaId)
        }

        _currentPartial = ""
        stopDeviceMetrics()
    }

    private fun flushVosk() {
        val r = recognizer?.finalResult?.let { JSONObject(it).optString("text") }.orEmpty().trim()
        if (r.isNotBlank()) onAsrResult(r)
        else if (_currentPartial.isNotBlank()) onAsrResult(_currentPartial)
    }

    // ===================== System ASR =====================

    private val systemAsrListener = object : RecognitionListener {
        override fun onReadyForSpeech(p: Bundle?) {}
        override fun onBeginningOfSpeech() { _micHeardVoice = true }
        override fun onRmsChanged(r: Float) {}
        override fun onBufferReceived(b: ByteArray?) {}
        override fun onEndOfSpeech() { _asrProcessStart = System.currentTimeMillis() }
        override fun onError(e: Int) {
            if (e == SpeechRecognizer.ERROR_NO_MATCH || e == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                if (systemAsrContinue && _recording) restartSystemAsr()
            } else if (systemAsrContinue && _recording) {
                lifecycleScope.launch { delay(500); if (_recording) restartSystemAsr() }
            }
        }
        override fun onResults(r: Bundle?) {
            if (_asrProcessStart > 0) _asrLatencyMs = System.currentTimeMillis() - _asrProcessStart
            val t = r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
            if (t.isNotBlank()) onAsrResult(t)
            if (systemAsrContinue && _recording) restartSystemAsr()
        }
        override fun onPartialResults(p: Bundle?) {
            val t = p?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
            if (t.isNotBlank()) _currentPartial = t
        }
        override fun onEvent(t: Int, p: Bundle?) {}
    }

    private fun systemAsrIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
    }

    private fun startSystemAsr() {
        if (!_systemAsrAvailable) { log("系统ASR不可用"); _asrEngine = 1; startVoskAsr(); return }
        try {
            speechRecognizer?.destroy()
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                setRecognitionListener(systemAsrListener)
            }
            systemAsrContinue = true; _recording = true; log("开始录音 (系统)")
            speechRecognizer?.startListening(systemAsrIntent())
        } catch (e: Throwable) { log("系统ASR: ${e.message}"); _asrEngine = 1; startVoskAsr() }
    }

    private fun restartSystemAsr() {
        try { speechRecognizer?.startListening(systemAsrIntent()) } catch (_: Throwable) {}
    }

    private fun stopSystemAsr() { systemAsrContinue = false; try { speechRecognizer?.stopListening() } catch (_: Throwable) {} }

    // ===================== Vosk =====================

    private fun attachEchoCanceler(record: AudioRecord) {
        try {
            aec?.release(); aec = null
            if (android.media.audiofx.AcousticEchoCanceler.isAvailable()) {
                aec = android.media.audiofx.AcousticEchoCanceler.create(record.audioSessionId)
                aec?.enabled = true
                Log.d("MainActivity", "AEC enabled: ${aec?.enabled}")
            }
        } catch (e: Throwable) {
            Log.w("MainActivity", "AEC not available: ${e.message}")
        }
    }

    private fun releaseEchoCanceler() {
        aec?.release(); aec = null
    }

    @SuppressLint("MissingPermission")
    private fun startVoskAsr() {
        val sr = 16000; val bs = max(AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), 4096)
        try { audioRecord = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bs) }
        catch (e: Throwable) { log("录音失败: ${e.message}"); return }
        if (voskModel == null) { log("Vosk模型未就绪"); return }
        recognizer?.close(); recognizer = Recognizer(voskModel, sr.toFloat())
        findInputDevice()?.let { audioRecord?.preferredDevice = it }
        // 启用硬件回声消除
        attachEchoCanceler(audioRecord!!)
        try { audioRecord?.startRecording() } catch (e: Throwable) { log("录音启动失败"); return }
        _recording = true; log("开始录音 (Vosk)")
        asrJob = lifecycleScope.launch(Dispatchers.IO) {
            val buf = ShortArray(2048)
            while (isActive && _recording) {
                val n = audioRecord?.read(buf, 0, buf.size) ?: break; if (n <= 0) continue
                val rms = buf.take(n).fold(0.0) { a, s -> a + s * s } / n
                if (10 * log10(rms + 1e-9) > -35.0) _micHeardVoice = true
                try {
                    if (recognizer?.acceptWaveForm(buf, n) == true) {
                        val t = recognizer?.result?.let { JSONObject(it).optString("text") }.orEmpty().trim()
                        if (t.isNotBlank()) withContext(Dispatchers.Main) { onAsrResult(t) }
                    } else {
                        val p = recognizer?.partialResult?.let { JSONObject(it).optString("partial") }.orEmpty().trim()
                        if (p.isNotBlank()) withContext(Dispatchers.Main) {
                            _currentPartial = p
                        }
                    }
                } catch (_: Throwable) {}
            }
        }
    }

    private fun stopVosk() { asrJob?.cancel(); asrJob = null; releaseEchoCanceler(); try { audioRecord?.stop() } catch (_: Throwable) {}; try { audioRecord?.release() } catch (_: Throwable) {}; audioRecord = null }

    // ===================== Whisper API =====================

    private var _asrProcessStart = 0L
    private val whisperCb = object : WhisperApiAsr.Callback {
        override fun onListening() { _currentPartial = "" }
        override fun onSpeechDetected() { _micHeardVoice = true; _currentPartial = "语音检测中…" }
        override fun onProcessing() { _currentPartial = "正在识别…"; _asrProcessStart = System.currentTimeMillis() }
        override fun onResult(text: String) { if (_asrProcessStart > 0) _asrLatencyMs = System.currentTimeMillis() - _asrProcessStart; onAsrResult(text) }
        override fun onError(msg: String) { log("WhisperAPI: $msg") }
    }

    private fun startWhisperApi(provider: WhisperApiAsr.Provider, key: String) {
        if (key.isBlank()) { log("请设置 ${provider.name} API Key"); return }
        whisperAsr?.close(); whisperAsr = WhisperApiAsr(this, key, provider)
        _recording = true; log("开始录音 (${provider.name})")

        // Ensure Silero VAD model is available; download in background if missing
        if (!WhisperApiAsr.isVadAvailable(this)) {
            log("正在下载语音检测模型…")
            _currentPartial = "下载语音检测模型…"
            lifecycleScope.launch(Dispatchers.IO) {
                val vadFile = WhisperApiAsr.ensureVadDownloaded(this@MainActivity)
                withContext(Dispatchers.Main) {
                    if (vadFile != null) {
                        log("语音检测模型就绪")
                    } else {
                        log("语音检测模型下载失败，使用简易检测")
                    }
                    // Start ASR (will use Silero VAD if downloaded, energy fallback otherwise)
                    whisperAsr?.start(lifecycleScope, whisperCb)
                }
            }
        } else {
            whisperAsr?.start(lifecycleScope, whisperCb)
        }
    }

    // ===================== Local Whisper =====================

    private val localWhisperCb = object : SherpaWhisperAsr.Callback {
        override fun onListening() { _currentPartial = "" }
        override fun onSpeechDetected() { _micHeardVoice = true; _currentPartial = "语音检测中…" }
        override fun onProcessing() { _currentPartial = "正在识别…"; _asrProcessStart = System.currentTimeMillis() }
        override fun onResult(text: String) { if (_asrProcessStart > 0) _asrLatencyMs = System.currentTimeMillis() - _asrProcessStart; onAsrResult(text) }
        override fun onError(msg: String) { log("本地Whisper: $msg") }
        override fun onDownloadProgress(file: String, percent: Int) { _downloadProgress = "$file: $percent%" }
        override fun onModelReady() { _localWhisperReady = true }
    }

    private fun startLocalWhisper() {
        if (!_localWhisperReady) { log("请先下载Whisper模型"); return }
        _recording = true; log("开始录音 (本地Whisper ${selectedWhisperModel().label})")
        sherpaWhisperAsr.start(lifecycleScope, localWhisperCb)
    }

    private fun downloadWhisperModel() {
        if (_localWhisperDownloading) return; _localWhisperDownloading = true
        val m = selectedWhisperModel()
        lifecycleScope.launch {
            if (sherpaWhisperAsr.downloadModel(m, localWhisperCb)) {
                val ok = withContext(Dispatchers.IO) { sherpaWhisperAsr.initModel(m) }
                _localWhisperReady = ok; log(if (ok) "Whisper [${m.label}] 就绪" else "初始化失败")
            }
            _localWhisperDownloading = false; _downloadProgress = ""
        }
    }

    private fun switchWhisperModel(idx: Int) {
        if (_recording) { log("请先停止录音"); return }
        _whisperModelIdx = idx; saveInt("whisper_model_idx", idx)
        val m = selectedWhisperModel()
        if (sherpaWhisperAsr.isModelDownloaded(m)) {
            _localWhisperReady = false
            lifecycleScope.launch(Dispatchers.IO) {
                val ok = sherpaWhisperAsr.initModel(m)
                withContext(Dispatchers.Main) { _localWhisperReady = ok; log(if (ok) "切换 Whisper [${m.label}]" else "初始化失败") }
            }
        } else _localWhisperReady = false
    }

    // ===================== Media Capture =====================

    private fun requestMediaCapture() {
        if (Build.VERSION.SDK_INT < 29) { log("媒体捕获需要 Android 10+"); return }
        // Stop mic ASR to avoid input conflicts
        if (_recording) { stopAllAsr(); log("已关闭麦克风，切换到媒体输入") }
        _mediaCaptureError = ""
        // Start foreground service FIRST (required for MediaProjection)
        val svcIntent = Intent(this, MediaCaptureService::class.java)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(svcIntent) else startService(svcIntent)
        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        requestMediaProjection.launch(mgr.createScreenCaptureIntent())
    }

    private fun stopMediaCapture() {
        val svcIntent = Intent(this, MediaCaptureService::class.java).apply {
            action = MediaCaptureService.ACTION_STOP
        }
        try { startService(svcIntent) } catch (_: Throwable) {}
        try { stopService(Intent(this, MediaCaptureService::class.java)) } catch (_: Throwable) {}
        _mediaCaptureActive = false
        _mediaCaptureStatus = ""
        _mediaSourceApp = ""
        // Close any active paragraph on media capture stop
        _paragraphs.lastOrNull()?.id?.let { closeParagraphById(it) }
        log("媒体捕获已停止")
    }

    // ===================== Floating Window =====================

    private fun launchFloatingWindow() {
        if (!Settings.canDrawOverlays(this)) {
            log("请授予悬浮窗权限")
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            startActivity(intent)
            return
        }
        val svcIntent = Intent(this, FloatingTranslateService::class.java)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(svcIntent) else startService(svcIntent)
        log("悬浮窗已启动")
    }

    // ===================== Settings =====================

    private fun loadSettings() {
        val p = getSharedPreferences("vri_settings", Context.MODE_PRIVATE)
        _openaiKey = p.getString("openai_key", "") ?: ""
        _groqKey = p.getString("groq_key", "") ?: ""
        _whisperModelIdx = p.getInt("whisper_model_idx", 0).coerceIn(0, SherpaWhisperAsr.WhisperModel.entries.size - 1)
        _asrEngine = p.getInt("asr_engine", 0)
        _ttsEngine = p.getInt("tts_engine", 0)
        _edgeVoiceIdx = p.getInt("edge_voice_idx", 0)
        _autoSpeak = p.getBoolean("auto_speak", true)
        _selectedInputId = p.getInt("selected_input_id", 0)
        _selectedOutputId = p.getInt("selected_output_id", 0)
        _translationEngineType = p.getInt("translation_engine", 0)
        _deeplKey = p.getString("deepl_key", "") ?: ""
        _openaiVoiceIdx = p.getInt("openai_voice_idx", 4)
        _localServerUrl = p.getString("local_server_url", "http://192.168.1.100:11434/v1") ?: "http://192.168.1.100:11434/v1"
        _localServerModel = p.getString("local_server_model", "qwen2.5:7b") ?: "qwen2.5:7b"
        _smartFilterEnabled = p.getBoolean("smart_filter_enabled", true)
        _filterFillers = p.getBoolean("filter_fillers", true)
        _filterEcho = p.getBoolean("filter_echo", true)
        _filterNoise = p.getBoolean("filter_noise", true)
        _filterMusic = p.getBoolean("filter_music", true)
        _refineProvider = p.getInt("refine_provider", 0)
        _refineModel = p.getString("refine_model", "llama-3.3-70b-versatile") ?: "llama-3.3-70b-versatile"
        _refineServerUrl = p.getString("refine_server_url", "http://192.168.1.100:11434/v1") ?: "http://192.168.1.100:11434/v1"
    }

    private fun saveKey(k: String, v: String) { getSharedPreferences("vri_settings", Context.MODE_PRIVATE).edit().putString(k, v).apply() }
    private fun saveInt(k: String, v: Int) { getSharedPreferences("vri_settings", Context.MODE_PRIVATE).edit().putInt(k, v).apply() }
    private fun saveBool(k: String, v: Boolean) { getSharedPreferences("vri_settings", Context.MODE_PRIVATE).edit().putBoolean(k, v).apply() }
    private fun selectedWhisperModel() = SherpaWhisperAsr.WhisperModel.entries[_whisperModelIdx]

    // ===================== Sentence Buffer =====================

    /**
     * Entry point for all ASR results.  Applies echo suppression and smart filter,
     * then adds the utterance to the current paragraph and submits for translation.
     *
     * Design: trust ASR boundaries — each onAsrResult call is one complete utterance.
     * No re-segmentation.  Paragraph breaks are detected by silence gaps.
     */
    private fun onAsrResult(text: String) {
        paragraphGapJob?.cancel()

        // TTS echo suppression
        if (_isTtsSpeaking || System.currentTimeMillis() - _ttsSpeakEndTime < TTS_ECHO_GRACE_MS) {
            Log.d("MainActivity", "ASR result suppressed during TTS: ${text.take(30)}")
            return
        }

        // Smart filter
        if (_smartFilterEnabled) {
            val config = AsrTextFilter.FilterConfig(_filterFillers, _filterEcho, _filterNoise, _filterMusic)
            val filtered = AsrTextFilter.filter(text, config) ?: return
            AsrTextFilter.recordText(filtered)
            addSegmentToParagraph(filtered)
        } else {
            addSegmentToParagraph(text)
        }
    }

    /**
     * Add an ASR utterance as a new segment in the current paragraph.
     * Creates a new paragraph if none exists.  Resets the paragraph gap timer.
     */
    private fun addSegmentToParagraph(text: String) {
        _currentPartial = ""

        // Ensure current paragraph exists
        if (_paragraphs.isEmpty() || _paragraphs.last().let {
                it.refinedZh.isNotBlank() || (!it.anyTranslating && it.allDone && it.segments.size >= 8)
            }) {
            _paragraphs.add(Paragraph(id = _nextParagraphId++))
        }

        val paraIdx = _paragraphs.lastIndex
        val para = _paragraphs[paraIdx]
        val paraId = para.id

        // 1. Allocate seqId
        val seqId = translationPipeline.allocateSeqId()

        // 2. Update UI state FIRST — English text visible immediately
        val newSeg = Segment(seqId = seqId, en = text, translating = true)
        _paragraphs[paraIdx] = para.copy(segments = para.segments + newSeg)

        // 3. THEN start translation (callback will find the segment)
        translationPipeline.submitSentence(seqId, paraId, text)

        // History
        translationHistory.append(text, "")

        // Paragraph gap timer
        paragraphGapJob?.cancel()
        paragraphGapJob = lifecycleScope.launch {
            delay(PARAGRAPH_GAP_MS)
            closeParagraphById(paraId)
        }
    }

    /** Close a paragraph by its stable ID: trigger Stage 2 refinement. */
    private fun closeParagraphById(paragraphId: Int) {
        val pi = _paragraphs.indexOfFirst { it.id == paragraphId }
        if (pi < 0) return
        val para = _paragraphs[pi]
        if (para.segments.isEmpty() || para.refining || para.refinedZh.isNotBlank()) return

        _paragraphs[pi] = para.copy(refining = true)
        translationPipeline.closeParagraph(paragraphId)
    }

    // ===================== Translation Engine =====================

    private fun getTranslationEngine(): TranslationEngine {
        if (cachedTransEngineType == _translationEngineType && cachedTransEngine != null) return cachedTransEngine!!
        cachedTransEngine?.close()
        cachedTransEngine = when (_translationEngineType) {
            1 -> LLMTranslation(_openaiKey)
            2 -> LLMTranslation(_groqKey, "https://api.groq.com/openai/v1", "llama-3.3-70b-versatile")
            3 -> DeepLTranslation(_deeplKey)
            4 -> LocalServerTranslation(_localServerUrl, _localServerModel)
            5 -> {
                val engine = OnDeviceTranslation(this, OnDeviceTranslationModel.OPUS_MT_EN_ZH)
                if (OnDeviceTranslationModel.OPUS_MT_EN_ZH.isDownloaded(this)) engine.init()
                engine
            }
            6 -> {
                val engine = OnDeviceTranslation(this, OnDeviceTranslationModel.NLLB_600M_INT8)
                if (OnDeviceTranslationModel.NLLB_600M_INT8.isDownloaded(this)) engine.init()
                engine
            }
            else -> MlKitTranslation(translator)
        }
        cachedTransEngineType = _translationEngineType
        return cachedTransEngine!!
    }

    private fun invalidateTranslationCache() {
        cachedTransEngineType = -1; cachedTransEngine?.close(); cachedTransEngine = null
        if (::translationPipeline.isInitialized) {
            translationPipeline.setEngine(getTranslationEngine())
            translationPipeline.setRefiner(buildRefiner())
        }
    }

    private fun buildRefiner(): TranslationRefiner? {
        return when (_refineProvider) {
            TranslationRefiner.PROVIDER_GROQ -> {
                if (_groqKey.isNotBlank()) TranslationRefiner(_groqKey, TranslationRefiner.providerBaseUrl(TranslationRefiner.PROVIDER_GROQ), _refineModel) else null
            }
            TranslationRefiner.PROVIDER_OPENAI -> {
                if (_openaiKey.isNotBlank()) TranslationRefiner(_openaiKey, TranslationRefiner.providerBaseUrl(TranslationRefiner.PROVIDER_OPENAI), _refineModel) else null
            }
            TranslationRefiner.PROVIDER_ON_DEVICE -> {
                TranslationRefiner("", TranslationRefiner.providerBaseUrl(TranslationRefiner.PROVIDER_ON_DEVICE), _refineModel)
            }
            TranslationRefiner.PROVIDER_LOCAL -> {
                if (_refineServerUrl.isNotBlank()) TranslationRefiner("", _refineServerUrl, _refineModel) else null
            }
            else -> null
        }
    }

    private fun invalidateOpenAiTts() { openAiTts?.close(); openAiTts = null }

    private fun translationReady() = when (_translationEngineType) {
        1 -> _openaiKey.isNotBlank()
        2 -> _groqKey.isNotBlank()
        3 -> _deeplKey.isNotBlank()
        4 -> _localServerUrl.isNotBlank()
        5 -> OnDeviceTranslationModel.OPUS_MT_EN_ZH.isDownloaded(this)
        6 -> OnDeviceTranslationModel.NLLB_600M_INT8.isDownloaded(this)
        else -> _translatorReady
    }

    // ===================== Pipeline Callback =====================

    private val pipelineCallback = object : TranslationPipeline.Callback {
        override fun onTranslationStarted(seqId: Int, en: String) {}

        override fun onTranslationResult(seqId: Int, en: String, zh: String) {
            runOnUiThread {
                // Find paragraph+segment by seqId (unique, handles duplicate English text)
                for (pi in _paragraphs.indices) {
                    val para = _paragraphs[pi]
                    val si = para.segments.indexOfFirst { it.seqId == seqId }
                    if (si >= 0) {
                        val newSegs = para.segments.toMutableList()
                        newSegs[si] = newSegs[si].copy(zh = zh, translating = false)
                        _paragraphs[pi] = para.copy(segments = newSegs)
                        log("$en → $zh")
                        translationHistory.updateLastZh(en, zh)
                        _historySessions.clear()
                        _historySessions.addAll(translationHistory.allSessions())
                        return@runOnUiThread
                    }
                }
            }
        }

        override fun onTranslationError(seqId: Int, en: String, error: String) {
            runOnUiThread {
                for (pi in _paragraphs.indices) {
                    val para = _paragraphs[pi]
                    val si = para.segments.indexOfFirst { it.seqId == seqId }
                    if (si >= 0) {
                        val newSegs = para.segments.toMutableList()
                        newSegs[si] = newSegs[si].copy(zh = "[翻译失败]", translating = false)
                        _paragraphs[pi] = para.copy(segments = newSegs)
                        log("翻译失败: $error")
                        return@runOnUiThread
                    }
                }
            }
        }

        override fun onTtsReady(zh: String) {
            ttsPendingCount.incrementAndGet()
            _ttsQueueSize = ttsPendingCount.get()
            ttsQueue.trySend(zh)
        }

        override fun onLatencyMeasured(translationMs: Long) {
            _transLatencyMs = translationMs
        }

        override fun onParagraphRefined(paragraphId: Int, refinedZh: String) {
            runOnUiThread {
                val pi = _paragraphs.indexOfFirst { it.id == paragraphId }
                if (pi >= 0) {
                    val current = _paragraphs[pi]
                    // Record how many segments existed when refinement was done
                    // so displayZh can fall back to rawZh if new segments arrive later
                    _paragraphs[pi] = current.copy(
                        refinedZh = refinedZh,
                        refining = false,
                        refinedAtCount = current.segments.size
                    )
                    log("段落润色完成 (${current.segments.size}句)")
                }
            }
        }
    }

    // ===================== Utility =====================

    private fun log(msg: String) { Log.d("VRI", msg); runOnUiThread { _logs.add(0, msg); if (_logs.size > 100) _logs.removeLastOrNull() } }
    private fun copyAssetDir(a: String, d: File) {
        if (!d.exists()) d.mkdirs()
        for (n in assets.list(a) ?: emptyArray()) { val p = "$a/$n"; val c = assets.list(p); if (c.isNullOrEmpty()) assets.open(p).use { i -> FileOutputStream(File(d, n)).use { o -> i.copyTo(o) } } else copyAssetDir(p, File(d, n)) }
    }
    private fun clearAll() {
        paragraphGapJob?.cancel()
        _paragraphs.clear(); _nextParagraphId = 0; _currentPartial = ""
        if (::translationPipeline.isInitialized) translationPipeline.reset()
        log("已清空")
    }

    private fun asrReady() = when (_asrEngine) { 0 -> _systemAsrAvailable; 1 -> _voskReady; 2, 5 -> _openaiKey.isNotBlank(); 3 -> _groqKey.isNotBlank(); 4 -> _localWhisperReady; else -> false }
    private fun asrLabel(): String {
        val asr = when (_asrEngine) { 0 -> "系统识别"; 1 -> "Vosk"; 2 -> "OpenAI"; 3 -> "Groq"; 4 -> "Whisper ${selectedWhisperModel().label}"; 5 -> "GPT-4o"; else -> "" }
        val trans = when (_translationEngineType) { 0 -> "MLKit"; 1 -> "GPT"; 2 -> "Groq"; 3 -> "DeepL"; 4 -> "本地LLM"; 5 -> "Opus-MT"; 6 -> "NLLB"; else -> "" }
        val refine = if (_refineProvider > 0) " +润色" else ""
        return "$asr → $trans$refine"
    }

    // ===================== Compose UI =====================

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun AppUI() {
        val drawerState = rememberDrawerState(DrawerValue.Closed)
        val scope = rememberCoroutineScope()

        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = { ModalDrawerSheet(Modifier.width(310.dp)) { DrawerContent() } }
        ) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        navigationIcon = { IconButton({ scope.launch { drawerState.open() } }) { Icon(Icons.Default.Menu, "菜单") } },
                        title = {
                            Column {
                                Text("实时语音翻译", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                                Text(asrLabel(), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                            }
                        },
                        actions = {
                            StatusDot(asrReady(), "ASR"); StatusDot(translationReady(), "翻译"); StatusDot(_ttsReady && _ttsLangOk, "TTS")
                            Spacer(Modifier.width(4.dp))
                            IconButton({ _showHistory = !_showHistory }) {
                                Icon(Icons.Default.History, "历史",
                                    tint = if (_showHistory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                            }
                            IconButton({ clearAll() }) { Icon(Icons.Default.Delete, "清空") }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
                    )
                },
                floatingActionButton = { RecordFAB(_recording) { toggleRecording() } },
                floatingActionButtonPosition = FabPosition.Center
            ) { pad ->
                // Session dialog
                if (_showSessionDialog) {
                    AlertDialog(
                        onDismissRequest = { _showSessionDialog = false; translationHistory.newSession() },
                        title = { Text("会话选择") },
                        text = { Text("继续上次会话还是新建？") },
                        confirmButton = {
                            TextButton({
                                _showSessionDialog = false
                                translationHistory.newSession()
                                _historySessions.clear()
                                _historySessions.addAll(translationHistory.allSessions())
                            }) { Text("新建会话") }
                        },
                        dismissButton = {
                            TextButton({
                                _showSessionDialog = false
                                translationHistory.continueOrNew()
                                // Load last session's entries into a paragraph
                                val lastSession = translationHistory.currentSession()
                                if (lastSession != null && lastSession.entries.isNotEmpty()) {
                                    _paragraphs.clear()
                                    val segs = lastSession.entries.map { e -> Segment(en = e.en, zh = e.zh, translating = false) }
                                    _paragraphs.add(Paragraph(id = _nextParagraphId++, segments = segs))
                                }
                            }) { Text("继续上次") }
                        }
                    )
                }

                Column(Modifier.fillMaxSize().padding(pad)) {
                    // 延迟指标栏
                    if (_recording || _ttsQueueSize > 0) {
                        val dimColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f), modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                                // 流水线延迟
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    if (_asrLatencyMs > 0) Text("ASR: ${_asrLatencyMs}ms", fontSize = 10.sp, color = dimColor)
                                    if (_transLatencyMs > 0) Text("翻译: ${_transLatencyMs}ms", fontSize = 10.sp, color = dimColor)
                                    if (_refineLatencyMs > 0) Text("润色: ${_refineLatencyMs}ms", fontSize = 10.sp, color = dimColor)
                                    if (_ttsLatencyMs > 0) Text("TTS: ${_ttsLatencyMs}ms", fontSize = 10.sp, color = dimColor)
                                    if (_ttsQueueSize > 0) Text("队列: $_ttsQueueSize", fontSize = 10.sp, color = Color(0xFFEF5350))
                                    if (_ttsSpeedPct > 0) Text("语速: +${_ttsSpeedPct}%", fontSize = 10.sp, color = MaterialTheme.colorScheme.primary)
                                }
                                // 设备指标
                                if (_cpuUsagePct > 0f || _memoryUsageMB > 0) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        Text("CPU: ${"%.1f".format(_cpuUsagePct)}%", fontSize = 10.sp, color = dimColor)
                                        Text("内存: ${_memoryUsageMB}MB", fontSize = 10.sp, color = dimColor)
                                        if (_batteryPct > 0) Text("电池: ${_batteryPct}%", fontSize = 10.sp, color = if (_batteryPct <= 15) Color(0xFFEF5350) else dimColor)
                                        if (_batteryTempC > 0f) Text("温度: ${"%.1f".format(_batteryTempC)}°C", fontSize = 10.sp, color = if (_batteryTempC >= 40f) Color(0xFFEF5350) else dimColor)
                                    }
                                }
                            }
                        }
                    }
                    // 媒体捕获状态栏
                    if (_mediaCaptureActive || _mediaCaptureError.isNotBlank()) {
                        val isError = _mediaCaptureError.isNotBlank() && !_mediaCaptureActive
                        val bgColor = if (isError) Color(0xFFEF5350).copy(alpha = 0.12f) else Color(0xFF4CAF50).copy(alpha = 0.12f)
                        val iconColor = if (isError) Color(0xFFEF5350) else Color(0xFF4CAF50)
                        Surface(color = bgColor, modifier = Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(if (isError) Icons.Default.ErrorOutline else Icons.Default.Audiotrack, null, Modifier.size(16.dp), tint = iconColor)
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    if (isError) {
                                        Text("媒体捕获失败", fontSize = 13.sp, color = Color(0xFFD32F2F))
                                        Text(_mediaCaptureError, fontSize = 11.sp, color = Color(0xFFD32F2F).copy(alpha = 0.7f))
                                    } else {
                                        Text(
                                            if (_mediaCaptureStatus.isNotBlank()) _mediaCaptureStatus else "媒体转译中",
                                            fontSize = 13.sp, color = Color(0xFF388E3C)
                                        )
                                        Row {
                                            Text("输入: 媒体音频", fontSize = 11.sp, color = Color(0xFF388E3C).copy(alpha = 0.7f))
                                            if (_mediaSourceApp.isNotBlank()) {
                                                Text(" · $_mediaSourceApp", fontSize = 11.sp, color = Color(0xFF388E3C).copy(alpha = 0.7f))
                                            }
                                        }
                                    }
                                }
                                if (_mediaCaptureActive) {
                                    TextButton({ stopMediaCapture() }) { Text("停止", fontSize = 12.sp) }
                                } else if (isError) {
                                    TextButton({ _mediaCaptureError = "" }) { Text("关闭", fontSize = 12.sp) }
                                }
                            }
                        }
                    }
                    if (_showHistory) HistoryArea(Modifier.weight(1f))
                    else ConversationArea(Modifier.weight(1f))
                    Spacer(Modifier.height(80.dp))
                }
            }
        }
    }

    @Composable
    private fun StatusDot(ok: Boolean, label: String) {
        val c by animateColorAsState(if (ok) Color(0xFF4CAF50) else Color(0xFFBDBDBD))
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 4.dp)) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(c))
            Text(label, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
        }
    }

    // ===================== Drawer =====================

    @Composable
    private fun DrawerContent() {
        Column(Modifier.fillMaxHeight().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text("设置", fontWeight = FontWeight.Bold, fontSize = 22.sp)
            Spacer(Modifier.height(16.dp))

            // 1) 基本设置
            CollapsibleSection("基本设置", Icons.Default.Settings) {
                SettingSwitch("自动播报翻译", _autoSpeak) { _autoSpeak = it; saveBool("auto_speak", it) }
            }

            // 2) 语音识别
            CollapsibleSection("语音识别引擎", Icons.Default.Mic) {
                AsrOption(0, "Android 系统", if (_systemAsrAvailable) "需联网" else "不可用")
                AsrOption(1, "Vosk 离线", "离线，准确率一般")
                AsrOption(2, "OpenAI Whisper", "API · \$0.006/min")
                AsrOption(3, "Groq Whisper", "API · 免费额度")
                AsrOption(4, "本地 Whisper", "离线 · 精度高")
                AsrOption(5, "GPT-4o 语音", "OpenAI API · 高精度")

                AnimatedVisibility(_asrEngine == 1) { VoskModelPanel() }
                AnimatedVisibility(_asrEngine == 2 || _asrEngine == 5) { ApiKeyInput("OpenAI API Key", _openaiKey) { _openaiKey = it; saveKey("openai_key", it); invalidateTranslationCache() } }
                AnimatedVisibility(_asrEngine == 3) { ApiKeyInput("Groq API Key", _groqKey) { _groqKey = it; saveKey("groq_key", it); invalidateTranslationCache() } }
                AnimatedVisibility(_asrEngine == 4) { WhisperModelSelector() }
            }

            // 3) 翻译引擎
            CollapsibleSection("翻译引擎", Icons.Default.Language) {
                TransOption(0, "MLKit 离线", "离线翻译，速度中等")
                TransOption(1, "OpenAI GPT", "API · 高质量")
                TransOption(2, "Groq LLM", "API · 免费额度 · 极速")
                TransOption(3, "DeepL", "API · 高质量")
                TransOption(4, "本地服务器", "Ollama / LM Studio · 离线")
                TransOption(5, "Opus-MT 离线", "Helsinki-NLP · 轻量 · ~250MB")
                TransOption(6, "NLLB-600M 离线", "Meta · 高质量 · ~1.1GB")

                AnimatedVisibility(_translationEngineType == 4) {
                    Column(Modifier.padding(start = 4.dp, top = 6.dp)) {
                        OutlinedTextField(_localServerUrl,
                            { _localServerUrl = it; saveKey("local_server_url", it); invalidateTranslationCache() },
                            label = { Text("服务器地址", fontSize = 12.sp) }, singleLine = true,
                            placeholder = { Text("http://192.168.1.100:11434/v1") },
                            modifier = Modifier.fillMaxWidth(), textStyle = LocalTextStyle.current.copy(fontSize = 13.sp))
                        Spacer(Modifier.height(4.dp))
                        OutlinedTextField(_localServerModel,
                            { _localServerModel = it; saveKey("local_server_model", it); invalidateTranslationCache() },
                            label = { Text("模型名称", fontSize = 12.sp) }, singleLine = true,
                            placeholder = { Text("qwen2.5:7b") },
                            modifier = Modifier.fillMaxWidth(), textStyle = LocalTextStyle.current.copy(fontSize = 13.sp))
                        Text("支持 Ollama、LM Studio 等 OpenAI 兼容 API", fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                            modifier = Modifier.padding(top = 4.dp))
                    }
                }

                // Offline model download UI
                AnimatedVisibility(_translationEngineType == 5 || _translationEngineType == 6) {
                    OfflineTransModelPanel()
                }

                AnimatedVisibility(_translationEngineType == 3) {
                    ApiKeyInput("DeepL API Key", _deeplKey) { _deeplKey = it; saveKey("deepl_key", it); invalidateTranslationCache() }
                }
                if (_translationEngineType == 1 && _openaiKey.isBlank()) {
                    Text("请在语音识别中设置 OpenAI API Key", fontSize = 11.sp, color = Color(0xFFEF5350),
                        modifier = Modifier.padding(start = 8.dp, top = 4.dp))
                }
                if (_translationEngineType == 2 && _groqKey.isBlank()) {
                    Text("请在语音识别中设置 Groq API Key", fontSize = 11.sp, color = Color(0xFFEF5350),
                        modifier = Modifier.padding(start = 8.dp, top = 4.dp))
                }

            }

            // 3.5) AI 润色
            CollapsibleSection("AI 润色", Icons.Default.AutoAwesome) {
                Text("翻译后用 LLM 结合上下文修正整段话，再送入 TTS 播报", fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                    modifier = Modifier.padding(bottom = 6.dp))

                RefineProviderOption(TranslationRefiner.PROVIDER_OFF, "关闭", "不进行润色")
                RefineProviderOption(TranslationRefiner.PROVIDER_GROQ, "Groq API", "极速 · 免费额度 · 推荐")
                RefineProviderOption(TranslationRefiner.PROVIDER_OPENAI, "OpenAI API", "高质量")
                RefineProviderOption(TranslationRefiner.PROVIDER_ON_DEVICE, "手机本机", "Termux + Ollama · 完全离线")
                RefineProviderOption(TranslationRefiner.PROVIDER_LOCAL, "局域网服务器", "PC/Mac 上的 Ollama / LM Studio")

                // Key warnings
                if (_refineProvider == TranslationRefiner.PROVIDER_GROQ && _groqKey.isBlank()) {
                    Text("请在语音识别中设置 Groq API Key", fontSize = 11.sp, color = Color(0xFFEF5350),
                        modifier = Modifier.padding(start = 8.dp, top = 4.dp))
                }
                if (_refineProvider == TranslationRefiner.PROVIDER_OPENAI && _openaiKey.isBlank()) {
                    Text("请在语音识别中设置 OpenAI API Key", fontSize = 11.sp, color = Color(0xFFEF5350),
                        modifier = Modifier.padding(start = 8.dp, top = 4.dp))
                }

                // Model selection per provider
                AnimatedVisibility(_refineProvider == TranslationRefiner.PROVIDER_GROQ) {
                    Column(Modifier.padding(start = 8.dp, top = 6.dp)) {
                        Text("模型选择", fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        TranslationRefiner.GROQ_MODELS.forEach { preset ->
                            SmallRadio("${preset.label}  ${preset.desc}", _refineModel == preset.id) {
                                _refineModel = preset.id; saveKey("refine_model", preset.id); invalidateTranslationCache()
                            }
                        }
                    }
                }
                AnimatedVisibility(_refineProvider == TranslationRefiner.PROVIDER_OPENAI) {
                    Column(Modifier.padding(start = 8.dp, top = 6.dp)) {
                        Text("模型选择", fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        TranslationRefiner.OPENAI_MODELS.forEach { preset ->
                            SmallRadio("${preset.label}  ${preset.desc}", _refineModel == preset.id) {
                                _refineModel = preset.id; saveKey("refine_model", preset.id); invalidateTranslationCache()
                            }
                        }
                    }
                }
                AnimatedVisibility(_refineProvider == TranslationRefiner.PROVIDER_ON_DEVICE) {
                    Column(Modifier.padding(start = 8.dp, top = 6.dp)) {
                        Text("手机端推荐模型", fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        TranslationRefiner.ON_DEVICE_MODELS.forEach { preset ->
                            SmallRadio("${preset.label}  ${preset.desc}", _refineModel == preset.id) {
                                _refineModel = preset.id; saveKey("refine_model", preset.id); invalidateTranslationCache()
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.padding(10.dp)) {
                                Text("部署步骤", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                Text("1. 安装 Termux (F-Droid)", fontSize = 11.sp)
                                Text("2. pkg install ollama", fontSize = 11.sp, fontStyle = FontStyle.Italic)
                                Text("3. ollama serve &", fontSize = 11.sp, fontStyle = FontStyle.Italic)
                                Text("4. ollama pull ${_refineModel}", fontSize = 11.sp, fontStyle = FontStyle.Italic)
                                Text("模型会自动从 localhost:11434 连接", fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                    modifier = Modifier.padding(top = 4.dp))
                                Text("Qwen2.5 0.5B/1.5B 中文能力最佳，推荐 8GB+ 内存手机", fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        OutlinedTextField(_refineModel,
                            { _refineModel = it; saveKey("refine_model", it); invalidateTranslationCache() },
                            label = { Text("自定义模型名称", fontSize = 12.sp) }, singleLine = true,
                            modifier = Modifier.fillMaxWidth(), textStyle = LocalTextStyle.current.copy(fontSize = 13.sp))
                    }
                }
                AnimatedVisibility(_refineProvider == TranslationRefiner.PROVIDER_LOCAL) {
                    Column(Modifier.padding(start = 8.dp, top = 6.dp)) {
                        Text("常用模型", fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        TranslationRefiner.LOCAL_MODELS.forEach { preset ->
                            SmallRadio("${preset.label}  ${preset.desc}", _refineModel == preset.id) {
                                _refineModel = preset.id; saveKey("refine_model", preset.id); invalidateTranslationCache()
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        OutlinedTextField(_refineServerUrl,
                            { _refineServerUrl = it; saveKey("refine_server_url", it); invalidateTranslationCache() },
                            label = { Text("服务器地址", fontSize = 12.sp) }, singleLine = true,
                            placeholder = { Text("http://192.168.1.100:11434/v1") },
                            modifier = Modifier.fillMaxWidth(), textStyle = LocalTextStyle.current.copy(fontSize = 13.sp))
                        Spacer(Modifier.height(4.dp))
                        OutlinedTextField(_refineModel,
                            { _refineModel = it; saveKey("refine_model", it); invalidateTranslationCache() },
                            label = { Text("自定义模型名称", fontSize = 12.sp) }, singleLine = true,
                            modifier = Modifier.fillMaxWidth(), textStyle = LocalTextStyle.current.copy(fontSize = 13.sp))
                        Text("支持 Ollama、LM Studio 等 OpenAI 兼容 API", fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                            modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }

            // 4) 语音合成
            CollapsibleSection("语音合成引擎", Icons.AutoMirrored.Filled.VolumeUp) {
                TtsOption(0, "Edge 神经语音", "微软，接近真人")
                TtsOption(1, "系统 TTS", "Google 系统语音")
                TtsOption(2, "Google 翻译", "基础音质")
                TtsOption(3, "OpenAI TTS", "高质量 · API")
                AnimatedVisibility(_ttsEngine == 3) {
                    Column(Modifier.padding(start = 12.dp, top = 6.dp)) {
                        Text("语音角色", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        OpenAiTts.VOICES.forEachIndexed { i, (_, l) ->
                            SmallRadio(l, _openaiVoiceIdx == i) { _openaiVoiceIdx = i; saveInt("openai_voice_idx", i); log("OpenAI语音: $l") }
                        }
                        if (_openaiKey.isBlank()) {
                            Text("需设置 OpenAI API Key", fontSize = 11.sp, color = Color(0xFFEF5350),
                                modifier = Modifier.padding(start = 8.dp, top = 4.dp))
                        }
                    }
                }
                AnimatedVisibility(_ttsEngine == 0) {
                    Column(Modifier.padding(start = 12.dp, top = 6.dp)) {
                        Text("语音角色", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        EdgeTts.ZH_VOICES.forEachIndexed { i, (_, l) ->
                            SmallRadio(l, _edgeVoiceIdx == i) { _edgeVoiceIdx = i; saveInt("edge_voice_idx", i); log("语音: $l") }
                        }
                    }
                }
            }

            // 5) 音频设备
            CollapsibleSection("音频设备", Icons.Default.Headphones) {
                Text("输入设备", fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                SmallRadio("默认麦克风", _selectedInputId == 0 && !_mediaCaptureActive) {
                    if (_mediaCaptureActive) { stopMediaCapture() }
                    _selectedInputId = 0; saveInt("selected_input_id", 0)
                }
                _inputDevices.forEach { d ->
                    SmallRadio(deviceLabel(d), _selectedInputId == d.id && !_mediaCaptureActive) {
                        if (_mediaCaptureActive) { stopMediaCapture() }
                        _selectedInputId = d.id; saveInt("selected_input_id", d.id)
                    }
                }
                // Media audio source option
                if (Build.VERSION.SDK_INT >= 29) {
                    val mediaLabel = if (_mediaCaptureActive && _mediaSourceApp.isNotBlank())
                        "媒体音频 ($_mediaSourceApp)" else "媒体音频 (系统播放)"
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                            .clickable {
                                if (_mediaCaptureActive) { stopMediaCapture() }
                                else { requestMediaCapture() }
                            }
                            .background(if (_mediaCaptureActive) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f) else Color.Transparent)
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(_mediaCaptureActive, {
                            if (_mediaCaptureActive) { stopMediaCapture() }
                            else { requestMediaCapture() }
                        }, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Column {
                            Text(mediaLabel, fontSize = 12.sp, fontWeight = if (_mediaCaptureActive) FontWeight.SemiBold else FontWeight.Normal)
                            Text("捕获后台播放的媒体音频 · Android 10+", fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f))
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text("输出设备（扬声器）", fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                SmallRadio("默认", _selectedOutputId == 0) { _selectedOutputId = 0; saveInt("selected_output_id", 0) }
                _outputDevices.forEach { d ->
                    SmallRadio(deviceLabel(d), _selectedOutputId == d.id) { _selectedOutputId = d.id; saveInt("selected_output_id", d.id) }
                }
                Spacer(Modifier.height(6.dp))
                TextButton(onClick = { refreshAudioDevices(); log("设备列表已刷新") }) {
                    Icon(Icons.Default.Refresh, null, Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("刷新设备列表", fontSize = 12.sp)
                }
            }

            // 6) 智能过滤
            CollapsibleSection("智能过滤", Icons.Default.FilterList) {
                SettingSwitch("启用智能过滤", _smartFilterEnabled) {
                    _smartFilterEnabled = it; saveBool("smart_filter_enabled", it)
                    if (!it) AsrTextFilter.reset()
                }
                AnimatedVisibility(_smartFilterEnabled) {
                    Column(Modifier.padding(start = 8.dp)) {
                        SettingSwitch("过滤语气词 (um, uh, like...)", _filterFillers) { _filterFillers = it; saveBool("filter_fillers", it) }
                        SettingSwitch("过滤回声/重复", _filterEcho) {
                            _filterEcho = it; saveBool("filter_echo", it)
                            if (!it) AsrTextFilter.reset()
                        }
                        SettingSwitch("过滤噪音/短语", _filterNoise) { _filterNoise = it; saveBool("filter_noise", it) }
                        SettingSwitch("过滤音乐/歌声", _filterMusic) { _filterMusic = it; saveBool("filter_music", it) }
                    }
                }
                Text("智能忽略语气词、回声、噪音和音乐，提高翻译质量",
                    fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                    modifier = Modifier.padding(top = 4.dp))
            }

            // 7) 媒体转译
            CollapsibleSection("媒体转译", Icons.Default.Audiotrack) {
                Text(
                    "捕获后台播放的媒体或通话音频进行实时翻译（需 Android 10+）",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                Spacer(Modifier.height(8.dp))
                if (Build.VERSION.SDK_INT < 29) {
                    Text("当前系统版本不支持媒体捕获", fontSize = 12.sp, color = Color(0xFFEF5350))
                } else if (_mediaCaptureActive) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("正在捕获中", fontSize = 13.sp, color = Color(0xFF4CAF50))
                        Spacer(Modifier.weight(1f))
                        TextButton({ stopMediaCapture() }) { Text("停止") }
                    }
                } else {
                    Button(onClick = { requestMediaCapture() },
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)) {
                        Icon(Icons.Default.PlayArrow, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("开始媒体转译", fontSize = 13.sp)
                    }
                }
            }

            // 8) 悬浮窗
            CollapsibleSection("悬浮窗模式", Icons.Default.Layers) {
                Text(
                    "启动悬浮窗后可切换到其他应用，继续后台翻译",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                Spacer(Modifier.height(8.dp))
                if (FloatingTranslateService.isRunning) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("悬浮窗运行中", fontSize = 13.sp, color = Color(0xFF4CAF50))
                        Spacer(Modifier.weight(1f))
                        TextButton({ stopService(Intent(this@MainActivity, FloatingTranslateService::class.java)) }) { Text("停止") }
                    }
                } else {
                    Button(onClick = { launchFloatingWindow() },
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)) {
                        Icon(Icons.Default.Layers, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("启动悬浮窗", fontSize = 13.sp)
                    }
                }
            }

            // 9) API 密钥管理
            CollapsibleSection("API 密钥管理", Icons.Default.VpnKey) {
                ApiKeyManagerPanel()
            }

            // 10) API 连通测试
            CollapsibleSection("API 连通测试", Icons.Default.Wifi) {
                ApiTestPanel()
            }

            // 11) 日志
            @Suppress("DEPRECATION")
            CollapsibleSection("系统日志", Icons.Default.List) {
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 200.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)).padding(8.dp)
                ) {
                    if (_logs.isEmpty()) Text("暂无日志", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f))
                    else _logs.take(30).forEach { Text(it, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)) }
                }
            }

            Spacer(Modifier.height(40.dp))
        }
    }

    // ---- Collapsible Section ----

    @Composable
    private fun CollapsibleSection(
        title: String,
        icon: ImageVector,
        defaultExpanded: Boolean = false,
        content: @Composable ColumnScope.() -> Unit
    ) {
        var expanded by remember { mutableStateOf(defaultExpanded) }
        val rotation by animateFloatAsState(if (expanded) 180f else 0f)

        Column(Modifier.padding(vertical = 4.dp)) {
            Surface(
                onClick = { expanded = !expanded },
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                shape = RoundedCornerShape(10.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Icon(Icons.Default.KeyboardArrowDown, null, Modifier.size(20.dp).rotate(rotation),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
                }
            }
            AnimatedVisibility(visible = expanded) {
                Column(Modifier.padding(start = 8.dp, top = 6.dp, end = 4.dp, bottom = 4.dp)) { content() }
            }
        }
    }

    // ---- Composable helpers ----

    @Composable
    private fun SettingSwitch(title: String, checked: Boolean, on: (Boolean) -> Unit) {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Switch(checked, on)
        }
    }

    @Composable
    private fun AsrOption(id: Int, title: String, desc: String) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                .clickable { if (_recording) log("请先停止录音") else { _asrEngine = id; saveInt("asr_engine", id) } }
                .background(if (_asrEngine == id) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f) else Color.Transparent)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(_asrEngine == id, { if (_recording) log("请先停止录音") else { _asrEngine = id; saveInt("asr_engine", id) } }, Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Column {
                Text(title, fontSize = 14.sp, fontWeight = if (_asrEngine == id) FontWeight.SemiBold else FontWeight.Normal)
                Text(desc, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
            }
        }
    }

    @Composable
    private fun TtsOption(id: Int, title: String, desc: String) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                .clickable { _ttsEngine = id; saveInt("tts_engine", id) }
                .background(if (_ttsEngine == id) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f) else Color.Transparent)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(_ttsEngine == id, { _ttsEngine = id; saveInt("tts_engine", id) }, Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Column {
                Text(title, fontSize = 14.sp, fontWeight = if (_ttsEngine == id) FontWeight.SemiBold else FontWeight.Normal)
                Text(desc, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
            }
        }
    }

    @Composable
    private fun TransOption(id: Int, title: String, desc: String) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                .clickable { _translationEngineType = id; saveInt("translation_engine", id); invalidateTranslationCache() }
                .background(if (_translationEngineType == id) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f) else Color.Transparent)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(_translationEngineType == id, { _translationEngineType = id; saveInt("translation_engine", id); invalidateTranslationCache() }, Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Column {
                Text(title, fontSize = 14.sp, fontWeight = if (_translationEngineType == id) FontWeight.SemiBold else FontWeight.Normal)
                Text(desc, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
            }
        }
    }

    @Composable
    private fun RefineProviderOption(id: Int, title: String, desc: String) {
        val selected = _refineProvider == id
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                .clickable {
                    _refineProvider = id; saveInt("refine_provider", id)
                    // Set default model for provider
                    if (id != TranslationRefiner.PROVIDER_OFF) {
                        val defaultModel = TranslationRefiner.defaultModel(id)
                        _refineModel = defaultModel; saveKey("refine_model", defaultModel)
                    }
                    invalidateTranslationCache()
                }
                .background(if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f) else Color.Transparent)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(selected, {
                _refineProvider = id; saveInt("refine_provider", id)
                if (id != TranslationRefiner.PROVIDER_OFF) {
                    val defaultModel = TranslationRefiner.defaultModel(id)
                    _refineModel = defaultModel; saveKey("refine_model", defaultModel)
                }
                invalidateTranslationCache()
            }, Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Column {
                Text(title, fontSize = 14.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                Text(desc, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
            }
        }
    }

    @Composable
    private fun SmallRadio(label: String, selected: Boolean, onClick: () -> Unit) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).clickable(onClick = onClick)
                .background(if (selected) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f) else Color.Transparent)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(selected, onClick, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(label, fontSize = 12.sp)
        }
    }

    @Composable
    private fun ApiKeyInput(label: String, value: String, on: (String) -> Unit) {
        Column(Modifier.padding(start = 4.dp, top = 6.dp)) {
            OutlinedTextField(value, on, label = { Text(label, fontSize = 12.sp) },
                visualTransformation = PasswordVisualTransformation(), singleLine = true,
                modifier = Modifier.fillMaxWidth(), textStyle = LocalTextStyle.current.copy(fontSize = 13.sp))
            Text(if (value.isBlank()) "请输入 API Key" else "已设置", fontSize = 11.sp,
                color = if (value.isBlank()) Color(0xFFEF5350) else Color(0xFF4CAF50),
                modifier = Modifier.padding(start = 4.dp, top = 2.dp))
        }
    }

    // ===================== API Key Manager =====================

    @Composable
    private fun ApiKeyManagerPanel() {
        Column {
            Text("当前已配置的 API 密钥", fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            Spacer(Modifier.height(8.dp))

            // Show existing keys
            data class KeyEntry(val name: String, val prefKey: String, val value: String, val onSet: (String) -> Unit)
            val keys = listOf(
                KeyEntry("OpenAI", "openai_key", _openaiKey) { _openaiKey = it; saveKey("openai_key", it); invalidateTranslationCache() },
                KeyEntry("Groq", "groq_key", _groqKey) { _groqKey = it; saveKey("groq_key", it); invalidateTranslationCache() },
                KeyEntry("DeepL", "deepl_key", _deeplKey) { _deeplKey = it; saveKey("deepl_key", it); invalidateTranslationCache() },
            )

            keys.forEach { entry ->
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
                ) {
                    Row(
                        Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(entry.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            if (entry.value.isNotBlank()) {
                                val masked = entry.value.take(4) + "****" + entry.value.takeLast(4)
                                Text(masked, fontSize = 11.sp, color = Color(0xFF4CAF50))
                            } else {
                                Text("未设置", fontSize = 11.sp, color = Color(0xFFEF5350))
                            }
                        }
                        if (entry.value.isNotBlank()) {
                            TextButton(onClick = {
                                entry.onSet("")
                                log("已删除 ${entry.name} API Key")
                            }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                                Text("删除", fontSize = 12.sp, color = Color(0xFFEF5350))
                            }
                        }
                    }
                }
            }

            // Server configs
            Spacer(Modifier.height(10.dp))
            Text("服务器配置", fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            Spacer(Modifier.height(4.dp))
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
            ) {
                Column(Modifier.padding(10.dp)) {
                    Text("本地翻译服务器", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text(_localServerUrl, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    Text("模型: $_localServerModel", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                }
            }
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
            ) {
                Column(Modifier.padding(10.dp)) {
                    Text("AI 润色服务器", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text(_refineServerUrl, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    Text("模型: $_refineModel", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                }
            }

            // Add new key section
            Spacer(Modifier.height(12.dp))
            Text("添加/修改 API 密钥", fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            Spacer(Modifier.height(4.dp))

            var selectedKeyType by remember { mutableStateOf(0) }
            val keyTypes = listOf("OpenAI", "Groq", "DeepL")
            Row(Modifier.fillMaxWidth()) {
                keyTypes.forEachIndexed { i, label ->
                    FilterChip(
                        selected = selectedKeyType == i,
                        onClick = { selectedKeyType = i },
                        label = { Text(label, fontSize = 11.sp) },
                        modifier = Modifier.padding(end = 6.dp)
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            OutlinedTextField(
                _newKeyValue, { _newKeyValue = it },
                label = { Text("${keyTypes[selectedKeyType]} API Key", fontSize = 12.sp) },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                textStyle = LocalTextStyle.current.copy(fontSize = 13.sp)
            )
            Spacer(Modifier.height(6.dp))
            Button(
                onClick = {
                    if (_newKeyValue.isNotBlank()) {
                        when (selectedKeyType) {
                            0 -> { _openaiKey = _newKeyValue; saveKey("openai_key", _newKeyValue) }
                            1 -> { _groqKey = _newKeyValue; saveKey("groq_key", _newKeyValue) }
                            2 -> { _deeplKey = _newKeyValue; saveKey("deepl_key", _newKeyValue) }
                        }
                        invalidateTranslationCache()
                        log("已保存 ${keyTypes[selectedKeyType]} API Key")
                        _newKeyValue = ""
                    }
                },
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                enabled = _newKeyValue.isNotBlank()
            ) {
                Icon(Icons.Default.Done, null, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("保存", fontSize = 13.sp)
            }
        }
    }

    // ===================== API Test Panel =====================

    @Composable
    private fun ApiTestPanel() {
        Column {
            Text("测试各 API 的连通性，逐步检查 DNS → 连接 → 认证 → 功能",
                fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f))
            Spacer(Modifier.height(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = { runAllApiTests() },
                    enabled = !_apiTestRunning,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    if (_apiTestRunning) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    } else {
                        Icon(Icons.Default.PlayArrow, null, Modifier.size(16.dp))
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(if (_apiTestRunning) "测试中..." else "测试所有 API", fontSize = 13.sp)
                }
                if (_apiTestProgress.isNotBlank()) {
                    Spacer(Modifier.width(10.dp))
                    Text(_apiTestProgress, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                }
            }

            // Individual test buttons
            Spacer(Modifier.height(8.dp))
            Text("单独测试", fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            Spacer(Modifier.height(4.dp))

            @Composable
            fun SingleTestButton(label: String, available: Boolean, onClick: () -> Unit) {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = onClick,
                        enabled = !_apiTestRunning && available,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
                    ) {
                        Icon(Icons.Default.PlayCircle, null, Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(label, fontSize = 12.sp)
                    }
                    if (!available) {
                        Text("(未配置)", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f))
                    }
                }
            }

            SingleTestButton("OpenAI 翻译/ASR/TTS", _openaiKey.isNotBlank()) {
                runSingleApiTest("openai")
            }
            SingleTestButton("Groq 翻译/ASR", _groqKey.isNotBlank()) {
                runSingleApiTest("groq")
            }
            SingleTestButton("DeepL 翻译", _deeplKey.isNotBlank()) {
                runSingleApiTest("deepl")
            }
            SingleTestButton("Edge TTS", true) {
                runSingleApiTest("edge")
            }
            SingleTestButton("Google 翻译 TTS", true) {
                runSingleApiTest("google_tts")
            }
            SingleTestButton("本地服务器", _localServerUrl.isNotBlank()) {
                runSingleApiTest("local")
            }

            // Results
            if (_apiTestResults.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text("测试结果", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))

                _apiTestResults.forEach { result ->
                    ApiTestResultCard(result)
                    Spacer(Modifier.height(6.dp))
                }
            }
        }
    }

    @Composable
    private fun ApiTestResultCard(result: ApiTestManager.ApiTestResult) {
        val bgColor = if (result.overallSuccess)
            Color(0xFF4CAF50).copy(alpha = 0.08f)
        else
            Color(0xFFEF5350).copy(alpha = 0.08f)
        val borderColor = if (result.overallSuccess) Color(0xFF4CAF50) else Color(0xFFEF5350)

        Surface(
            color = bgColor,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (result.overallSuccess) Icons.Default.CheckCircle else Icons.Default.Error,
                        null, Modifier.size(18.dp),
                        tint = borderColor
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        result.apiName, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        color = borderColor
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        if (result.overallSuccess) "通过" else "失败",
                        fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        color = borderColor
                    )
                }
                Spacer(Modifier.height(6.dp))

                result.steps.forEach { step ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 1.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        val stepIcon = if (step.success) "✓" else "✗"
                        val stepColor = if (step.success) Color(0xFF4CAF50) else Color(0xFFEF5350)
                        Text(stepIcon, fontSize = 12.sp, color = stepColor, fontWeight = FontWeight.Bold,
                            modifier = Modifier.width(16.dp))
                        Text("${step.step}: ", fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                        Column(Modifier.weight(1f)) {
                            Text(step.detail, fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        }
                        if (step.durationMs > 0) {
                            Text("${step.durationMs}ms", fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f))
                        }
                    }
                }

                // Show blocked step info
                val failed = result.failedStep
                if (failed != null) {
                    Spacer(Modifier.height(4.dp))
                    Surface(
                        color = Color(0xFFEF5350).copy(alpha = 0.1f),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Warning, null, Modifier.size(14.dp), tint = Color(0xFFD32F2F))
                            Spacer(Modifier.width(6.dp))
                            Text("阻断于: ${failed.step} — ${failed.detail}",
                                fontSize = 11.sp, color = Color(0xFFD32F2F))
                        }
                    }
                }
            }
        }
    }

    private fun runAllApiTests() {
        if (_apiTestRunning) return
        _apiTestRunning = true
        _apiTestResults.clear()
        _apiTestProgress = "准备中..."

        lifecycleScope.launch {
            val tests = mutableListOf<suspend () -> ApiTestManager.ApiTestResult>()

            // Always test free services
            tests.add { apiTestManager.testEdgeTts() }
            tests.add { apiTestManager.testGoogleTts() }

            // Test configured APIs
            if (_openaiKey.isNotBlank()) {
                tests.add { apiTestManager.testOpenAiTranslation(_openaiKey) }
                tests.add { apiTestManager.testOpenAiWhisper(_openaiKey) }
                tests.add { apiTestManager.testOpenAiTts(_openaiKey) }
            }
            if (_groqKey.isNotBlank()) {
                tests.add { apiTestManager.testGroqTranslation(_groqKey) }
                tests.add { apiTestManager.testGroqWhisper(_groqKey) }
            }
            if (_deeplKey.isNotBlank()) {
                tests.add { apiTestManager.testDeepL(_deeplKey) }
            }
            if (_localServerUrl.isNotBlank()) {
                tests.add { apiTestManager.testLocalServer(_localServerUrl, _localServerModel) }
            }

            for ((i, test) in tests.withIndex()) {
                _apiTestProgress = "${i + 1}/${tests.size}"
                try {
                    val result = test()
                    _apiTestResults.add(result)
                } catch (e: Throwable) {
                    log("API测试异常: ${e.message}")
                }
            }
            _apiTestRunning = false
            _apiTestProgress = "完成 (${_apiTestResults.count { it.overallSuccess }}/${_apiTestResults.size} 通过)"
            log("API测试完成: ${_apiTestResults.count { it.overallSuccess }}/${_apiTestResults.size} 通过")
        }
    }

    private fun runSingleApiTest(type: String) {
        if (_apiTestRunning) return
        _apiTestRunning = true
        _apiTestProgress = "测试中..."

        lifecycleScope.launch {
            try {
                val results = mutableListOf<ApiTestManager.ApiTestResult>()
                when (type) {
                    "openai" -> {
                        results.add(apiTestManager.testOpenAiTranslation(_openaiKey))
                        results.add(apiTestManager.testOpenAiWhisper(_openaiKey))
                        results.add(apiTestManager.testOpenAiTts(_openaiKey))
                    }
                    "groq" -> {
                        results.add(apiTestManager.testGroqTranslation(_groqKey))
                        results.add(apiTestManager.testGroqWhisper(_groqKey))
                    }
                    "deepl" -> results.add(apiTestManager.testDeepL(_deeplKey))
                    "edge" -> results.add(apiTestManager.testEdgeTts())
                    "google_tts" -> results.add(apiTestManager.testGoogleTts())
                    "local" -> results.add(apiTestManager.testLocalServer(_localServerUrl, _localServerModel))
                }
                // Replace results for this type, keep others
                val oldNames = results.map { it.apiName }.toSet()
                _apiTestResults.removeAll { it.apiName in oldNames }
                _apiTestResults.addAll(0, results)
            } catch (e: Throwable) {
                log("API测试异常: ${e.message}")
            }
            _apiTestRunning = false
            _apiTestProgress = "完成"
        }
    }

    @Composable
    private fun VoskModelPanel() {
        val hasVosk = sherpaWhisperAsr.isVoskModelPresent()
        val sizeMB = if (hasVosk) sherpaWhisperAsr.voskModelSizeMB() else 0
        Column(Modifier.padding(start = 4.dp, top = 6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    if (hasVosk) {
                        Text("Vosk 模型已就绪", fontSize = 13.sp, color = Color(0xFF4CAF50))
                        Text("占用 ${sizeMB}MB 存储空间", fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f))
                    } else {
                        Text("Vosk 模型未提取", fontSize = 13.sp, color = Color(0xFFEF5350))
                        Text("切换到 Vosk 录音时自动从 assets 提取", fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f))
                    }
                }
                if (hasVosk) {
                    TextButton(onClick = {
                        if (_recording && _asrEngine == 1) {
                            log("请先停止录音再删除模型")
                        } else {
                            sherpaWhisperAsr.deleteVoskModel()
                            _voskReady = false
                            voskModel?.close(); voskModel = null
                            log("已删除 Vosk 模型，释放 ${sizeMB}MB")
                        }
                    }) {
                        Text("删除", fontSize = 12.sp, color = Color(0xFFEF5350))
                    }
                }
            }
        }
    }

    @Composable
    private fun WhisperModelSelector() {
        Column(Modifier.padding(start = 4.dp, top = 6.dp)) {
            Text("模型选择", fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            Spacer(Modifier.height(4.dp))

            SherpaWhisperAsr.WhisperModel.entries.forEachIndexed { idx, m ->
                val dl = sherpaWhisperAsr.isModelDownloaded(m)
                val sizeMB = if (dl) sherpaWhisperAsr.modelSizeMB(m) else 0
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                        .clickable { switchWhisperModel(idx) }
                        .background(if (_whisperModelIdx == idx) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f) else Color.Transparent)
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(_whisperModelIdx == idx, { switchWhisperModel(idx) }, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Column(Modifier.weight(1f)) {
                        Text(m.label, fontSize = 13.sp, fontWeight = if (_whisperModelIdx == idx) FontWeight.SemiBold else FontWeight.Normal)
                        Text("${m.desc} · ~${m.approxSizeMB}MB" +
                            if (dl) " · 已下载 (${sizeMB}MB)" else "",
                            fontSize = 10.sp, color = if (dl) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f))
                    }
                    if (dl) {
                        IconButton(onClick = {
                            if (_recording && _asrEngine == 4 && _whisperModelIdx == idx) {
                                log("请先停止录音再删除模型")
                            } else {
                                sherpaWhisperAsr.deleteModel(m)
                                if (_whisperModelIdx == idx) _localWhisperReady = false
                                log("已删除 Whisper ${m.label}")
                            }
                        }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.Delete, "删除", Modifier.size(16.dp), tint = Color(0xFFEF5350))
                        }
                    }
                }
            }

            Spacer(Modifier.height(6.dp))
            val cur = selectedWhisperModel()
            if (_localWhisperReady) {
                Text("${cur.label} 已就绪", fontSize = 13.sp, color = Color(0xFF4CAF50))
            } else if (_localWhisperDownloading) {
                Text(_downloadProgress.ifBlank { "下载中…" }, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(4.dp)); LinearProgressIndicator(Modifier.fillMaxWidth())
            } else {
                Button(onClick = { downloadWhisperModel() }, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)) {
                    Icon(Icons.Default.Download, null, Modifier.size(14.dp)); Spacer(Modifier.width(4.dp))
                    Text(if (sherpaWhisperAsr.isModelDownloaded(cur)) "重新初始化" else "下载 ${cur.label} (~${cur.approxSizeMB}MB)", fontSize = 12.sp)
                }
            }
        }
    }

    @Composable
    private fun OfflineTransModelPanel() {
        val model = if (_translationEngineType == 6) OnDeviceTranslationModel.NLLB_600M_INT8
                    else OnDeviceTranslationModel.OPUS_MT_EN_ZH
        val downloaded = model.isDownloaded(this@MainActivity)

        Column(Modifier.padding(start = 4.dp, top = 6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(model.label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text("${model.desc} · ~${model.approxSizeMB}MB" +
                        if (downloaded) " · 已下载" else "",
                        fontSize = 10.sp,
                        color = if (downloaded) Color(0xFF4CAF50)
                               else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f))
                }
            }
            Spacer(Modifier.height(6.dp))

            if (downloaded) {
                Text("模型已就绪", fontSize = 13.sp, color = Color(0xFF4CAF50))
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = {
                    translationModelManager.deleteModel(model)
                    invalidateTranslationCache()
                    log("已删除 ${model.label}")
                }) {
                    Text("删除模型", fontSize = 12.sp, color = Color(0xFFEF5350))
                }
            } else if (_offlineTransDownloading) {
                Text(_offlineTransProgress.ifBlank { "下载中…" }, fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth())
            } else {
                Button(onClick = { downloadOfflineTransModel(model) },
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)) {
                    Icon(Icons.Default.Download, null, Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("下载 ${model.label} (~${model.approxSizeMB}MB)", fontSize = 12.sp)
                }
            }
            Text("使用 ONNX Runtime 在设备上运行翻译模型，无需网络",
                fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                modifier = Modifier.padding(top = 4.dp))
        }
    }

    private fun downloadOfflineTransModel(model: OnDeviceTranslationModel) {
        if (_offlineTransDownloading) return
        _offlineTransDownloading = true
        val cb = object : TranslationModelManager.DownloadCallback {
            override fun onProgress(file: String, percent: Int) {
                runOnUiThread { _offlineTransProgress = "$file: $percent%" }
            }
            override fun onComplete(success: Boolean, error: String?) {
                runOnUiThread {
                    _offlineTransDownloading = false
                    _offlineTransProgress = ""
                    if (success) {
                        invalidateTranslationCache()
                        log("${model.label} 下载完成")
                    } else {
                        log("下载失败: $error")
                    }
                }
            }
        }
        lifecycleScope.launch { translationModelManager.download(model, cb) }
    }

    // ---- History ----

    @Composable
    private fun HistoryArea(modifier: Modifier = Modifier) {
        val sdf = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
        val ls = rememberLazyListState()
        val filtered = remember(_historySessions.size, _historySearchQuery) {
            if (_historySearchQuery.isBlank()) _historySessions.toList()
            else translationHistory.search(_historySearchQuery)
        }

        // Rename dialog
        if (_editingSessionId != null) {
            AlertDialog(
                onDismissRequest = { _editingSessionId = null },
                title = { Text("重命名会话") },
                text = {
                    OutlinedTextField(_editingTitle, { _editingTitle = it },
                        label = { Text("标题") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                },
                confirmButton = {
                    TextButton({
                        translationHistory.renameSession(_editingSessionId!!, _editingTitle)
                        _historySessions.clear(); _historySessions.addAll(translationHistory.allSessions())
                        _editingSessionId = null
                    }) { Text("保存") }
                },
                dismissButton = { TextButton({ _editingSessionId = null }) { Text("取消") } }
            )
        }

        Column(modifier.fillMaxWidth()) {
            // Header with search
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("翻译历史 (${filtered.size})", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (_historySessions.isNotEmpty()) {
                    TextButton({
                        translationHistory.clearAll(); _historySessions.clear(); log("历史已清空")
                    }) { Text("清空", fontSize = 12.sp, color = Color(0xFFEF5350)) }
                }
            }
            // Search bar
            OutlinedTextField(
                _historySearchQuery,
                { _historySearchQuery = it },
                placeholder = { Text("搜索标题或内容…", fontSize = 13.sp) },
                leadingIcon = { Icon(Icons.Default.Search, null, Modifier.size(18.dp)) },
                trailingIcon = {
                    if (_historySearchQuery.isNotBlank()) {
                        IconButton({ _historySearchQuery = "" }) { Icon(Icons.Default.Close, null, Modifier.size(16.dp)) }
                    }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(48.dp),
                textStyle = LocalTextStyle.current.copy(fontSize = 13.sp)
            )
            Spacer(Modifier.height(6.dp))

            if (filtered.isEmpty()) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Text(
                        if (_historySearchQuery.isNotBlank()) "未找到匹配记录" else "暂无翻译记录",
                        fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                    )
                }
            } else {
                LazyColumn(state = ls, modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 8.dp),
                    reverseLayout = true) {
                    items(filtered.size) { i ->
                        val idx = filtered.size - 1 - i
                        val session = filtered[idx]
                        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            elevation = CardDefaults.cardElevation(1.dp)) {
                            Column(Modifier.padding(14.dp)) {
                                // Title row with actions
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text(session.displayTitle, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    // Rename
                                    IconButton(onClick = {
                                        _editingSessionId = session.id
                                        _editingTitle = session.title
                                    }, modifier = Modifier.size(24.dp)) {
                                        Icon(Icons.Default.Edit, "重命名", Modifier.size(14.dp),
                                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
                                    }
                                    // Delete
                                    IconButton(onClick = {
                                        translationHistory.deleteSession(session.id)
                                        _historySessions.clear(); _historySessions.addAll(translationHistory.allSessions())
                                        log("已删除会话")
                                    }, modifier = Modifier.size(24.dp)) {
                                        Icon(Icons.Default.Delete, "删除", Modifier.size(14.dp),
                                            tint = Color(0xFFEF5350).copy(alpha = 0.6f))
                                    }
                                }
                                // Time + count
                                Row(Modifier.fillMaxWidth()) {
                                    Text(sdf.format(Date(session.startTime)), fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f))
                                    Spacer(Modifier.weight(1f))
                                    Text("${session.entries.size} 句", fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f))
                                }
                                Spacer(Modifier.height(4.dp))
                                // Merged paragraph view
                                Text(session.enParagraph, fontSize = 13.sp, lineHeight = 18.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                    maxLines = 6, overflow = TextOverflow.Ellipsis)
                                Spacer(Modifier.height(4.dp))
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                                Spacer(Modifier.height(4.dp))
                                Text(session.zhParagraph, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                                    lineHeight = 22.sp, maxLines = 8, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }

    // ---- Conversation ----

    @Composable
    private fun ConversationArea(modifier: Modifier = Modifier) {
        val ls = rememberLazyListState()
        val totalItems = _paragraphs.size + (if (_currentPartial.isNotBlank()) 1 else 0)
        LaunchedEffect(totalItems, _paragraphs.lastOrNull()?.segments?.size) {
            if (totalItems > 0) ls.animateScrollToItem(totalItems - 1)
        }
        LazyColumn(state = ls, modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(vertical = 12.dp)
        ) {
            items(_paragraphs.size) { ParagraphCard(_paragraphs[it]) }
            if (_currentPartial.isNotBlank() && (_recording || _mediaCaptureActive)) {
                item { PartialCard(_currentPartial) }
            }
            if (_paragraphs.isEmpty() && _currentPartial.isBlank()) item { EmptyHint() }
        }
    }

    @Composable
    private fun ParagraphCard(para: Paragraph) {
        Card(Modifier.fillMaxWidth().animateContentSize(), shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(1.dp)
        ) {
            Column(Modifier.padding(14.dp)) {
                // English: all sentences combined — always visible immediately
                Text(para.combinedEn, fontSize = 14.sp, lineHeight = 20.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))

                Spacer(Modifier.height(6.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                Spacer(Modifier.height(6.dp))

                // Chinese: always show whatever is available (rawZh grows in real time)
                val zh = para.displayZh
                if (zh.isNotBlank()) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(zh, fontSize = 17.sp, fontWeight = FontWeight.Medium, lineHeight = 24.sp,
                            modifier = Modifier.weight(1f))
                        if (zh != "[翻译失败]") {
                            IconButton({ speakManual(zh) }, Modifier.size(32.dp)) {
                                Icon(Icons.AutoMirrored.Filled.VolumeUp, "播放",
                                    Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                } else if (para.anyTranslating) {
                    // No Chinese yet but translation in progress — show spinner inline
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("翻译中…", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                    }
                }

                // Status row: show progress or refinement state
                if (zh.isNotBlank() && para.anyTranslating) {
                    Spacer(Modifier.height(4.dp))
                    val done = para.segments.count { !it.translating }
                    Text("翻译中 ($done/${para.segments.size})", fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f))
                } else if (para.refining) {
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(10.dp), strokeWidth = 1.5.dp)
                        Spacer(Modifier.width(4.dp))
                        Text("润色中…", fontSize = 10.sp, color = MaterialTheme.colorScheme.tertiary)
                    }
                } else if (para.refinedZh.isNotBlank() && para.segments.size == para.refinedAtCount) {
                    Spacer(Modifier.height(2.dp))
                    Text("✦ AI", fontSize = 9.sp, color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.4f))
                }
            }
        }
    }

    @Composable
    private fun PartialCard(text: String) {
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f))
        ) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                val p by rememberInfiniteTransition().animateFloat(0.8f, 1.2f,
                    infiniteRepeatable(tween(600), RepeatMode.Reverse))
                Box(Modifier.size(8.dp).scale(p).clip(CircleShape).background(Color(0xFFEF5350)))
                Spacer(Modifier.width(10.dp))
                Text(text, fontSize = 15.sp, fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
        }
    }

    @Composable
    private fun EmptyHint() {
        Box(Modifier.fillMaxWidth().padding(vertical = 60.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.Mic, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f))
                Spacer(Modifier.height(12.dp))
                Text("点击下方按钮开始录音", fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f))
                Text("说英语，自动翻译成中文", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f))
            }
        }
    }

    @Composable
    private fun RecordFAB(recording: Boolean, onClick: () -> Unit) {
        val isMediaCapture = _mediaCaptureActive
        val bg by animateColorAsState(
            if (isMediaCapture) Color(0xFF4CAF50)
            else if (recording) Color(0xFFEF5350) else Color(0xFF5B5FC7)
        )
        val p by rememberInfiniteTransition().animateFloat(1f, if (recording || isMediaCapture) 1.08f else 1f, infiniteRepeatable(tween(800), RepeatMode.Reverse))
        val label = when {
            isMediaCapture -> "媒体捕获中"
            recording -> "正在录音…点击停止"
            else -> "点击开始录音"
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
            Spacer(Modifier.height(6.dp))
            FloatingActionButton(onClick, Modifier.size(64.dp).scale(p).shadow(if (recording || isMediaCapture) 12.dp else 6.dp, CircleShape), containerColor = bg, contentColor = Color.White, shape = CircleShape) {
                Icon(
                    if (isMediaCapture) Icons.Default.Audiotrack
                    else if (recording) Icons.Default.Stop else Icons.Default.Mic,
                    label, Modifier.size(28.dp)
                )
            }
        }
    }
}
