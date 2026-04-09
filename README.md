# RealTimeTranslateTTS

> 默认语言：**中文**；（English version below）。

一款 Android 实时语音翻译（同声传译）应用，支持麦克风录音与系统媒体音频的语音识别、翻译和语音合成（TTS）。

An Android app for real-time speech recognition, translation, and text-to-speech (TTS) output — from both microphone input and system/media audio.

---

## 最新更新 / Latest Updates (2026-04)

- **SWR 双通道翻译**：支持“先快后优”升级显示（Fast Path + Quality Path）
- **翻译上下文增强**：新增 `latencyMode`（实时/平衡/质量）、`background`（可选背景信息）、`domainHint`（领域提示）
- **术语库系统升级**：内置领域词库 + 开源词库下载 + 用户自定义上传（CSV/TSV）
- **历史记录精确回填**：基于 `seqId` 进行快译/优译落库，减少高并发错位
- **并发稳定性增强**：段落状态机 + TTL 清理、ONNX 本地翻译并发闸门与资源释放优化

---

## 更新进度 / Roadmap Status

### ✅ 已完成 / Done
- Iter-0（P0）UI 重构：三级设置菜单、段落聚合控制、历史会话交互升级
- SWR 双通道翻译（先快后优）与“已优化”结果升级展示
- 翻译上下文增强：`latencyMode` / `background` / `domainHint`
- 领域词库路由：`auto/general/meeting/medical/customer_support/game`
- 术语库管理：内置词库、开源词库下载、用户上传（CSV/TSV）
- 历史记录 `seqId` 精确回填与优译覆盖落库
- 并发稳定性优化：段落状态机、TTL 兜底清理、ONNX 并发闸门

### 🚧 进行中 / In Progress
- 多语言互译体验优化（中英默认，逐步扩展更多语种双向互译）
- 术语库来源扩展与质量治理（来源分级、许可校验、冲突处理）
- 翻译质量可观测性完善（优译触发率/超时率/术语命中率）

### 🗺️ 规划中 / Planned
- AI 助手对话：基于当前会话历史追问、总结、草拟回复
- 多语言界面切换：中文 / 英文 UI
- 对话结束后自动整理内容与大纲并上传云
- 更细粒度的传译策略配置（速度/质量/延迟档位与场景模板）
- 会议专用功能：与日历/会议软件集成，自动识别会议语境与术语，提供会议纪要草稿
- 个人专用功能：与个人知识库/笔记软件集成，自动整理学习资料与待办事项
- 识别不同说话人（Speaker Diarization）与个性化翻译（Personalized Translation）
- 支持更多输入输出语言（如日语、韩语、法语等），并提供双向互译
- 支持更多 ASR/TTS/翻译引擎的接入与切换（如本地离线模型、云端 API 等）
- 支持更多翻译增强功能（如上下文理解、术语注入、风格调整等）
- 支持自定义音色和语速的 TTS 输出
- 增加同步录音功能，方便会议记录和回放
- 增加文本导出功能，支持将翻译结果以文本文件形式保存或分享

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

### 翻译增强（SWR + 上下文 + 词库）
- **SWR 先快后优**：先返回低延迟结果，再在后台输出优译（可视化“已优化”）
- **延迟模式**：`实时(REALTIME)` / `平衡(BALANCED)` / `质量(QUALITY)`
- **背景信息输入**：可选 `background` 帮助模型理解上下文（不直接输出）
- **领域路由**：`auto / general / meeting / medical / customer_support / game`
- **术语注入**：按领域自动注入术语，提高专有名词一致性

### 术语库管理
- **内置词库**：通用、会议、医疗、客服、游戏
- **开源词库下载**：支持按来源下载并本地缓存（含许可证白名单校验）
- **用户上传词库**：支持导入 CSV/TSV（英文,中文两列）
- **优先级合并**：`用户词库 > 下载词库 > 内置词库`

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
- **优译覆盖落库**：SWR 优译可覆盖快译并持久化到历史
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
├── TranslationPipeline    ← 并发翻译 + SWR 优译 + 有序输出
│   ├── TranslationContext (latencyMode / background / domainHint)
│   ├── GlossaryManager    (术语路由与注入)
│   ├── TranslationEngine  (MLKit / LLM / DeepL / Server / ONNX)
│   ├── QualityEngine      (可选后台优译通道)
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
