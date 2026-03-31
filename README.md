# RealTimeTranslateTTS

> 默认语言：**中文**；（English version below）。

一款 Android 实时语音翻译应用，支持麦克风录音与系统媒体音频的语音识别、翻译和语音合成（TTS）。

An Android app for real-time speech recognition, translation, and text-to-speech (TTS) output — from both microphone input and system/media audio.

---

## 更新计划 / Upcoming Enhancements

- 多语言互译体验优化（中英默认，支持更多语种双向互译）
- 专业领域 / 场景预设输入（如会议、客服、医学、游戏等）
- AI 助手对话：可基于当前会话历史进行追问、总结、草拟回复
- 多语言界面切换：可选中文或英文 UI，默认中文显示
- 对话结束后自动整理内容和大纲并上传云
- 增加传译速率、质量和延迟水平调整的不同模式

---

## 功能概览 / Features

### 语音识别（ASR）
| 引擎 | 说明 |
|------|------|
| 系统 ASR | Android 原生语音识别，无需额外配置 |
| Vosk（离线） | 本地离线英文语音识别，无需网络 |
| OpenAI Whisper API | 通过 OpenAI API 调用 Whisper，识别准确度高 |
| Groq Whisper API | 通过 Groq API 调用 Whisper，速度快、延迟低 |
| 本地 Whisper（Sherpa-ONNX） | 在设备端运行 Whisper ONNX 模型，完全离线，集成 Silero VAD |

### 翻译引擎
| 引擎 | 说明 |
|------|------|
| Google MLKit（离线） | 本地离线翻译，无需网络 |
| OpenAI / 兼容 API | 调用 OpenAI 或兼容 LLM 接口（可配置 base URL） |
| Groq | 低延迟 LLM 翻译 |
| DeepL | 高质量翻译（需 API Key） |
| 本地服务器（Ollama 等） | 自托管 LLM 推理服务器（如 qwen2.5） |
| Opus-MT / NLLB（离线 ONNX） | 本地 ONNX 离线翻译模型，完全私密 |

### AI 润色
支持在离线翻译结果基础上，调用快速 LLM（Groq / OpenAI / 本地服务器）进行语法校正与自然度优化。

### 文字转语音（TTS）
| 引擎 | 说明 |
|------|------|
| Microsoft Edge TTS | 微软神经网络语音（zh-CN-XiaoxiaoNeural 等），高质量 |
| Android 系统 TTS | 原生系统语音，无需网络 |
| Google Translate TTS | 通过 Google 翻译语音接口合成 |
| OpenAI TTS | 调用 OpenAI 语音合成 API |
| Sherpa-ONNX 本地 TTS | 设备本地 VITS 模型，完全离线 |

### 媒体音频转译
通过 **MediaProjection API**（需 Android 10+）捕获系统媒体音频（视频、音乐、会议等），实时转录并翻译，无需接触麦克风。

### 浮动悬浮窗
离开主界面后，自动弹出悬浮翻译窗口，录音与翻译在后台持续进行，翻译结果同步回主界面历史记录。

### 其他功能
- **翻译历史**：按会话分组保存，支持搜索与重命名
- **TTS 回声抑制**：麦克风模式下自动抑制 TTS 输出被再次识别
- **智能 ASR 过滤**：过滤填充词、噪声、音乐干扰、回声等
- **延迟指标面板**：实时显示 ASR / 翻译 / 润色 / TTS 各阶段耗时
- **设备状态监控**：CPU、内存、电量、温度
- **API 连通性测试**：一键测试所有已配置 API
- **音频设备选择**：可指定输入 / 输出音频设备

---

## 系统要求 / Requirements

- **Android 10+**（API 29+）；媒体音频捕获功能需要 Android 10+
- 麦克风权限（`RECORD_AUDIO`）
- 悬浮窗权限（`SYSTEM_ALERT_WINDOW`，可选，用于浮动窗口）
- 媒体投影权限（可选，用于捕获系统音频）
- 网络权限（使用在线 API 时需要）

---

## 架构概览 / Architecture

```
MainActivity               ← 主界面（Jetpack Compose）
├── Vosk / SherpaWhisperAsr / SystemASR / WhisperApiAsr
│        └── ASR 结果
├── TranslationPipeline    ← 并发翻译 + 有序输出
│   ├── TranslationEngine  (MLKit / LLM / DeepL / Server / ONNX)
│   └── TranslationRefiner (可选 LLM 润色)
├── TTS Consumer
│   └── EdgeTts / SystemTts / GoogleTts / OpenAiTts / SherpaOnnxTts
├── MediaCaptureService    ← 前台服务，系统音频捕获 + Vosk ASR
└── FloatingTranslateService ← 前台服务，后台悬浮翻译窗口
```

---

## 使用的主要第三方库 / Key Dependencies

| 库 | 用途 |
|----|------|
| [Vosk Android](https://alphacephei.com/vosk/) | 离线语音识别 |
| [Sherpa-ONNX](https://github.com/k2-fsa/sherpa-onnx) | 本地 Whisper ASR + VITS TTS |
| [Google MLKit Translate](https://developers.google.com/ml-kit/language/translation) | 离线翻译 |
| [OkHttp](https://square.github.io/okhttp/) | Edge TTS WebSocket、HTTP API 调用 |
| [ONNX Runtime Android](https://onnxruntime.ai/) | 本地 ONNX 模型推理 |
| Jetpack Compose | UI 框架 |
| Kotlin Coroutines | 异步并发 |
