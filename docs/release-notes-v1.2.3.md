# v1.2.3 发布说明

发布日期：2026-04-09  
对比基线：`v1.2.2`

---

## 一句话概览

`v1.2.3` 聚焦“语音链路升级 + 构建稳定性修复”：新增 Sherpa 流式 ASR、统一离线 TTS 引擎（含 Kokoro v1.1），并修复 JDK 25 下的 Gradle 构建失败问题。

## 重点更新

### 1) 流式 ASR（Iter-1 核心能力）

- 新增 `app/src/main/java/com/example/myapplication1/asr/SherpaStreamingAsr.kt`
- 基于 sherpa-onnx `OnlineRecognizer`，支持：
	- 实时中间结果（partial）
	- endpoint 触发最终结果（final）
	- 模型下载、切换、初始化、释放
- 支持多模型形态：Transducer / Paraformer / CTC / NeMo CTC

### 2) 统一离线 TTS 引擎（Iter-2 核心能力）

- 新增 `app/src/main/java/com/example/myapplication1/tts/VitsTts.kt`
- 将 Sherpa 离线 TTS 统一到一个引擎层，支持：
	- VITS
	- Matcha
	- Kokoro
	- Kitten
- 统一了模型下载、依赖文件校验、初始化与播放流程。

### 3) Kokoro 升级与接入增强

- 新增/整合 `app/src/main/java/com/example/myapplication1/tts/KokoroTts.kt`
- 当前运行时使用：**Kokoro multi-lang v1.1**（`csukuangfj/kokoro-multi-lang-v1_1`）
- 增加中文音色可用性、文本规范化、模型目录迁移清理逻辑。

### 4) 主界面链路联动

- `MainActivity` 完成 Streaming ASR / Sherpa TTS 的状态、下载进度、模型切换与开始/停止联动。
- 识别与播报的生命周期管理更完整（init/release/stop）。

### 5) 构建稳定性修复

- `gradle.properties` 新增：
	- `org.gradle.java.home=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home`
- 目的：规避 JDK 25 下 Kotlin/Gradle 脚本解析异常（`JavaVersion.parse(25)`）。

---

## 变更文件（核心）

- `app/src/main/java/com/example/myapplication1/asr/SherpaStreamingAsr.kt`（新增）
- `app/src/main/java/com/example/myapplication1/tts/VitsTts.kt`（新增）
- `app/src/main/java/com/example/myapplication1/tts/KokoroTts.kt`（新增/升级）
- `app/src/main/java/com/example/myapplication1/MainActivity.kt`（联动接入）
- `app/build.gradle.kts`（音频能力相关依赖/兼容配置）
- `gradle.properties`（JDK 21 固定）

---

## 升级与验证建议

### 升级注意事项

1. 若本机默认 JDK 为 25，保留 `gradle.properties` 中的 `org.gradle.java.home` 配置。  
2. 首次体验流式 ASR / 新 TTS 模型时，需要先下载模型资源（依赖网络）。  
3. 旧版 Kokoro 目录会在新流程中自动清理迁移。

### 验证结果（本次发布前）

- ✅ `:app:compileDebugKotlin` 编译通过
- ⚠️ 已知非阻断项：`third_party/espeak-ng` 目录仍可能显示 untracked content（不影响本次功能与编译）

---

## 与 v1.2.2 的关系

- `v1.2.2` 重点在翻译链路（SWR、术语与并发稳定性）。
- `v1.2.3` 重点补齐语音输入/输出主链路：
	- 输入侧：流式 ASR
	- 输出侧：统一离线 TTS + Kokoro v1.1
	- 工程侧：构建环境稳定性

