# v1.2.2 专项修复文档（语音质量与识别质量）

> 本文档聚焦当前线上/测试中最影响体验的四类问题：
> 1) Kokoro 语音不自然；2) Kokoro 合成速度慢；3) Zipformer 精度差；4) Zipformer 分句不合理。
>
> 状态注记（2026-04-10）：`v1.2.3` 已落地统一推理加速配置（CPU/NNAPI/XNNPACK）及自动回退链，作为本方案中的“性能与稳定性基础设施”补齐项。

## 修复目标（可验收）

- **TTS 自然度**：MOS 主观评分从当前基线提升到 **≥ 3.8/5**（中文场景）。
- **TTS 延迟**：首包语音时间（TTFA）降到 **< 600ms**，整句合成耗时下降 **30%+**。
- **ASR 精度**：核心场景 WER/CER 下降 **15%+**（中英混说、噪声、口语停顿）。
- **分句质量**：明显错断句率下降 **40%+**，长句漏断率下降 **30%+**。

## 现状问题与根因拆解

### 1) Kokoro 语音不自然

可能根因：
- 文本预处理不足（数字、缩写、标点、英文夹杂未做规范化）。
- 一次性长文本直接送入，缺乏韵律边界（短停顿/重音）。
- 声学参数固定，未按语言/语速/场景做动态策略。
- 历史上下文未参与 prosody（每段独立说，语气跳变）。

### 2) Kokoro 合成速度慢

可能根因：
- 未做模型预热，首次请求冷启动明显。
- 文本分块策略粗糙，块大小不均导致尾块抖动。
- 推理线程与 UI/ASR 竞争，CPU 争用高。
- 未启用量化/加速路径（或配置不完整）。

### 3) Zipformer 精度差

可能根因：
- VAD 与 endpoint 参数不匹配真实说话习惯。
- 解码参数偏保守（beam/hotword 权重未调优）。
- 领域词（专有名词、产品名）缺乏热词注入。
- 采样率/前处理链路不一致（重采样或增益策略问题）。

### 4) Zipformer 分句不合理

可能根因：
- 仅靠静音阈值断句，未融合语义/标点恢复。
- 对短停顿过敏（把一个句子切成多段）。
- 缺少“最小句长 + 最大句长 + 语义回并”策略。

## 模型替换可行性结论（2026-04-07）

结论：**可以通过替换模型解决部分核心问题，但不能只靠换模型。**

- **Kokoro 不自然/慢**：可通过替换 TTS 模型显著改善（自然度、首包延迟）。
- **Zipformer 精度差**：可通过替换或升级 ASR 模型改善。
- **Zipformer 分句不合理**：主要仍需 **VAD + 标点恢复 + 断句回并策略**，仅替换 ASR 模型通常不够。

### 联网依据（官方入口）

- sherpa-onnx TTS 模型生态（Kokoro/KittenTTS/VITS/Matcha/Piper 等）
	- https://k2-fsa.github.io/sherpa/onnx/tts/index.html
- sherpa-onnx ASR 预训练模型池（Zipformer/Paraformer/SenseVoice/Qwen3-ASR/Whisper 等）
	- https://k2-fsa.github.io/sherpa/onnx/pretrained_models/index.html
- sherpa-onnx 标点恢复（用于分句质量）
	- https://k2-fsa.github.io/sherpa/onnx/punctuation/index.html
- sherpa-onnx VAD（silero-vad / ten-vad）
	- https://k2-fsa.github.io/sherpa/onnx/vad/index.html
- sherpa-onnx hotwords（专有词纠偏）
	- https://k2-fsa.github.io/sherpa/onnx/hotwords/index.html
- 服务器端 ASR 备选：faster-whisper（性能与量化路径）
	- https://github.com/SYSTRAN/faster-whisper
- 服务器端 TTS 备选：Coqui XTTS-v2（多语言与流式能力）
	- https://docs.coqui.ai/en/latest/models/xtts.html

## 模型替换策略（建议顺序）

1. **优先同框架替换**（低风险）：在 sherpa-onnx 内完成 TTS/ASR 候选 A/B。
2. **再引入服务器模型**（中风险）：仅在端侧无法达到目标时，引入 LAN 推理（Whisper/XTTS）。
3. **分句单独治理**（必须）：VAD + 标点恢复 + 断句策略，与 ASR 模型解耦评估。

## 模型 A/B 实验矩阵（可直接执行）

### TTS 候选

- Baseline：`Kokoro (current)`
- Candidate-A：`Kokoro v1.1`（若当前非 v1.1）
- Candidate-B：`VITS zh_en`
- Candidate-C：`KittenTTS`
- Candidate-D（可选，LAN）：`XTTS-v2`

评估指标：
- 自然度 MOS（5 位测试者，1~5 分）
- 首包时间 TTFA（P50/P90）
- 整句耗时（1句/3句/长段）
- 设备温升与耗电（10 分钟连续播报）

通过门槛：
- MOS 至少提升 **+0.4**
- TTFA 至少下降 **25%**
- 无明显错读、吞字、机械停顿恶化

### ASR 候选

- Baseline：`Zipformer (current)`
- Candidate-A：`Zipformer 新版/更大规格`
- Candidate-B：`Paraformer`
- Candidate-C：`SenseVoice`
- Candidate-D（可选，LAN）：`faster-whisper / distil-whisper`

评估指标：
- CER/WER（安静/噪声/中英混说）
- 专有词命中率（开启 hotwords 前后）
- 实时性 RTF（连续 10 分钟）
- 分句错误率（错断、漏断、过碎）

通过门槛：
- CER/WER 至少下降 **15%**
- 分句错误率至少下降 **40%**
- RTF 不劣于当前基线超过 **15%**

## 分句专项（与模型替换并行）

- 引入/调优 VAD：短静音阈值、最小语音段、最小静音段。
- 接入标点恢复：先输出无标点文本，再做标点插入。
- 断句策略：`最小句长 + 最大句长 + 短句回并 + 语义回并`。
- 验证方式：固定 50 条长短混合样本，人工标注对照。

## 改造方案（按优先级）

### [P0] 一周内落地（高收益、低风险）

1. **Kokoro 文本规范化（必须）**
	- 增加中文数字/单位/金额读法规则。
	- 英文缩写与符号转读（AI、CPU、% 等）。
	- 标点标准化：连续标点折叠、非法字符清洗。

2. **Kokoro 分块与停顿策略（必须）**
	- 采用“标点优先 + 长度兜底”的 chunking。
	- 每块目标长度：中文 18~32 字；超长按短语切分。
	- 块间插入可配置短停顿（80~180ms）避免机械感。

3. **Kokoro 性能快速优化（必须）**
	- App 启动后模型预热 1 次（后台低优先级）。
	- 合成线程池与 ASR 分离，限制并发为 1~2。
	- 添加合成缓存（相同文本+voice 参数命中复用）。

4. **Zipformer 解码参数调优（必须）**
	- 暴露并调优：beam、max-active、endpoint-silence。
	- 增加热词表（产品词、人名、术语）并可在线更新。

5. **分句规则增强（必须）**
	- 从“仅静音切分”改为“静音 + 标点恢复 + 最小句长”。
	- 增加回并逻辑：对过短分句（如 < 6 字）尝试合并。

### [P1] 两到三周落地（中风险、明显提升）

1. **Kokoro 韵律控制增强**
	- 引入句法提示（逗号、顿号、并列结构）映射韵律 tag。
	- 按场景配置 speaking rate / pitch / energy 模板。

2. **Zipformer 后处理增强**
	- 增加轻量标点恢复模型（或规则+词典混合）。
	- 引入数字、时间、金额、专有词规范化输出。

3. **噪声鲁棒性优化**
	- 前处理增加 AGC + 降噪（可切换档位）。
	- 噪声场景自动提高 endpoint 阈值，减少误切。

### [P2] 一个月内（架构级优化）

1. **流式双通道架构**
	- 快通道：低延迟草稿转写。
	- 质通道：重打分/重断句后增量修正。

2. **统一质量评估平台**
	- 固定评测集（安静/地铁/会议/中英混说）。
	- 每次迭代自动输出 CER/WER、断句指标、TTFA、RTF。

## 验收清单（DoD）

- [ ] Kokoro 文本规范化规则已接入主流程并可开关。
- [ ] Kokoro 首包时间 TTFA < 600ms（中位数，50 条样本）。
- [ ] Kokoro 自然度主观评分 ≥ 3.8/5（至少 5 位测试者）。
- [ ] Zipformer 热词机制可配置并支持动态更新。
- [ ] Zipformer CER/WER 对比基线下降 ≥ 15%。
- [ ] 断句错误率下降 ≥ 40%，并有可复现实验记录。

## 风险与回退

- 若 P0 参数调优后出现精度波动：保留“旧参数配置”一键回退。
- 若 TTS 加速影响音质：分设备分层启用（高端机先开，低端机灰度）。
- 若分句模型引入延迟：保留纯规则模式作为低延迟兜底。

## 建议的版本节奏

- **v1.2.3**：完成 P0 全量。
- **v1.2.4**：完成 P1，并稳定线上参数。
- **v1.3.0**：完成 P2 与自动评测闭环。

