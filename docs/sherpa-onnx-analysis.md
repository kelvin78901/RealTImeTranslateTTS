# Sherpa-ONNX 开源项目分析 & 与 RealTimeTranslateTTS 整合方案

> 分析日期：2026-04-04
> sherpa-onnx 版本：1.12.35
> 源码路径：`/Users/kelvin/Downloads/sherpa-onnx-master`

---

## 一、Sherpa-ONNX 项目概述

Sherpa-ONNX 是由 [k2-fsa](https://github.com/k2-fsa) 团队开发的 **工业级语音 AI 框架**，以 ONNX Runtime 为统一推理后端，覆盖 **11 大类语音处理功能**，支持 **12 种编程语言**、**15+ 硬件架构**。

### 核心定位
- **全离线可用**：所有功能均可纯本地运行，无需网络
- **全平台覆盖**：Android / iOS / Linux / macOS / Windows / WebAssembly / HarmonyOS
- **模型生态丰富**：30+ 模型架构，200+ 预训练模型变体
- **统一 C++ 内核 + 多语言绑定**：JNI → Kotlin/Java，PyBind → Python，CGo → Go，等

---

## 二、Sherpa-ONNX 功能全景

| 功能 | 流式 | 离线 | 支持的模型数 | 说明 |
|------|:----:|:----:|:-----------:|------|
| **语音识别 (ASR)** | ✓ | ✓ | 20+ | Whisper, Zipformer, Paraformer, SenseVoice, Qwen3, Moonshine 等 |
| **文本转语音 (TTS)** | — | ✓ | 7 系列 | VITS, Matcha, Kokoro, ZipVoice, Kitten, Pocket, Supertonic |
| **语音活动检测 (VAD)** | ✓ | ✓ | 2 | Silero VAD, Ten-VAD |
| **关键词检测 (KWS)** | ✓ | — | 1 | 自定义唤醒词，Transducer 架构 |
| **说话人识别** | ✓ | ✓ | 2 | 声纹提取 + 注册/搜索/验证 |
| **说话人分离** | — | ✓ | 1 | Pyannote 分割 + 聚类 |
| **语种识别** | ✓ | ✓ | 1 | Whisper 多语种（90+ 语言） |
| **音频标签** | — | ✓ | 1 | 环境音/音乐/语音事件分类 |
| **标点恢复** | ✓ | ✓ | 2 | CT-Transformer / CNN-BiLSTM |
| **语音增强/降噪** | ✓ | ✓ | 2 | GTCRN, DPDFNet |
| **音源分离** | — | ✓ | 2 | Spleeter, UVR（人声/伴奏分离） |

---

## 三、当前项目已使用的 Sherpa-ONNX 模块

当前 RealTimeTranslateTTS 已通过 AAR 引入 sherpa-onnx，但仅用了两个功能：

| 已用模块 | 实现文件 | 使用方式 |
|---------|---------|---------|
| **离线 Whisper ASR** | `asr/SherpaWhisperAsr.kt` | OfflineRecognizer + Silero VAD，支持 6 种 Whisper 模型大小，HuggingFace 自动下载 |
| **离线 VITS TTS** | `tts/SherpaOnnxTts.kt` | OfflineTts (VITS 模型)，双模式加载（文件/Asset），反射兼容多版本 JNI |

**利用率**：约 **18%**（2/11 功能）

---

## 四、Sherpa-ONNX 比当前方案做得好的地方

### 4.1 流式 ASR（Streaming ASR）—— 当前项目的最大短板

**当前方案问题**：
- Vosk 离线 ASR 准确率一般，模型老旧
- Whisper ASR 是非流式的（需要 VAD 截断后整段送入），延迟高
- 系统 ASR 需联网

**Sherpa-ONNX 优势**：
- 提供 **真正的流式 ASR**（OnlineRecognizer），支持 Zipformer、Paraformer Streaming、NeMo CTC 等
- 边说边出文字，延迟低至 200ms
- 内置 endpoint 检测（可配置静音阈值），无需额外 VAD 来截断
- 支持 **热词/上下文增强**（hotwords），适合专业场景
- 模型体积小（Zipformer bilingual zh-en 仅 ~70MB），适合移动端

**影响**：替换 Vosk 后，离线 ASR 延迟和准确率都会大幅提升。

### 4.2 TTS 模型丰富度

**当前方案**：仅支持 VITS 一种本地模型

**Sherpa-ONNX 支持 7 种 TTS 系列**：
| 模型 | 特点 | 适用场景 |
|------|------|---------|
| **VITS** | 当前已用 | 基础离线 TTS |
| **Kokoro** | 31+ 音色，支持中英双语，极轻量 | **推荐替换 VITS** |
| **Matcha** | Flow-matching，中英双语，高质量 | 质量优先场景 |
| **ZipVoice** | 零样本声音克隆 | 个性化语音 |
| **Pocket TTS** | 实时声音克隆，缓存加速 | 多角色会话翻译 |
| **Kitten** | 超轻量，9 音色 | 资源受限设备 |
| **Supertonic** | 风格可控，多语种 | 高级定制 |

### 4.3 完整的语音处理流水线

Sherpa-ONNX 的架构设计是一条完整的流水线：

```
麦克风 → 降噪(Denoiser) → VAD → ASR → 标点恢复 → 翻译(你的) → TTS → 扬声器
                                    ↓
                              说话人识别 / 语种识别
```

当前项目只实现了 `VAD → ASR → 翻译 → TTS` 的核心链路，缺少降噪、标点恢复、说话人识别等增强环节。

### 4.4 流式语音降噪（OnlineSpeechDenoiser）

**当前方案**：依赖系统 AEC（AcousticEchoCanceler），效果有限

**Sherpa-ONNX**：
- 提供专用降噪模型（GTCRN），支持流式处理
- 在 ASR 之前对音频降噪，显著提升嘈杂环境下的识别率
- API 简单：`denoiser.run(samples, sampleRate) → DenoisedAudio`

### 4.5 标点恢复（OnlinePunctuation）

**当前方案**：ASR 输出无标点，直接送翻译

**Sherpa-ONNX**：
- CNN-BiLSTM 流式标点恢复模型
- `punctuation.addPunctuation("hello world how are you") → "Hello world, how are you?"`
- 大写字母 + 逗号/句号/问号自动添加
- 可提升翻译引擎对句子边界的理解

### 4.6 说话人识别与分离

**当前方案**：不区分说话人，多人对话时翻译结果混杂

**Sherpa-ONNX**：
- **声纹提取 + 管理**：注册说话人 → 实时识别当前说话人 → 标注翻译归属
- **说话人分离**：离线分析录音，区分不同说话人段落
- 适用于会议翻译、多人对话等场景

### 4.7 语种识别（SpokenLanguageIdentification）

**当前方案**：硬编码为英→中翻译

**Sherpa-ONNX**：
- 基于 Whisper 的语种识别（90+ 语种）
- 可自动检测输入语言，切换翻译方向
- 对多语种场景非常有价值

---

## 五、可直接复用的模块

### 5.1 直接可用（已有 AAR，仅需调用新 API）

| 模块 | 复杂度 | 价值 | 说明 |
|------|:------:|:----:|------|
| **流式 ASR (OnlineRecognizer)** | 中 | ★★★★★ | 替换 Vosk，大幅提升离线 ASR 质量和延迟 |
| **Kokoro TTS** | 低 | ★★★★★ | 替换 VITS，31+ 音色，中英双语 |
| **在线标点恢复** | 低 | ★★★★ | ASR 后自动加标点，提升翻译质量 |
| **语音降噪** | 低 | ★★★★ | 嘈杂环境下提升 ASR 准确率 |
| **语种识别** | 低 | ★★★ | 自动检测源语言 |
| **Matcha TTS** | 低 | ★★★ | 比 VITS 更高质量的中文 TTS |
| **关键词检测** | 中 | ★★★ | 唤醒词 / 免触摸启动 |
| **说话人识别** | 中 | ★★★ | 多人会话区分说话人 |

### 5.2 需要更新 AAR 版本

当前项目使用的 sherpa-onnx AAR 可能较旧，新功能（Kokoro、标点恢复、降噪、语种识别）需要 **更新到 1.12.x 版本的 AAR**。

两种途径：
1. 从 [sherpa-onnx releases](https://github.com/k2-fsa/sherpa-onnx/releases) 下载最新预编译 AAR
2. 按 `build-android-arm64-v8a.sh` 自编译（可裁剪不需要的功能减小体积）

---

## 六、整合方案（按优先级）

### Phase 1：高价值低风险（1-3 天）

#### 1A. 流式 ASR 替换 Vosk

**目标**：用 sherpa-onnx OnlineRecognizer 替换 Vosk，实现真正的流式离线识别

**实现步骤**：
1. 更新 sherpa-onnx AAR 到最新版本
2. 下载 Zipformer bilingual (zh-en) 模型（~70MB）
3. 新建 `asr/SherpaStreamingAsr.kt`，参考 `sherpa-onnx/kotlin-api/OnlineRecognizer.kt`
4. 核心代码模式：

```kotlin
// 初始化（一次）
val config = OnlineRecognizerConfig(
    modelConfig = OnlineModelConfig(
        transducer = OnlineTransducerModelConfig(
            encoder = "encoder.onnx",
            decoder = "decoder.onnx",
            joiner = "joiner.onnx"
        ),
        tokens = "tokens.txt",
        numThreads = 2
    ),
    featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
    endpointConfig = EndpointConfig(
        rule1 = EndpointRule(mustContainNonSilence = false, minTrailingSilence = 2.4f),
        rule2 = EndpointRule(mustContainNonSilence = true, minTrailingSilence = 1.2f),
        rule3 = EndpointRule(mustContainNonSilence = false, minUtteranceLength = 20.0f)
    ),
    enableEndpoint = true
)
val recognizer = OnlineRecognizer(assetManager, config)
val stream = recognizer.createStream()

// 流式循环（每 ~100ms）
stream.acceptWaveform(samples, sampleRate = 16000)
while (recognizer.isReady(stream)) {
    recognizer.decode(stream)
}
val partial = recognizer.getResult(stream).text   // 实时 partial 结果

if (recognizer.isEndpoint(stream)) {
    val final = recognizer.getResult(stream).text  // 最终结果
    recognizer.reset(stream)                       // 重置继续下一句
}
```

**优势**：
- 延迟从 Vosk 的 ~500ms 降到 ~200ms
- 中英双语准确率大幅提升
- 内置 endpoint 检测，不再需要手动 VAD 截断

#### 1B. Kokoro TTS 替换/补充 VITS

**目标**：新增 Kokoro TTS 选项，31+ 音色，中英双语

**实现步骤**：
1. 下载 `kokoro-multi-lang-v1_0` 模型
2. 在 `SherpaOnnxTts.kt` 中新增 Kokoro 初始化路径：

```kotlin
val config = OfflineTtsConfig(
    model = OfflineTtsModelConfig(
        kokoro = OfflineTtsKokoroModelConfig(
            model = "model.onnx",
            voices = "voices.bin",
            tokens = "tokens.txt",
            dataDir = "espeak-ng-data",
            lengthScale = 1.0f
        ),
        numThreads = 2
    )
)
val tts = OfflineTts(assetManager, config)
val audio = tts.generate("你好世界", sid = 0, speed = 1.0f)
```

#### 1C. 在线标点恢复

**目标**：ASR 输出自动加标点，提升翻译质量

**实现步骤**：
1. 下载 CNN-BiLSTM 标点模型（~5MB）
2. 在 ASR 输出后、翻译前插入标点恢复：

```kotlin
val punctConfig = OnlinePunctuationConfig(
    model = OnlinePunctuationModelConfig(
        cnnBiLstm = "model.onnx",
        bpeVocab = "bpe.vocab"
    )
)
val punct = OnlinePunctuation(assetManager, punctConfig)

// 在 ASR 结果回调中：
val withPunct = punct.addPunctuation(asrText)
// 然后把 withPunct 送入翻译流水线
```

### Phase 2：中等价值（3-5 天）

#### 2A. 语音降噪

在 ASR 前对音频流降噪：

```kotlin
val denoiserConfig = OnlineSpeechDenoiserConfig(
    model = OfflineSpeechDenoiserModelConfig(
        gtcrn = OfflineSpeechDenoiserGtcrnModelConfig(model = "gtcrn_simple.onnx")
    )
)
val denoiser = OnlineSpeechDenoiser(assetManager, denoiserConfig)

// 在音频采集循环中：
val denoised = denoiser.run(samples, sampleRate = 16000)
// 将 denoised.samples 送入 ASR
```

#### 2B. 语种自动识别

检测输入音频的语种，自动切换翻译方向：

```kotlin
val lidConfig = getSpokenLanguageIdentificationConfig(type = 0) // whisper tiny
val lid = SpokenLanguageIdentification(assetManager, lidConfig)
val stream = lid.createStream()
stream.acceptWaveform(samples, sampleRate = 16000)
val lang = lid.compute(stream)  // "en", "zh", "ja", "ko", ...
```

#### 2C. 说话人识别

多人会话时标注说话人：

```kotlin
// 初始化
val extractor = SpeakerEmbeddingExtractor(assetManager, extractorConfig)
val manager = SpeakerEmbeddingManager(extractor.dim())

// 注册说话人（enrollment）
val stream = extractor.createStream()
stream.acceptWaveform(samples, sampleRate)
val embedding = extractor.compute(stream)
manager.add("Speaker_A", embedding)

// 实时识别
val testEmbedding = extractor.compute(testStream)
val speaker = manager.search(testEmbedding, threshold = 0.5f)
// speaker = "Speaker_A" 或 "" (未知)
```

### Phase 3：进阶功能（5-10 天）

#### 3A. 关键词检测（免触控启动）

使用 KWS 实现 "Hey Translate" 唤醒：

```kotlin
val kwsConfig = KeywordSpotterConfig(
    modelConfig = OnlineModelConfig(...),
    keywords = "hey translate\nhello\n你好"
)
val kws = KeywordSpotter(assetManager, kwsConfig)
```

#### 3B. 完整流水线整合

```
麦克风
  ↓ 降噪 (OnlineSpeechDenoiser)
  ↓ VAD (Silero/Ten-VAD)
  ↓ 语种识别 (SpokenLanguageIdentification)
  ↓ 说话人识别 (SpeakerEmbeddingExtractor)
  ↓ 流式 ASR (OnlineRecognizer)
  ↓ 标点恢复 (OnlinePunctuation)
  ↓ 翻译 (TranslationPipeline - 已有)
  ↓ TTS (Kokoro/Matcha - 多音色)
  ↓ 扬声器
```

#### 3C. ZipVoice / Pocket TTS 声音克隆

允许用户录制一段参考音频，后续 TTS 使用该声音：

```kotlin
val config = GenerationConfig(
    speed = 1.0f,
    sid = 0,
    referenceAudio = referenceFloatArray,
    referenceSampleRate = 16000,
    referenceText = "This is my voice."
)
val audio = tts.generateWithConfig(text, config)
```

---

## 七、架构对比与建议

### 7.1 当前项目的优势（应保留）

| 优势 | 说明 |
|------|------|
| **翻译流水线** | TranslationPipeline 的 SWR 双通道、LRU 缓存、段落状态机设计成熟 |
| **多引擎切换** | 支持 7 种翻译引擎（MLKit/OpenAI/Groq/DeepL/Local/Opus-MT/NLLB） |
| **翻译上下文增强** | 背景信息、领域词库、术语管理系统 |
| **Edge TTS** | 微软神经语音质量高，作为在线 TTS 首选 |
| **悬浮窗服务** | FloatingTranslateService 后台翻译能力 |
| **媒体捕获** | MediaProjection 系统音频翻译 |

### 7.2 Sherpa-ONNX 的架构优势（应学习）

| 优势 | 当前项目差距 | 建议 |
|------|------------|------|
| **统一模型管理** | 当前 ASR/TTS 模型散落在不同文件夹 | 建立统一的模型目录结构和版本管理 |
| **流式设计优先** | 当前 Whisper ASR 是离线的 | 引入 OnlineRecognizer 实现真正流式 |
| **配置化模型加载** | 当前 TTS 用大量反射 | 学习 sherpa-onnx 的 Config 模式，类型安全 |
| **多模型热切换** | 当前切换引擎需重建对象 | 学习 sherpa-onnx 的 createStream 模式 |
| **回调式 TTS** | 当前 TTS 等整段生成完 | 用 `generateWithCallback` 实现边生成边播放 |
| **端点检测可配** | 当前 VAD 参数硬编码 | 暴露 endpoint 配置给用户 |

### 7.3 当前项目不需要的功能

| 功能 | 原因 |
|------|------|
| 音频标签 | 与翻译场景无关 |
| 音源分离 | 与实时翻译无关 |
| WebAssembly 支持 | 纯移动端应用 |
| 非 ARM64 架构支持 | 仅面向现代 Android 手机 |

---

## 八、模型体积与资源规划

| 模型 | 体积 | 内存占用 | 说明 |
|------|------|---------|------|
| Zipformer bilingual ASR | ~70MB | ~150MB | 替换 Vosk |
| Kokoro multi-lang TTS | ~80MB | ~120MB | 替换 VITS |
| Silero VAD | ~2MB | ~10MB | 已有 |
| CNN-BiLSTM 标点 | ~5MB | ~20MB | 新增 |
| GTCRN 降噪 | ~5MB | ~30MB | 新增 |
| Whisper tiny 语种识别 | ~40MB | ~80MB | 可选 |
| 3D-Speaker 声纹提取 | ~25MB | ~50MB | 可选 |
| **合计（推荐核心）** | **~160MB** | **~330MB** | Phase 1 |

建议：核心模型内置于 APK，可选模型按需下载（复用当前的 HuggingFace 下载机制）。

---

## 九、风险与注意事项

1. **AAR 体积**：完整 sherpa-onnx AAR (~38MB) 已包含全部功能；如需裁剪可自编译
2. **ONNX Runtime 版本冲突**：当前项目已用 ONNX Runtime 1.17.3 做翻译模型；sherpa-onnx 内含自己的 ONNX Runtime。需确认版本兼容或隔离
3. **模型下载体积**：首次下载可能较大，需提供进度 UI 和 WiFi-only 选项
4. **内存压力**：同时加载多个模型可能超出低端设备限制，建议懒加载 + 按需卸载
5. **JNI 版本兼容**：当前 TTS 用反射处理 JNI 差异，升级 AAR 后需验证

---

## 十、推荐执行顺序

```
Week 1:  更新 AAR → 流式 ASR (替换 Vosk) → 基本验证
Week 2:  Kokoro TTS → 在线标点恢复 → 集成测试
Week 3:  语音降噪 → 语种识别 → 说话人识别
Week 4:  完整流水线联调 → 性能优化 → 发布
```

---

## 十一、关键参考文件

### Sherpa-ONNX 核心 API（Kotlin）
- `sherpa-onnx/kotlin-api/OnlineRecognizer.kt` — 流式 ASR
- `sherpa-onnx/kotlin-api/Tts.kt` — TTS（含 7 种模型配置）
- `sherpa-onnx/kotlin-api/Vad.kt` — VAD
- `sherpa-onnx/kotlin-api/OnlinePunctuation.kt` — 标点恢复
- `sherpa-onnx/kotlin-api/OnlineSpeechDenoiser.kt` — 流式降噪
- `sherpa-onnx/kotlin-api/Speaker.kt` — 说话人识别
- `sherpa-onnx/kotlin-api/SpokenLanguageIdentification.kt` — 语种识别

### Sherpa-ONNX Android 示例
- `android/SherpaOnnx/` — 流式 ASR 示例
- `android/SherpaOnnxVadAsr/` — VAD + ASR 组合示例
- `android/SherpaOnnxTts/` — TTS 示例
- `android/SherpaOnnxSpeakerIdentification/` — 说话人识别示例

### 当前项目对应文件
- `asr/SherpaWhisperAsr.kt` — 当前 Whisper ASR 实现
- `tts/SherpaOnnxTts.kt` — 当前 VITS TTS 实现
- `MainActivity.kt` — ASR 引擎选择（`_asrEngine` 变量）
- `FloatingTranslateService.kt` — 悬浮窗 ASR/TTS

### 模型下载
- ASR 模型：https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models
- TTS 模型：https://github.com/k2-fsa/sherpa-onnx/releases/tag/tts-models
- 其他模型：https://github.com/k2-fsa/sherpa-onnx/releases
