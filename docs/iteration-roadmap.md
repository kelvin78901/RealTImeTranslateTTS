# RealTimeTranslateTTS 后续迭代技术手册

> 版本：1.0 → 2.0 迭代规划
> 基于：README 开发计划 + sherpa-onnx-analysis.md 可优化项
> 日期：2026-04-04

> v1.2.3 状态快照（2026-04-09）：
> - ✅ Iter-1 核心能力已落地：`SherpaStreamingAsr`（流式 partial/final + endpoint）
> - ✅ Iter-2 核心能力已落地：统一 `VitsTts`（VITS/Matcha/Kokoro/Kitten）
> - ✅ Kokoro 升级到 multi-lang v1.1，并完成下载/初始化/音色接入
> - ✅ 构建兼容性修复：Gradle 运行时固定 JDK 21，避免 JDK 25 触发构建失败

---

## 目录

- [总览：迭代阶段与优先级矩阵](#总览)
- [Iter-0：UI 重构与交互优化（P0）](#iter-0)
- [Iter-1：流式 ASR 替换 Vosk](#iter-1)
- [Iter-2：Kokoro TTS + 多音色](#iter-2)
- [Iter-3：ASR 前处理增强（降噪 + 标点）](#iter-3)
- [Iter-4：语种自动识别 + 双向互译](#iter-4)
- [Iter-5：说话人识别与标注](#iter-5)
- [Iter-6：AI 助手对话](#iter-6)
- [Iter-7：会议专用功能](#iter-7)
- [附录 A：AAR 升级方案](#附录-a)
- [附录 B：模型资源清单](#附录-b)
- [附录 C：ONNX Runtime 版本冲突规避](#附录-c)
---

<a id="总览"></a>
## 总览：迭代阶段与优先级矩阵

### 阶段划分

| 阶段 | 内容 | 对应 README 计划项 | 预估工期 | 前置依赖 |
|:----:|------|-------------------|:--------:|:--------:|
| **Iter-1** | 流式 ASR 替换 Vosk | "支持更多 ASR 引擎" | 3-4 天 | AAR 升级 |
| **Iter-2** | Kokoro TTS + 多音色选择 | "自定义音色和语速的 TTS 输出" | 2-3 天 | AAR 升级 |
| **Iter-3** | ASR 前处理增强（降噪 + 标点恢复） | "翻译增强功能" | 2-3 天 | Iter-1 |
| **Iter-4** | 语种自动识别 + 双向互译 | "支持更多输入输出语言" | 3-4 天 | Iter-1 |
| **Iter-5** | 说话人识别与标注 | "识别不同说话人" | 3-4 天 | Iter-1 |
| **Iter-6** | AI 助手对话 | "AI 助手对话" | 4-5 天 | 无 |
| **Iter-7** | 会议专用功能 | "会议专用功能" | 5-7 天 | Iter-5 |

### 前置条件：AAR 升级（所有 Iter 的基础）

当前项目使用的 `sherpa-onnx.aar` 仅包含 OfflineRecognizer + OfflineTts 功能。Iter-1～5 所需的 OnlineRecognizer、OnlinePunctuation、OnlineSpeechDenoiser、SpokenLanguageIdentification、SpeakerEmbeddingExtractor 等 API 需要升级到 sherpa-onnx 1.12.x AAR。

详见 [附录 A：AAR 升级方案](#附录-a)。

---

<a id="iter-1"></a>
## Iter-1：流式 ASR 替换 Vosk

### 1.1 目标

用 sherpa-onnx OnlineRecognizer（Zipformer Transducer）替换 Vosk 作为主要离线 ASR 引擎，实现：
- 真正的流式识别（边说边出文字）
- 中英双语支持
- 识别延迟从 ~500ms 降至 ~200ms
- 内置 endpoint 检测，无需外部 VAD 截断

### 1.2 当前状态

```
ASR 引擎选项（_asrEngine）：
  0 = 系统 ASR（联网）
  1 = Vosk 离线（准确率一般）  ← 将被替换
  2 = OpenAI Whisper API
  3 = Groq Whisper API
  4 = 本地 Whisper（sherpa-onnx OfflineRecognizer + Silero VAD）
  5 = GPT-4o 语音
```

### 1.3 修改方案

#### 步骤 1：新建 `asr/SherpaStreamingAsr.kt`

```kotlin
package com.example.myapplication1.asr

/**
 * 流式 ASR：使用 sherpa-onnx OnlineRecognizer (Zipformer Transducer)。
 * 替代 Vosk 成为新的离线 ASR 首选。
 *
 * 关键区别于 SherpaWhisperAsr（离线批量识别）：
 * - 不需要 VAD 截断后送整段音频
 * - 音频到达即送入，结果实时输出
 * - 内置 endpoint 检测（可配置静音阈值）
 */
class SherpaStreamingAsr(private val context: Context) {

    companion object {
        private const val TAG = "SherpaStreamingAsr"
        private const val SAMPLE_RATE = 16000
    }

    /** 可选模型 */
    enum class StreamingModel(
        val label: String,
        val desc: String,
        val dirName: String,
        val hfRepo: String,
        val encoderFile: String,
        val decoderFile: String,
        val joinerFile: String,
        val tokensFile: String,
        val approxSizeMB: Int,
        val isInt8: Boolean
    ) {
        ZIPFORMER_BILINGUAL(
            label = "Zipformer 中英",
            desc = "中英双语，流式，推荐",
            dirName = "sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20",
            hfRepo = "csukuangfj/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20",
            encoderFile = "encoder-epoch-99-avg-1.int8.onnx",
            decoderFile = "decoder-epoch-99-avg-1.onnx",
            joinerFile = "joiner-epoch-99-avg-1.int8.onnx",
            tokensFile = "tokens.txt",
            approxSizeMB = 70,
            isInt8 = true
        ),
        ZIPFORMER_EN(
            label = "Zipformer 英语",
            desc = "纯英语，流式",
            dirName = "sherpa-onnx-streaming-zipformer-en-2023-06-26",
            hfRepo = "csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-06-26",
            encoderFile = "encoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx",
            decoderFile = "decoder-epoch-99-avg-1-chunk-16-left-128.onnx",
            joinerFile = "joiner-epoch-99-avg-1-chunk-16-left-128.int8.onnx",
            tokensFile = "tokens.txt",
            approxSizeMB = 50,
            isInt8 = true
        )
    }

    interface Callback {
        fun onPartial(text: String)          // 实时中间结果
        fun onResult(text: String)           // endpoint 触发后的最终结果
        fun onStateChanged(ready: Boolean)
    }

    // 核心对象
    private var recognizer: OnlineRecognizer? = null
    private var stream: OnlineStream? = null
    private var audioRecord: AudioRecord? = null
    private var asrJob: Job? = null
    @Volatile private var recording = false

    var callback: Callback? = null

    fun isReady() = recognizer != null

    /**
     * 初始化：下载模型（如需）+ 创建 OnlineRecognizer
     */
    suspend fun init(model: StreamingModel = StreamingModel.ZIPFORMER_BILINGUAL) {
        withContext(Dispatchers.IO) {
            val modelDir = ensureModelDownloaded(model)

            val config = OnlineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = "$modelDir/${model.encoderFile}",
                        decoder = "$modelDir/${model.decoderFile}",
                        joiner = "$modelDir/${model.joinerFile}",
                    ),
                    tokens = "$modelDir/${model.tokensFile}",
                    numThreads = 2,
                    debug = false,
                    provider = "cpu"
                ),
                endpointConfig = EndpointConfig(
                    // Rule1: 2.4s 静音 → endpoint（无论是否有语音）
                    rule1 = EndpointRule(false, 2.4f, 0.0f),
                    // Rule2: 1.2s 静音 → endpoint（有语音之后）
                    rule2 = EndpointRule(true, 1.2f, 0.0f),
                    // Rule3: 单句超 20s 强制 endpoint
                    rule3 = EndpointRule(false, 0.0f, 20.0f)
                ),
                enableEndpoint = true,
                decodingMethod = "greedy_search"
            )

            recognizer = OnlineRecognizer(config = config)  // 文件模式
            stream = recognizer!!.createStream()
        }
        callback?.onStateChanged(true)
    }

    /**
     * 开始录音 + 流式识别
     */
    fun startRecording() {
        if (recording || recognizer == null) return
        val sr = SAMPLE_RATE
        val bufSize = max(
            AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT), 4096
        )
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, sr,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize
        )
        audioRecord?.startRecording()
        recording = true

        asrJob = CoroutineScope(Dispatchers.IO).launch {
            val buf = ShortArray(1600)  // 100ms @ 16kHz
            while (isActive && recording) {
                val n = audioRecord?.read(buf, 0, buf.size) ?: break
                if (n <= 0) continue

                // Short → Float 转换
                val floats = FloatArray(n) { buf[it] / 32768.0f }

                // 送入流式识别器
                stream?.acceptWaveform(floats, SAMPLE_RATE)
                while (recognizer!!.isReady(stream!!)) {
                    recognizer!!.decode(stream!!)
                }

                // 获取当前中间结果
                val result = recognizer!!.getResult(stream!!)
                if (result.text.isNotBlank()) {
                    withContext(Dispatchers.Main) {
                        callback?.onPartial(result.text)
                    }
                }

                // 检查 endpoint
                if (recognizer!!.isEndpoint(stream!!)) {
                    val final = recognizer!!.getResult(stream!!).text.trim()
                    if (final.isNotBlank()) {
                        withContext(Dispatchers.Main) {
                            callback?.onResult(final)
                        }
                    }
                    recognizer!!.reset(stream!!)
                }
            }
        }
    }

    fun stopRecording() {
        recording = false
        asrJob?.cancel()
        // 获取残余结果
        val last = recognizer?.getResult(stream!!)?.text?.trim()
        if (!last.isNullOrBlank()) callback?.onResult(last)
        recognizer?.reset(stream!!)
        try { audioRecord?.stop() } catch (_: Throwable) {}
        try { audioRecord?.release() } catch (_: Throwable) {}
        audioRecord = null
    }

    fun close() {
        stopRecording()
        stream?.release()
        recognizer?.release()
        stream = null; recognizer = null
    }

    // 模型下载逻辑（复用 SherpaWhisperAsr 的 HuggingFace 下载方式）
    private suspend fun ensureModelDownloaded(model: StreamingModel): String {
        val dir = File(context.filesDir, model.dirName)
        if (dir.exists() && File(dir, model.tokensFile).exists()) return dir.absolutePath
        // ... 从 HuggingFace 下载（与 SherpaWhisperAsr.downloadModel 相同逻辑）
        return dir.absolutePath
    }
}
```

#### 步骤 2：修改 `MainActivity.kt`

在 ASR 引擎列表中新增选项：

```kotlin
// 原有：
// 0=系统, 1=Vosk, 2=OpenAI, 3=Groq, 4=本地Whisper, 5=GPT-4o

// 新增：
// 6=Sherpa 流式（Zipformer）

// 在 DrawerContent 中添加：
AsrOption(6, "Sherpa 流式离线", "Zipformer · 中英双语 · 推荐")
```

新增状态变量和初始化逻辑：

```kotlin
private var streamingAsr: SherpaStreamingAsr? = null
private var _streamingAsrReady by mutableStateOf(false)
```

在 `toggleRecording()` 中添加 `_asrEngine == 6` 分支：

```kotlin
6 -> {
    if (_recording) {
        streamingAsr?.stopRecording()
        _recording = false
    } else {
        if (streamingAsr == null) {
            streamingAsr = SherpaStreamingAsr(this).apply {
                callback = object : SherpaStreamingAsr.Callback {
                    override fun onPartial(text: String) {
                        _currentPartial = text
                    }
                    override fun onResult(text: String) {
                        // 复用现有 onAsrResult 流程
                        onAsrResultFiltered(text)
                    }
                    override fun onStateChanged(ready: Boolean) {
                        _streamingAsrReady = ready
                    }
                }
            }
            lifecycleScope.launch { streamingAsr!!.init() }
        }
        streamingAsr?.startRecording()
        _recording = true
    }
}
```

#### 步骤 3：迁移 Vosk 在 MediaCaptureService/FloatingTranslateService 中的使用

当前 `MediaCaptureService` 和 `FloatingTranslateService` 硬编码使用 Vosk。需要：
1. 在两个 Service 中读取 `_asrEngine` 配置
2. 当 `_asrEngine == 6` 时使用 `SherpaStreamingAsr` 替代 Vosk

### 1.4 验证标准

- [ ] 选择"Sherpa 流式离线"后，录音过程中实时显示 partial 文字
- [ ] 说话停顿 1.2s 后自动触发 endpoint，结果进入翻译流水线
- [ ] 中英双语混合输入均可正确识别
- [ ] 长句（>20s）自动切断
- [ ] 延迟指标面板显示 ASR 延迟 < 300ms
- [ ] 悬浮窗和媒体捕获模式下均可使用

### 1.5 风险与规避

| 风险 | 规避措施 |
|------|---------|
| 模型下载 ~70MB 较大 | 显示下载进度条，支持仅 WiFi 下载 |
| 首次加载模型 ~3s | 显示"模型加载中"状态，异步初始化 |
| 保留 Vosk 作为备选 | 不删除 Vosk 代码，保持引擎可切换 |

---

<a id="iter-2"></a>
## Iter-2：Kokoro TTS + 多音色

### 2.1 目标

新增 Kokoro TTS 引擎选项，提供 31+ 音色选择、中英双语支持，替代 VITS 成为推荐本地 TTS。

### 2.2 修改方案

#### 步骤 1：在 `SherpaOnnxTts.kt` 中新增 Kokoro 初始化路径

当前 `SherpaOnnxTts` 仅支持 VITS。需要：
1. 构造函数增加 `modelType` 参数（`vits` / `kokoro`）
2. 根据 `modelType` 切换使用 `OfflineTtsVitsModelConfig` 或 `OfflineTtsKokoroModelConfig`
3. Kokoro 需要额外文件：`voices.bin`、`espeak-ng-data/` 目录

```kotlin
// Kokoro 初始化路径（添加到 init() 中）
"kokoro" -> {
    val kokoroCfg = newNoArg(clsKokoro)?.also {
        setByName(it, "model", modelOnnx.absolutePath)
        setByName(it, "voices", File(outDir, "voices.bin").absolutePath)
        setByName(it, "tokens", tokensTxt.absolutePath)
        setByName(it, "dataDir", File(outDir, "espeak-ng-data").absolutePath)
        setByName(it, "lengthScale", 1.0f)
    }
    // ... 构建 OfflineTtsModelConfig
}
```

#### 步骤 2：新增 TTS 引擎选项

```kotlin
// 原有 TTS 引擎：
// 0=Edge, 1=系统, 2=Google, 3=OpenAI, 4=Sherpa VITS

// 新增：
// 5=Sherpa Kokoro（多音色）
```

#### 步骤 3：音色选择 UI

Kokoro multi-lang v1.0 提供 31+ 预定义音色。在 DrawerContent 中新增音色选择器：

```kotlin
AnimatedVisibility(_ttsEngine == 5) {
    Column(Modifier.padding(start = 12.dp, top = 6.dp)) {
        Text("Kokoro 音色", fontSize = 12.sp, ...)
        // 音色列表从模型元数据读取（numSpeakers()）
        // 或预定义常用音色名
        val voices = listOf(
            "af" to "美式女声 (默认)",
            "af_bella" to "Bella 女声",
            "af_sarah" to "Sarah 女声",
            "am_adam" to "Adam 男声",
            "am_michael" to "Michael 男声",
            "bf_emma" to "英式 Emma",
            "bm_george" to "英式 George",
            // ... 更多音色
        )
        voices.forEach { (id, label) ->
            SmallRadio(label, _kokoroVoiceId == id) {
                _kokoroVoiceId = id
                saveKey("kokoro_voice_id", id)
            }
        }
    }
}
```

#### 步骤 4：模型下载管理

Kokoro multi-lang 模型约 80MB，需要按需下载：
- 复用现有的 `TranslationModelManager` 下载逻辑
- 模型目录：`filesDir/sherpa-onnx-kokoro/`
- 必需文件：`model.onnx`、`voices.bin`、`tokens.txt`、`espeak-ng-data/`

### 2.3 验证标准

- [ ] 选择 Kokoro TTS 后，中文和英文均可正常合成
- [ ] 切换音色后语音特征明显不同
- [ ] 语速控制正常（`lengthScale` 参数生效）
- [ ] 首次使用时提示下载模型，有进度条
- [ ] 悬浮窗模式下 Kokoro TTS 正常工作

---

<a id="iter-3"></a>
## Iter-3：ASR 前处理增强（降噪 + 标点恢复）

### 3.1 目标

在 ASR 识别链路中增加两个增强环节：

```
麦克风 → [降噪] → ASR → [标点恢复] → 翻译流水线
```

### 3.2 修改方案

#### A. 流式降噪（OnlineSpeechDenoiser）

在 `SherpaStreamingAsr` 的音频处理循环中，ASR 之前插入降噪：

```kotlin
// 在 init() 中初始化降噪器
private var denoiser: OnlineSpeechDenoiser? = null

// 降噪模型约 5MB
val denoiserConfig = OnlineSpeechDenoiserConfig(
    model = OfflineSpeechDenoiserModelConfig(
        gtcrn = OfflineSpeechDenoiserGtcrnModelConfig(
            model = "$modelDir/gtcrn_simple.onnx"
        )
    )
)
denoiser = OnlineSpeechDenoiser(config = denoiserConfig)

// 在音频循环中：
val floats = FloatArray(n) { buf[it] / 32768.0f }

// 降噪（如已启用）
val processedSamples = if (denoiserEnabled && denoiser != null) {
    val denoised = denoiser!!.run(floats, SAMPLE_RATE)
    denoised.samples
} else {
    floats
}

stream?.acceptWaveform(processedSamples, SAMPLE_RATE)
```

**UI 控制**：在"智能过滤"设置区新增"AI 降噪"开关。

#### B. 标点恢复（OnlinePunctuation）

在 ASR 结果输出后、送入翻译流水线前插入标点恢复：

```kotlin
// 初始化（与 ASR 引擎一起）
private var punctuation: OnlinePunctuation? = null

val punctConfig = OnlinePunctuationConfig(
    model = OnlinePunctuationModelConfig(
        cnnBiLstm = "$modelDir/model.onnx",
        bpeVocab = "$modelDir/bpe.vocab"
    )
)
punctuation = OnlinePunctuation(config = punctConfig)

// 在 onResult 回调中：
override fun onResult(text: String) {
    val withPunct = punctuation?.addPunctuation(text) ?: text
    onAsrResultFiltered(withPunct)
}
```

**效果示例**：
```
ASR 输出：  "hello how are you i am fine thank you"
标点恢复后："Hello, how are you? I am fine, thank you."
```

### 3.3 模型资源

| 模型 | 体积 | 用途 |
|------|------|------|
| `gtcrn_simple.onnx` | ~5MB | 流式降噪 |
| 标点 CNN-BiLSTM | ~5MB | 标点恢复 |

### 3.4 验证标准

- [ ] 嘈杂环境下（背景音乐/人声）ASR 准确率提升
- [ ] 标点恢复后的文本送入翻译，翻译质量提升
- [ ] 降噪和标点可独立开关
- [ ] 对 ASR 延迟的影响 < 50ms

---

<a id="iter-4"></a>
## Iter-4：语种自动识别 + 双向互译

### 4.1 目标

对应 README 计划："支持更多输入输出语言"、"双向互译"。

实现：
1. 自动检测输入音频的语种（中文/英文/日语/韩语等）
2. 根据检测结果动态切换翻译方向
3. TTS 输出语种自动匹配

### 4.2 修改方案

#### 步骤 1：集成语种识别

```kotlin
// 新建 asr/LanguageDetector.kt
class LanguageDetector(context: Context) {

    private var lid: SpokenLanguageIdentification? = null

    fun init() {
        val config = SpokenLanguageIdentificationConfig(
            whisper = SpokenLanguageIdentificationWhisperConfig(
                encoder = "$modelDir/encoder.onnx",
                decoder = "$modelDir/decoder.onnx"
            ),
            numThreads = 2
        )
        lid = SpokenLanguageIdentification(config = config)
    }

    /**
     * 检测音频片段的语种。
     * 返回 ISO 639-1 代码：en, zh, ja, ko, fr, de, es, ...
     */
    fun detect(samples: FloatArray, sampleRate: Int): String {
        val stream = lid!!.createStream()
        stream.acceptWaveform(samples, sampleRate)
        return lid!!.compute(stream)
    }
}
```

#### 步骤 2：修改翻译方向逻辑

当前硬编码 EN→ZH。改为动态：

```kotlin
// TranslationEngine 基类增加语言对参数
abstract suspend fun translate(
    text: String,
    context: TranslationContext
): String

// TranslationContext 增加字段
data class TranslationContext(
    // ... 已有字段
    val sourceLang: String = "en",   // 新增：源语言
    val targetLang: String = "zh",   // 新增：目标语言
)
```

#### 步骤 3：LLM 引擎适配

LLM 翻译引擎的 system prompt 改为动态：

```kotlin
private fun buildSiPrompt(src: String, tgt: String): String {
    val langNames = mapOf(
        "en" to "英文", "zh" to "中文", "ja" to "日语",
        "ko" to "韩语", "fr" to "法语", "de" to "德语", "es" to "西班牙语"
    )
    val srcName = langNames[src] ?: src
    val tgtName = langNames[tgt] ?: tgt
    return "你是专业同声传译员（${srcName}译${tgtName}）。" +
        "请将用户输入的${srcName}直接翻译为地道、简洁的${tgtName}。" +
        "只输出翻译结果，不得包含任何解释、标注或额外标点。"
}
```

#### 步骤 4：DeepL 适配

DeepL API 的 `source_lang` 和 `target_lang` 改为动态传入。

#### 步骤 5：UI 修改

```
顶部状态栏新增：[自动检测] EN → ZH [切换]
                        ↑
                  点击可手动选择源语言/目标语言
```

### 4.3 验证标准

- [ ] 说中文时自动识别为 zh，翻译方向切换为 ZH→EN
- [ ] 说英文时自动识别为 en，翻译方向为 EN→ZH
- [ ] 手动选择语种后，锁定不再自动切换
- [ ] 日语/韩语等语种可识别（翻译引擎需支持）
- [ ] 语种检测延迟 < 500ms

---

<a id="iter-5"></a>
## Iter-5：说话人识别与标注

### 5.1 目标

对应 README 计划："识别不同说话人（Speaker Diarization）与个性化翻译"。

实现：
1. 实时识别当前说话人
2. 在翻译结果中标注说话人身份
3. 支持注册/管理说话人

### 5.2 修改方案

#### 步骤 1：新建 `asr/SpeakerRecognizer.kt`

```kotlin
class SpeakerRecognizer(context: Context) {

    private var extractor: SpeakerEmbeddingExtractor? = null
    private var manager: SpeakerEmbeddingManager? = null

    fun init() {
        val config = SpeakerEmbeddingExtractorConfig(
            model = "$modelDir/3dspeaker_speech_eres2net_base.onnx",
            numThreads = 2
        )
        extractor = SpeakerEmbeddingExtractor(config = config)
        manager = SpeakerEmbeddingManager(extractor!!.dim())
    }

    /** 注册说话人（需 3-5 秒语音样本） */
    fun enroll(name: String, samples: FloatArray, sampleRate: Int): Boolean {
        val stream = extractor!!.createStream()
        stream.acceptWaveform(samples, sampleRate)
        if (!extractor!!.isReady(stream)) return false
        val embedding = extractor!!.compute(stream)
        return manager!!.add(name, embedding)
    }

    /** 识别当前说话人 */
    fun identify(samples: FloatArray, sampleRate: Int, threshold: Float = 0.5f): String {
        val stream = extractor!!.createStream()
        stream.acceptWaveform(samples, sampleRate)
        if (!extractor!!.isReady(stream)) return ""
        val embedding = extractor!!.compute(stream)
        return manager!!.search(embedding, threshold)
    }
}
```

#### 步骤 2：修改数据模型

```kotlin
// Segment 增加说话人字段
data class Segment(
    // ... 已有字段
    val speaker: String = "",        // 说话人标识
)
```

#### 步骤 3：在 ASR 结果回调中加入说话人识别

在每次 endpoint 触发时，对该段音频进行说话人识别：

```kotlin
override fun onResult(text: String) {
    val speaker = speakerRecognizer?.identify(lastSegmentSamples, SAMPLE_RATE) ?: ""
    // 将 speaker 信息传入 addSegmentToParagraph
    addSegmentToParagraph(text, speaker)
}
```

#### 步骤 4：UI 显示

在 `ParagraphCard` 中显示说话人标签：

```kotlin
if (seg.speaker.isNotBlank()) {
    Text(seg.speaker, fontSize = 10.sp, color = speakerColor(seg.speaker),
        fontWeight = FontWeight.Bold)
}
```

#### 步骤 5：说话人管理页面

新增"说话人管理"设置区：
- 列出已注册说话人
- "录制注册"按钮（录 5 秒样本）
- 删除/重命名说话人

### 5.3 验证标准

- [ ] 注册说话人后，该人说话时正确标注
- [ ] 未注册说话人显示"未知"或自动编号
- [ ] 多人交替说话时标注切换正确
- [ ] 说话人信息保存在翻译历史中

---

<a id="iter-6"></a>
## Iter-6：AI 助手对话

### 6.1 目标

对应 README 计划："AI 助手对话：基于当前会话历史追问、总结、草拟回复"。

### 6.2 修改方案

#### 核心设计

在翻译会话的基础上，提供 AI 助手功能：
1. **总结**：对当前会话生成摘要
2. **追问**：基于会话上下文提问
3. **草拟回复**：基于对方最后一句生成建议回复

#### 步骤 1：新建 `AiAssistant.kt`

```kotlin
class AiAssistant(
    private val apiKey: String,
    private val baseUrl: String,
    private val model: String
) {
    /** 基于会话历史生成摘要 */
    suspend fun summarize(entries: List<TranslationHistory.Entry>): String {
        val context = entries.joinToString("\n") { "${it.en}\n→ ${it.zh}" }
        val prompt = "请用中文简要总结以下对话内容（3-5 条要点）：\n$context"
        return callLlm(prompt)
    }

    /** 基于会话历史回答用户追问 */
    suspend fun askQuestion(
        entries: List<TranslationHistory.Entry>,
        question: String
    ): String {
        val context = entries.takeLast(20).joinToString("\n") { "${it.en}\n→ ${it.zh}" }
        val prompt = "以下是一段对话记录：\n$context\n\n用户追问：$question\n请用中文回答："
        return callLlm(prompt)
    }

    /** 草拟回复建议 */
    suspend fun draftReply(
        entries: List<TranslationHistory.Entry>,
        style: String = "正式"
    ): String {
        val last = entries.lastOrNull() ?: return ""
        val context = entries.takeLast(10).joinToString("\n") { "${it.en}\n→ ${it.zh}" }
        val prompt = "以下是对话记录：\n$context\n\n" +
            "请用${style}的英文草拟一句回复（基于最后一句 \"${last.en}\"）："
        return callLlm(prompt)
    }

    private suspend fun callLlm(prompt: String): String { /* 复用 LLM API 调用 */ }
}
```

#### 步骤 2：UI 入口

在会话区底部或顶部添加 AI 助手按钮栏：

```
[总结] [追问] [草拟回复]
```

点击后弹出底部面板（BottomSheet）显示结果。

### 6.3 验证标准

- [ ] 总结功能能生成 3-5 条要点
- [ ] 追问能基于上下文正确回答
- [ ] 草拟回复语气和上下文匹配
- [ ] 需要 API Key（使用已配置的 OpenAI/Groq Key）

---

<a id="iter-7"></a>
## Iter-7：会议专用功能

### 7.1 目标

对应 README 计划："会议专用功能：与日历/会议软件集成，自动识别会议语境与术语，提供会议纪要草稿"。

### 7.2 修改方案

1. **会议模式**：一键进入会议模式，自动启用：
   - 说话人识别（Iter-5）
   - 会议领域词库
   - 降噪
   - 同步录音

2. **同步录音**：对应 README "增加同步录音功能"
   - 录音保存为 WAV 文件
   - 与翻译历史关联

3. **会议纪要草稿**：会议结束后调用 AI 助手（Iter-6）生成：
   - 会议议题
   - 关键决策
   - 行动项（Action Items）
   - 待确认事项

4. **导出**：支持导出为 Markdown/TXT 文件

### 7.3 依赖关系

```
Iter-7 依赖：
  ← Iter-5（说话人识别）
  ← Iter-6（AI 助手）
  ← Iter-3（降噪 + 标点）
```

---

<a id="附录-a"></a>
## 附录 A：AAR 升级方案

### 当前状态

- 项目使用 `app/libs/sherpa-onnx.aar`（约 38MB）
- 通过 `settings.gradle.kts` 中的 `flatDir` 引入
- 仅包含 OfflineRecognizer + OfflineTts 功能

### 升级方案

#### 方案 1：下载预编译 AAR（推荐）

从 [sherpa-onnx releases](https://github.com/k2-fsa/sherpa-onnx/releases) 下载最新版 AAR：

```bash
# 下载最新 AAR（包含全部功能）
wget https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.12.35/sherpa-onnx-1.12.35.aar
# 替换 app/libs/sherpa-onnx.aar
cp sherpa-onnx-1.12.35.aar app/libs/sherpa-onnx.aar
```

#### 方案 2：自编译（裁剪体积）

```bash
cd /Users/kelvin/Downloads/sherpa-onnx-master

# 仅编译需要的功能
./build-android-arm64-v8a.sh \
  -DSHERPA_ONNX_ENABLE_TTS=ON \
  -DSHERPA_ONNX_ENABLE_SPEAKER_DIARIZATION=ON \
  -DBUILD_SHARED_LIBS=ON
```

#### 方案 3：JitPack 远程引用

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        maven { url = uri("https://jitpack.io") }
    }
}

// build.gradle.kts
dependencies {
    implementation("com.github.k2-fsa:sherpa-onnx:v1.12.35")
}
```

### ONNX Runtime 冲突处理

详见 [附录 C](#附录-c)。

---

<a id="附录-b"></a>
## 附录 B：模型资源清单

### 必需模型（Phase 1）

| 模型 | 体积 | 来源 | 用途 |
|------|------|------|------|
| Zipformer bilingual zh-en (int8) | ~70MB | [HuggingFace](https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20) | 流式 ASR |
| Kokoro multi-lang v1.0 | ~80MB | [HuggingFace](https://huggingface.co/csukuangfj/sherpa-onnx-tts-kokoro-multi-lang-v1_0) | 多音色 TTS |
| Silero VAD | ~2MB | 已有 | VAD |

### 可选模型（Phase 2-3）

| 模型 | 体积 | 用途 |
|------|------|------|
| GTCRN denoiser | ~5MB | 流式降噪 |
| CNN-BiLSTM punct | ~5MB | 标点恢复 |
| Whisper tiny LID | ~40MB | 语种识别 |
| 3D-Speaker embedding | ~25MB | 说话人识别 |

### 下载策略

1. **Silero VAD**：内置于 APK（仅 2MB）
2. **ASR / TTS 模型**：首次选择时按需下载，支持断点续传
3. **增强模型**：在"翻译增强"设置中提供下载入口

---

<a id="附录-c"></a>
## 附录 C：ONNX Runtime 版本冲突规避

### 问题描述

当前项目存在两个 ONNX Runtime 消费者：
1. **sherpa-onnx AAR**：内含自己编译的 `libonnxruntime.so`（通过 JNI 调用）
2. **翻译模型（OnDeviceTranslation）**：使用 `onnxruntime-android:1.17.3` Maven 依赖

两者的 `libonnxruntime.so` 可能版本不同，同一进程中加载两个不同版本会导致符号冲突或 crash。

### 当前规避方式

在 `build.gradle.kts` 中已有排除规则：

```kotlin
packagingOptions {
    pickFirst("lib/arm64-v8a/libonnxruntime.so")
    pickFirst("lib/arm64-v8a/libonnxruntime4j_jni.so")
}
```

这只保留一个版本的 so 文件。

### 推荐升级策略

1. **统一版本**：升级 sherpa-onnx AAR 到与 `onnxruntime-android:1.17.3` 相同的 ORT 版本（或反之）
2. **自编译 sherpa-onnx**：编译时指定使用项目中已有的 ORT 版本
3. **验证步骤**：
   - 升级 AAR 后，运行以下测试：
     - 流式 ASR 正常
     - Kokoro TTS 正常
     - OnDeviceTranslation（Opus-MT / NLLB）正常
     - 三者交替使用不 crash
   - 如有冲突，回退到 `pickFirst` 策略并测试哪个版本更兼容

---

<a id="iter-0"></a>
# Iter-0：UI 重构与交互优化（最高优先级）

> 说明：此为新增迭代项，因当前 UI 存在可用性问题，需在其他功能迭代前先完成重构。
> 优先级：P0（必须先做）
> 预估工期：4-5 天
> 状态：✅ 已完成（version 1.2.2）

---

## 0.1 问题诊断

### 问题 1：侧边栏设置过长且扁平

**现状**：`DrawerContent()` 中包含 **14 个 `CollapsibleSection`**，全部平铺在一个 `verticalScroll` 的 Column 中：

```
基本设置 / 语音识别引擎 / 翻译引擎 / 翻译增强 / 术语库管理 / AI 润色 /
语音合成引擎 / 音频设备 / 智能过滤 / 媒体转译 / 悬浮窗模式 /
API 密钥管理 / API 连通测试 / 系统日志
```

**问题点**：
- 信息架构扁平，缺少分类
- 用户需要大量滚动才能找到目标设置
- 14 个同级项目视觉上压迫感强
- 当前已经用到「3.3) 翻译增强」「3.4) 术语库管理」「3.5) AI 润色」这种编号，说明用户自己也意识到需要分组

### 问题 2：段落自动聚合不可控

**现状**：`MainActivity.addSegmentToParagraph()` 强制将 ASR 句子按规则聚合：

```kotlin
if (_paragraphs.isEmpty() || (_paragraphs.last().allDone && _paragraphs.last().segments.size >= 8)) {
    _paragraphs.add(Paragraph(id = _nextParagraphId++))
}
// ... 硬编码每段 >= 8 句，静音 4 秒结束段落
private val PARAGRAPH_SILENCE_MS = 4000L
```

**问题点**：
- 用户无法关闭段落聚合（只有"句子流"模式的需求无法满足）
- 无法自定义聚合阈值（句子数、静音时长）
- AI 润色（`closeParagraph` 触发）与段落聚合强绑定——关闭润色也无法独立关闭聚合

### 问题 3：会话列表三项可用性缺陷

#### 3a. 缺少清晰标号

**现状**（`HistoryArea` 第 2768 行）：

```kotlin
Text(session.displayTitle, ...)  // "你好 我是小明" 截取前 40 字
Text("${session.entries.size} 句", ...)  // 右下角句数
```

**问题**：标题是自动从第一句截断生成，视觉上无法快速分辨"第几个会话"。

#### 3b. 无"新建对话"UI 入口

**现状**：
- 新会话只在两种情况下创建：(i) App 启动时的 Session 对话框，(ii) `clearAll()` 被调用时
- **用户在运行中无法主动开启一个新会话**
- `translationHistory.newSession()` 存在但未暴露给 UI

#### 3c. 删除逻辑有缺陷

**现状**（`HistoryArea` 第 2779 行 + `TranslationHistory.deleteSession`）：

```kotlin
IconButton(onClick = {
    translationHistory.deleteSession(session.id)  // 立即删除，无确认
    _historySessions.clear()
    _historySessions.addAll(translationHistory.allSessions())
}, ...)

// TranslationHistory.kt
fun deleteSession(id: String) {
    sessions.removeAll { it.id == id }
    if (currentSessionId == id) currentSessionId = null  // ← 清空 currentId
    scheduleSave()
}
```

**问题点**：
1. **无确认对话框** — 单击即永久删除
2. **删除当前会话后，`_paragraphs` 仍显示旧数据** — UI 状态和数据不同步
3. **删除录音中的会话** — 正在翻译的结果会写入新自动创建的会话，造成数据错乱
4. **无撤销机制**

---

## 0.2 设计方案

### 0.2.1 三级菜单重构

#### 顶层分类（4 大组 + 通用）

```
📱 通用
├── 基本设置（自动播报/主题/语言）
└── 段落聚合（新增：可关闭）

🎤 语音
├── 语音识别引擎（ASR）
├── 语音合成引擎（TTS）
├── 音频设备
└── 智能过滤

🌐 翻译
├── 翻译引擎
├── 翻译增强（延迟模式 + 背景 + 领域）
├── 术语库管理
└── AI 润色

🔌 高级
├── 媒体转译
├── 悬浮窗模式
├── API 密钥管理
├── API 连通测试
└── 系统日志
```

#### 交互模式：侧边栏 → 分类页面

```
┌── ModalNavigationDrawer ────┐
│                             |
│   设置                       
│   ─────────                 │
│   📱  通用      →            │    点击 → 导航到分类页面
│   🎤  语音      →            │    (full-screen / bottom sheet)
│   🌐  翻译      →            │
│   🔌  高级      →            │
│                             │
│   ─────────                 | 
│   关于                       
│                             │
└─────────────────────────────┘
```

#### 实现方式：Compose 导航状态机（不引入 Navigation 库）

```kotlin
// UI 导航状态（新增）
private enum class SettingsScreen {
    Main,           // 侧边栏主页（4 大分类）
    General,        // 通用设置页
    Voice,          // 语音设置页
    Translation,    // 翻译设置页
    Advanced,       // 高级设置页
}

private var _settingsScreen by mutableStateOf(SettingsScreen.Main)

@Composable
private fun DrawerContent() {
    Column(Modifier.fillMaxHeight()) {
        // 顶部面包屑 + 返回按钮（非 Main 页时）
        if (_settingsScreen != SettingsScreen.Main) {
            Row(verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                IconButton({ _settingsScreen = SettingsScreen.Main }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                }
                Text(screenTitle(_settingsScreen), fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
            HorizontalDivider()
        }

        Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            when (_settingsScreen) {
                SettingsScreen.Main -> SettingsMainPage()
                SettingsScreen.General -> GeneralSettingsPage()
                SettingsScreen.Voice -> VoiceSettingsPage()
                SettingsScreen.Translation -> TranslationSettingsPage()
                SettingsScreen.Advanced -> AdvancedSettingsPage()
            }
        }
    }
}

@Composable
private fun SettingsMainPage() {
    Column {
        Text("设置", fontWeight = FontWeight.Bold, fontSize = 22.sp, modifier = Modifier.padding(bottom = 16.dp))
        CategoryEntry("通用", "基本偏好与段落聚合", Icons.Default.Tune, SettingsScreen.General)
        CategoryEntry("语音", "ASR / TTS / 音频设备", Icons.Default.Mic, SettingsScreen.Voice)
        CategoryEntry("翻译", "引擎 / 词库 / 润色", Icons.Default.Language, SettingsScreen.Translation)
        CategoryEntry("高级", "媒体转译 / API / 日志", Icons.Default.Settings, SettingsScreen.Advanced)

        Spacer(Modifier.height(20.dp))
        HorizontalDivider()
        Spacer(Modifier.height(12.dp))
        Text("关于", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
        Text("RealTimeTranslateTTS v${BuildConfig.VERSION_NAME}", fontSize = 11.sp,
             color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
    }
}

@Composable
private fun CategoryEntry(
    title: String,
    subtitle: String,
    icon: ImageVector,
    screen: SettingsScreen
) {
    Surface(
        onClick = { _settingsScreen = screen },
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(subtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null,
                 Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
        }
    }
}
```

#### 分类页面内容映射

```kotlin
@Composable
private fun VoiceSettingsPage() {
    // 三级菜单：仍然使用 CollapsibleSection 作为第三级折叠
    CollapsibleSection("语音识别引擎", Icons.Default.Mic) { /* 原 ASR 选项 */ }
    CollapsibleSection("语音合成引擎", Icons.AutoMirrored.Filled.VolumeUp) { /* 原 TTS 选项 */ }
    CollapsibleSection("音频设备", Icons.Default.Headphones) { /* 原音频设备 */ }
    CollapsibleSection("智能过滤", Icons.Default.FilterList) { /* 原智能过滤 */ }
}

@Composable
private fun TranslationSettingsPage() {
    CollapsibleSection("翻译引擎", Icons.Default.Language) { /* 原翻译引擎 */ }
    CollapsibleSection("翻译增强", Icons.Default.Tune) { /* 原翻译增强 */ }
    CollapsibleSection("术语库管理", Icons.Default.LibraryBooks) { /* 原术语库 */ }
    CollapsibleSection("AI 润色", Icons.Default.AutoAwesome) { /* 原 AI 润色 */ }
}

@Composable
private fun AdvancedSettingsPage() {
    CollapsibleSection("媒体转译", Icons.Default.Audiotrack) { /* 原媒体转译 */ }
    CollapsibleSection("悬浮窗模式", Icons.Default.Layers) { /* 原悬浮窗 */ }
    CollapsibleSection("API 密钥管理", Icons.Default.VpnKey) { /* 原 API 管理 */ }
    CollapsibleSection("API 连通测试", Icons.Default.Wifi) { /* 原 API 测试 */ }
    CollapsibleSection("系统日志", Icons.Default.List) { /* 原日志 */ }
}

@Composable
private fun GeneralSettingsPage() {
    CollapsibleSection("基本设置", Icons.Default.Settings, defaultExpanded = true) {
        SettingSwitch("自动播报翻译", _autoSpeak) { _autoSpeak = it; saveBool("auto_speak", it) }
        // 未来可加：主题 / 语言切换
    }
    CollapsibleSection("段落聚合", Icons.Default.FormatListBulleted, defaultExpanded = true) {
        // 见 0.2.2 段落聚合控制
    }
}
```

#### 返回键行为

系统返回键在 Drawer 打开且非 Main 页时：返回到 Main 页，不关闭 Drawer：

```kotlin
BackHandler(enabled = drawerState.isOpen && _settingsScreen != SettingsScreen.Main) {
    _settingsScreen = SettingsScreen.Main
}
```

---

### 0.2.2 段落聚合控制

#### 新增配置字段

```kotlin
// 段落聚合配置（新增）
private var _paragraphAutoGroup by mutableStateOf(true)         // 总开关
private var _paragraphMaxSegments by mutableStateOf(8)          // 每段最大句数（1-20）
private var _paragraphSilenceMs by mutableStateOf(4000)         // 静音断句毫秒（1000-10000）
```

#### SharedPreferences 持久化

```kotlin
// 在 loadSettings() 中加入：
_paragraphAutoGroup = p.getBoolean("paragraph_auto_group", true)
_paragraphMaxSegments = p.getInt("paragraph_max_segments", 8)
_paragraphSilenceMs = p.getInt("paragraph_silence_ms", 4000)
```

#### 修改 `addSegmentToParagraph()` 逻辑

```kotlin
private fun addSegmentToParagraph(text: String) {
    _currentPartial = ""
    paragraphSilenceJob?.cancel()

    // 根据聚合开关决定段落行为
    val shouldCreateNewParagraph = if (!_paragraphAutoGroup) {
        true  // 关闭聚合：每句一段
    } else {
        _paragraphs.isEmpty() ||
        (_paragraphs.last().allDone && _paragraphs.last().segments.size >= _paragraphMaxSegments)
    }

    if (shouldCreateNewParagraph) {
        _paragraphs.add(Paragraph(id = _nextParagraphId++))
    }

    val paraIdx = _paragraphs.lastIndex
    val para = _paragraphs[paraIdx]
    val seqId = translationPipeline.allocateSeqId()
    val newSeg = Segment(seqId = seqId, en = text, translating = true)
    _paragraphs[paraIdx] = para.copy(segments = para.segments + newSeg)

    log("提交翻译 seq=$seqId: ${text.take(40)}")
    translationHistory.appendPending(seqId, text)
    translationPipeline.submitSentence(seqId, para.id, text)

    if (_paragraphAutoGroup) {
        // 只在启用聚合时调度静音断句
        val currentParaId = para.id
        paragraphSilenceJob = lifecycleScope.launch {
            delay(_paragraphSilenceMs.toLong())
            if (_paragraphs.isNotEmpty()) {
                val lastPara = _paragraphs.last()
                if (lastPara.id == currentParaId && lastPara.segments.isNotEmpty()) {
                    if (lastPara.allDone) {
                        translationPipeline.closeParagraph(currentParaId)
                    }
                    _paragraphs.add(Paragraph(id = _nextParagraphId++))
                }
            }
        }
    } else {
        // 关闭聚合：每句立即 closeParagraph（触发润色）
        translationPipeline.closeParagraph(para.id)
    }
}
```

#### UI 控件（在"通用 → 段落聚合"分区）

```kotlin
CollapsibleSection("段落聚合", Icons.Default.FormatListBulleted, defaultExpanded = true) {
    Text("关闭后每句独立显示，不会自动合并为段落", fontSize = 10.sp,
         color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
         modifier = Modifier.padding(bottom = 6.dp))

    SettingSwitch("自动聚合段落", _paragraphAutoGroup) {
        _paragraphAutoGroup = it
        saveBool("paragraph_auto_group", it)
    }

    AnimatedVisibility(_paragraphAutoGroup) {
        Column(Modifier.padding(start = 8.dp, top = 6.dp)) {
            Text("每段最大句数：$_paragraphMaxSegments", fontSize = 12.sp)
            Slider(
                value = _paragraphMaxSegments.toFloat(),
                onValueChange = { _paragraphMaxSegments = it.toInt() },
                onValueChangeFinished = { saveInt("paragraph_max_segments", _paragraphMaxSegments) },
                valueRange = 1f..20f, steps = 18
            )

            Text("静音断句：${_paragraphSilenceMs / 1000f}s", fontSize = 12.sp)
            Slider(
                value = _paragraphSilenceMs.toFloat(),
                onValueChange = { _paragraphSilenceMs = it.toInt() },
                onValueChangeFinished = { saveInt("paragraph_silence_ms", _paragraphSilenceMs) },
                valueRange = 1000f..10000f, steps = 8
            )
        }
    }

    if (!_paragraphAutoGroup) {
        Surface(color = Color(0xFFFFF3CD), shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
            Text("⚠ 关闭聚合时，每句都会触发 AI 润色（如启用），可能增加 API 调用次数",
                 fontSize = 10.sp, color = Color(0xFF856404),
                 modifier = Modifier.padding(8.dp))
        }
    }
}
```

---

### 0.2.3 会话列表重构

#### 数据模型调整

`TranslationHistory.Session` 增加显示序号：

```kotlin
// 新增虚拟属性（不持久化，按当前列表位置计算）
fun sessionNumber(session: Session): Int {
    // 按创建时间升序的序号，显示为 #1, #2, ...
    return sessions.indexOf(session) + 1
}
```

或者在 UI 层计算：

```kotlin
// HistoryArea 中：
val sortedByTime = _historySessions.sortedBy { it.startTime }
val numberMap = sortedByTime.withIndex().associate { (i, s) -> s.id to (i + 1) }
```

#### 新增"新建对话"入口

**位置**：`HistoryArea` 顶部工具栏

```kotlin
// HistoryArea header（替换原有的"翻译历史 (N)" + "清空"按钮）
Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically) {
    Text("翻译历史", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    Text(" · ${filtered.size}", fontSize = 12.sp,
         color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
    Spacer(Modifier.weight(1f))

    // [新增] 新建对话按钮
    TextButton(onClick = { onNewConversation() }) {
        Icon(Icons.Default.Add, null, Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Text("新建对话", fontSize = 12.sp)
    }

    // 原清空按钮（改为带确认）
    if (_historySessions.isNotEmpty()) {
        TextButton(onClick = { _showClearAllDialog = true }) {
            Text("清空全部", fontSize = 12.sp, color = Color(0xFFEF5350))
        }
    }
}
```

**新建对话函数**：

```kotlin
private fun onNewConversation() {
    // 如果正在录音，先停止
    if (_recording) {
        log("请先停止录音再新建对话")
        return  // 或者弹出提示
    }

    // 1) 刷新当前段落到历史（如果有未保存数据，append 已持续落库）
    translationHistory.flush()

    // 2) 清空 UI 状态（不删除历史会话，只清当前界面）
    paragraphSilenceJob?.cancel()
    _paragraphs.clear()
    _nextParagraphId = 0
    _currentPartial = ""
    translationPipeline.reset()

    // 3) 创建新会话
    translationHistory.newSession()
    _historySessions.clear()
    _historySessions.addAll(translationHistory.allSessions())

    log("已新建对话")
}
```

#### 会话卡片新版：加入序号

```kotlin
// HistoryArea 的会话卡片（替换 Line 2762-2808）
items(filtered.size) { i ->
    val idx = filtered.size - 1 - i      // reverseLayout = true
    val session = filtered[idx]
    val num = numberMap[session.id] ?: 0
    val isCurrent = session.id == translationHistory.currentSession()?.id

    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isCurrent)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f)
            else MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(1.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            // 标题行：序号徽章 + 标题 + 编辑/删除
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                // [新增] 序号徽章
                Surface(
                    shape = CircleShape,
                    color = if (isCurrent) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.size(28.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("#$num", fontSize = 11.sp, fontWeight = FontWeight.Bold,
                             color = if (isCurrent) Color.White
                                     else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    }
                }
                Spacer(Modifier.width(8.dp))

                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(session.displayTitle, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                             maxLines = 1, overflow = TextOverflow.Ellipsis,
                             modifier = Modifier.weight(1f, fill = false))
                        if (isCurrent) {
                            Spacer(Modifier.width(6.dp))
                            Surface(shape = RoundedCornerShape(4.dp),
                                    color = MaterialTheme.colorScheme.primary) {
                                Text("当前", fontSize = 9.sp, color = Color.White,
                                     modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp))
                            }
                        }
                    }
                }

                IconButton(onClick = {
                    _editingSessionId = session.id
                    _editingTitle = session.title
                }, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Edit, "重命名", Modifier.size(14.dp))
                }

                // [修改] 删除改为先弹确认
                IconButton(onClick = { _pendingDeleteSessionId = session.id },
                           modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Delete, "删除", Modifier.size(14.dp),
                         tint = Color(0xFFEF5350).copy(alpha = 0.7f))
                }
            }

            // 时间 + 句数
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Text(sdf.format(Date(session.startTime)), fontSize = 10.sp,
                     color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f))
                Spacer(Modifier.weight(1f))
                Text("${session.entries.size} 句", fontSize = 10.sp,
                     color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f))
            }

            Spacer(Modifier.height(4.dp))
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
```

#### 删除确认对话框

新增状态：

```kotlin
private var _pendingDeleteSessionId by mutableStateOf<String?>(null)
private var _showClearAllDialog by mutableStateOf(false)
```

确认对话框：

```kotlin
// 单会话删除确认
if (_pendingDeleteSessionId != null) {
    val target = _historySessions.find { it.id == _pendingDeleteSessionId }
    val isCurrent = _pendingDeleteSessionId == translationHistory.currentSession()?.id
    AlertDialog(
        onDismissRequest = { _pendingDeleteSessionId = null },
        icon = { Icon(Icons.Default.Warning, null, tint = Color(0xFFEF5350)) },
        title = { Text("删除对话？") },
        text = {
            Column {
                Text("\"${target?.displayTitle}\" 将被永久删除（${target?.entries?.size ?: 0} 条记录），此操作无法撤销。")
                if (isCurrent) {
                    Spacer(Modifier.height(8.dp))
                    Text("⚠ 这是当前对话，删除后主界面将被清空。",
                         fontSize = 12.sp, color = Color(0xFFEF5350))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                handleDeleteSession(_pendingDeleteSessionId!!)
                _pendingDeleteSessionId = null
            }) { Text("删除", color = Color(0xFFEF5350)) }
        },
        dismissButton = {
            TextButton({ _pendingDeleteSessionId = null }) { Text("取消") }
        }
    )
}

// 清空全部确认
if (_showClearAllDialog) {
    AlertDialog(
        onDismissRequest = { _showClearAllDialog = false },
        icon = { Icon(Icons.Default.Warning, null, tint = Color(0xFFEF5350)) },
        title = { Text("清空所有历史？") },
        text = { Text("将删除所有 ${_historySessions.size} 个对话，此操作无法撤销。") },
        confirmButton = {
            TextButton(onClick = {
                handleClearAllSessions()
                _showClearAllDialog = false
            }) { Text("清空", color = Color(0xFFEF5350)) }
        },
        dismissButton = {
            TextButton({ _showClearAllDialog = false }) { Text("取消") }
        }
    )
}
```

#### 修复删除逻辑

```kotlin
private fun handleDeleteSession(sessionId: String) {
    val isCurrent = sessionId == translationHistory.currentSession()?.id
    val wasRecording = _recording

    // 1) 如果正在录音且删的是当前会话，先停止录音
    if (isCurrent && wasRecording) {
        log("已停止录音（因删除当前会话）")
        toggleRecording()  // 停止录音
    }

    // 2) 如果是当前会话，清空 UI 状态
    if (isCurrent) {
        paragraphSilenceJob?.cancel()
        _paragraphs.clear()
        _nextParagraphId = 0
        _currentPartial = ""
        translationPipeline.reset()
    }

    // 3) 删除历史
    translationHistory.deleteSession(sessionId)

    // 4) 如果删除的是当前会话，自动切换到最新会话或创建新会话
    if (isCurrent) {
        if (translationHistory.allSessions().isNotEmpty()) {
            translationHistory.continueOrNew()  // 切到最新一个
        } else {
            translationHistory.newSession()     // 没有历史就新建
        }
    }

    // 5) 刷新列表
    _historySessions.clear()
    _historySessions.addAll(translationHistory.allSessions())
    log("已删除对话")
}

private fun handleClearAllSessions() {
    // 如果正在录音，先停止
    if (_recording) toggleRecording()

    // 清空 UI
    paragraphSilenceJob?.cancel()
    _paragraphs.clear()
    _nextParagraphId = 0
    _currentPartial = ""
    translationPipeline.reset()

    // 清空历史
    translationHistory.clearAll()
    translationHistory.newSession()  // 创建一个空的新会话作为当前
    _historySessions.clear()
    _historySessions.addAll(translationHistory.allSessions())
    log("已清空所有历史")
}
```

---

### 0.2.4 主界面顶栏重新设计

配合抽屉导航的简化，顶部状态栏也需要优化信息密度：

```kotlin
TopAppBar(
    navigationIcon = { IconButton({ scope.launch { drawerState.open() } }) { Icon(Icons.Default.Menu, "菜单") } },
    title = {
        Column {
            Text("实时语音翻译", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            // [增强] 显示当前会话序号 + ASR 引擎
            val currentSession = translationHistory.currentSession()
            val num = currentSession?.let {
                translationHistory.allSessions().sortedBy { s -> s.startTime }.indexOf(it) + 1
            } ?: 0
            Text(
                "#$num · ${asrLabel()}",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
        }
    },
    actions = {
        StatusDot(asrReady(), "ASR"); StatusDot(translationReady(), "翻译"); StatusDot(_ttsReady && _ttsLangOk, "TTS")
        Spacer(Modifier.width(4.dp))

        // [新增] 新建对话（主界面快捷入口）
        IconButton(onClick = { onNewConversation() }) {
            Icon(Icons.Default.AddCircleOutline, "新建对话")
        }

        // 历史按钮
        IconButton({ _showHistory = !_showHistory }) {
            Icon(Icons.Default.History, "历史",
                 tint = if (_showHistory) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        }

        // [替换原"清空"按钮] 清空当前界面（不删历史）
        IconButton(onClick = {
            paragraphSilenceJob?.cancel()
            _paragraphs.clear()
            _nextParagraphId = 0
            _currentPartial = ""
            translationPipeline.reset()
        }) { Icon(Icons.Default.ClearAll, "清空当前") }
    }
)
```

---

## 0.3 修改文件清单

| 文件 | 修改性质 | 说明 |
|------|---------|------|
| `MainActivity.kt` | 重构 | `DrawerContent()` 拆分为 5 个分类页面 + 导航状态机 |
| `MainActivity.kt` | 新增 | `SettingsScreen` 枚举、`CategoryEntry` / 页面 Composable |
| `MainActivity.kt` | 新增 | `onNewConversation()` / `handleDeleteSession()` / `handleClearAllSessions()` |
| `MainActivity.kt` | 新增 | `_paragraphAutoGroup` / `_paragraphMaxSegments` / `_paragraphSilenceMs` 状态 |
| `MainActivity.kt` | 新增 | `_pendingDeleteSessionId` / `_showClearAllDialog` 状态 |
| `MainActivity.kt` | 新增 | `_settingsScreen` 状态 + `BackHandler` |
| `MainActivity.kt` | 修改 | `addSegmentToParagraph()` 读取聚合配置 |
| `MainActivity.kt` | 修改 | `loadSettings()` 加载新增偏好 |
| `MainActivity.kt` | 修改 | `HistoryArea()` 加入序号徽章、确认对话框、新建按钮 |
| `MainActivity.kt` | 修改 | `TopAppBar` 加入新建对话按钮、显示会话序号 |

**无新文件创建**；**无非 UI 逻辑被破坏**。

---

## 0.4 验收标准

### 侧边栏三级菜单

- [x] 首次打开抽屉显示 4 大分类 + 关于信息
- [x] 点击分类进入对应页面，顶部有返回按钮
- [x] 系统返回键在子页面返回到主页（不关闭抽屉）
- [x] 子页面滚动流畅，内容不重叠
- [x] 每个子页面的 CollapsibleSection 默认全部折叠（除非特别标注默认展开）

### 段落聚合控制

- [x] 开关可切换，即时生效（新产生的句子遵循新规则）
- [x] 关闭聚合后，每句单独成段显示
- [x] 关闭聚合时，`closeParagraph` 每句触发（润色每句应用）
- [x] 句数滑条（1-20）实时显示当前值，松开后保存
- [x] 静音滑条（1-10s）实时显示当前值，松开后保存
- [x] 关闭聚合时显示黄色警告提示

### 会话列表

- [x] 每个会话卡片左侧显示 `#N` 序号徽章
- [x] 当前会话卡片有高亮背景和"当前"标签
- [x] 历史页顶部有"新建对话"按钮
- [x] 顶栏有"新建对话"图标按钮
- [x] 点击新建对话后，主界面段落清空，新会话成为当前
- [x] 正在录音时点击"新建对话"给出提示（或自动停止录音）
- [x] 点击单个会话的删除图标，弹出确认对话框
- [x] 删除当前会话后，主界面自动清空并切换到最新会话
- [x] 点击"清空全部"弹出二次确认
- [x] 清空全部后自动创建一个新的空会话

---

## 0.5 风险与注意事项

### 风险 1：状态保持

**场景**：用户在"翻译"分类页面调整了 API Key，然后旋转屏幕。

**风险**：`_settingsScreen` 状态可能丢失，退回到 Main 页。

**规避**：将 `_settingsScreen` 改为 `rememberSaveable` 或写入 SharedPreferences。

### 风险 2：段落聚合与润色的耦合

**场景**：用户关闭聚合 + 启用 AI 润色。

**风险**：每句都触发一次润色 API 调用，增加费用和延迟。

**规避**：
- UI 明显提示（已在 0.2.2 中加入黄色警告）
- 可选方案：关闭聚合时自动禁用润色
- 或引入 `refineParagraphOnly` 开关，仅对多句段落润色

### 风险 3：删除当前会话时正在翻译

**场景**：用户点击删除按钮时，后台还有未完成的翻译 seqId。

**风险**：翻译结果回来后会写入已删除或新创建的会话，造成数据错乱。

**规避**：
- `handleDeleteSession` 中先调用 `translationPipeline.reset()`，取消所有在途翻译的回调
- 或调用 `translationPipeline.cleanupExpiredParagraphs()` 强制回收

### 风险 4：序号动态变化

**场景**：用户删除 #2 会话，#3 变成 #2。

**现状**：顶栏显示的"#N"会跟着变化。

**权衡**：这是期望行为（序号代表在列表中的位置，便于用户快速识别"第几个"）。

**替代方案**：若希望序号稳定，可将 `number` 持久化为 Session 字段（首次创建时分配递增 ID，永不重用）。

### 风险 5：旧会话的迁移

**场景**：现有用户已有翻译历史。

**风险**：重构后会话卡片显示正常，但序号可能与用户印象中的"第几个"不符。

**规避**：本次仅调整 UI，不迁移数据结构，旧会话按创建时间自动编号即可。

---

## 0.6 设计决策记录（ADR）

### ADR-1：为什么不用 Navigation-Compose 库？

**选项**：
- A. 使用 `androidx.navigation:navigation-compose`
- B. 自定义状态机（当前选择）

**决策**：B

**理由**：
- 项目目前无导航库依赖，引入需增加 APK 体积
- 设置页面之间不需要深链、参数传递
- 4 个顶级页面用 `when (screen)` 足以应对
- `BackHandler` 能处理返回键

### ADR-2：三级菜单 vs 标签页（Tabs）

**选项**：
- A. 三级菜单（抽屉 → 分类页面 → 折叠区）（当前选择）
- B. 抽屉 → Tabs（分类） → 折叠区
- C. 抽屉 → 分区列表（一屏显示）

**决策**：A

**理由**：
- Tabs 在横向空间有限（窄手机）时会显示拥挤
- 分区列表虽然一屏可见但回到了扁平问题
- 分类页面模式符合 Android 系统设置的主流交互

### ADR-3：段落聚合开关默认开

**决策**：默认 `false`

**理由**：
- 保持与当前用户行为兼容
- 开启是进阶功能

### ADR-4：会话序号动态计算 vs 持久化

**决策**：动态计算

**理由**：
- 实现简单，不需要迁移数据
- 用户删除会话后重新编号符合直觉（"现在有 5 个对话，这是第 3 个"）
- 如果有用户反馈需要稳定编号，可在 V2 引入持久化序号

---

## 0.7 实施顺序

```
Day 1: 抽屉导航重构（Main/General/Voice/Translation/Advanced 5 个页面）
Day 2: 段落聚合开关 + 滑条 UI + 业务逻辑接入
Day 3: 会话列表序号 + 新建对话入口
Day 4: 删除确认对话框 + handleDeleteSession 修复
Day 5: 联调、BackHandler、ADR 验证、文档更新
```
