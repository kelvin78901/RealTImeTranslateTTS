# RealTImeTranslateTTS 高并发/高效率改动清单（文件→函数级）

> 目标：在**不丢在途翻译结果、不截断录音后未完成翻译**前提下，提升高并发稳定性、吞吐与内存可控性。  
> 适用分支：`main`  
> 更新日期：2026-03-31

---

## 0. 改造范围与优先级

- **P0（必须先做）**
  1. `TranslationPipeline`：段落状态机 + 延迟回收（避免 `paragraphData` 泄漏且不丢在途结果）
  2. `OnDeviceTranslation`：ONNX Runtime 结果对象生命周期修正（防 native 内存压力）
- **P1（强烈建议）**
  3. `TranslationHistory`：按 `seqId` 精确回填，避免重复英文导致错位更新
  4. `MainActivity` / `FloatingTranslateService`：高并发下队列与节流优化
- **P2（持续优化）**
  5. 指标埋点、背压策略、长会话压测

---

## 1) `app/src/main/java/com/example/myapplication1/translation/TranslationPipeline.kt`

### 1.1 新增状态字段（类属性）

**新增**
- `private val paragraphPendingCount = mutableMapOf<Int, Int>()`
- `private val paragraphClosing = mutableSetOf<Int>()`
- `private val paragraphClosedAt = mutableMapOf<Int, Long>()`
- `private val paragraphRefining = mutableSetOf<Int>()`
- `private val PARAGRAPH_TTL_MS = 120_000L`

**目的**
- 跟踪每段在途翻译数
- 区分“请求结束”与“可回收”
- 防止重复触发润色
- 提供 TTL 兜底清理

---

### 1.2 `submitSentence(seqId, paragraphId, en)`

**修改点**
1. 在进入慢路径（真正发起翻译）前：
   - `incParagraphPending(paragraphId)`
2. 在翻译 `finally` 中：
   - `decParagraphPending(paragraphId)`
   - 调用 `maybeFinalizeParagraph(paragraphId)`
3. 缓存命中路径不增加 pending（保持零延迟）

**注意**
- 失败路径必须也 `dec`，否则会造成 pending 永不归零。

---

### 1.3 `closeParagraph(paragraphId)`

**修改点**
1. 不再立即清理 `paragraphData[paragraphId]`
2. 仅执行：
   - 标记 `paragraphClosing += paragraphId`
   - `paragraphClosedAt[paragraphId] = now`
   - 快照当前已完成结果，异步触发一次润色
3. 润色结束后：
   - `paragraphRefining -= paragraphId`
   - 再次 `maybeFinalizeParagraph(paragraphId)`

**目的**
- 保证在途翻译晚到结果仍可并入段落状态
- 避免关闭段落后数据竞态丢失

---

### 1.4 新增函数 `incParagraphPending(paragraphId)`

**职责**
- 在 `synchronized(paragraphData)` 或单独锁内累加计数

**伪逻辑**
- `paragraphPendingCount[id] = (paragraphPendingCount[id] ?: 0) + 1`

---

### 1.5 新增函数 `decParagraphPending(paragraphId)`

**职责**
- 安全递减，最低到 0，不允许负数

**伪逻辑**
- `new = maxOf(0, old - 1)`
- `paragraphPendingCount[id] = new`

---

### 1.6 新增函数 `maybeFinalizeParagraph(paragraphId)`

**触发点**
- `submitSentence(...).finally`
- `closeParagraph(...)` 的润色结束回调
- 周期性 TTL 清理任务

**回收条件**
- `paragraphId` 在 `paragraphClosing` 中
- `pendingCount == 0`
- 不在 `paragraphRefining` 中

**回收动作**
- `paragraphData.remove(id)`
- `paragraphPendingCount.remove(id)`
- `paragraphClosing.remove(id)`
- `paragraphClosedAt.remove(id)`

---

### 1.7 新增函数 `cleanupExpiredParagraphs(nowMs: Long = System.currentTimeMillis())`

**职责**
- 清理超过 `PARAGRAPH_TTL_MS` 的 closing 段落（异常兜底）

**建议触发方式**
- 每次 `submitSentence` 或 `closeParagraph` 后轻量触发（节流）
- 或在 `scope.launch(Dispatchers.Default)` 周期执行

---

### 1.8 `reset()` / `close()`

**修改点**
- 除原逻辑外，清理以上新状态容器
- `close()` 中取消可能存在的 cleanup job（如有）

---

## 2) `app/src/main/java/com/example/myapplication1/translation/OnDeviceTranslation.kt`

### 2.1 `runEncoder(inputIds: LongArray)`

**问题**
- `OrtSession.run()` 返回 `OrtSession.Result`，若未完整关闭，可能增加 native 内存压力。

**改造建议（函数级）**
- 改为：在 `runEncoder` 内拿到 `result`，从 `result[0]` 提取并复制为 JVM 数组（如 `FloatArray`）后立即关闭 `result`。
- 后续 decoder 使用复制后的数组重建 `OnnxTensor`（或改造 decoder 输入方式）。

**目标**
- 保证 ORT 结果对象生命周期清晰、可控。

---

### 2.2 `runDecoderStep(...)`

**修改点**
- 确保 `decoderInputIds`、`result`、`logitsTensor` 在任意异常路径都可关闭。
- 建议用 `try/finally` 包裹资源。

---

### 2.3 `translate(text)`

**优化点**
- 高并发时建议对 on-device 路径增加并发闸门（如 `Semaphore(1~2)`），防止多并发抢占 ORT 导致延迟抖动。

---

## 3) `app/src/main/java/com/example/myapplication1/TranslationHistory.kt`

### 3.1 `append(en, zh)` 与 `updateLastZh(en, zh)`

**问题**
- 当前按 `en` 反查最后一个空 `zh`，重复英文句子可能回填错位。

**改造建议**
1. `Entry` 增加 `seqId: Int` 字段
2. 新增函数：`appendPending(seqId, en)`
3. 新增函数：`updateZhBySeqId(seqId, zh)`
4. 保留兼容函数，但主链路改为 seqId 回填

**收益**
- 高并发重复句场景下不会串写历史。

---

## 4) `app/src/main/java/com/example/myapplication1/MainActivity.kt`

### 4.1 `addSegmentToParagraph(text)`

**改造点**
- 调用 `translationHistory.appendPending(seqId, text)`
- 移除“先 append 空 zh 再按 en 回填”的路径

---

### 4.2 `pipelineCallback.onTranslationResult(...)`

**改造点**
- 回填历史改为 `translationHistory.updateZhBySeqId(seqId, zh)`
- 保持 UI 更新逻辑不变

---

### 4.3 `runAllApiTests()`

**优化点（吞吐）**
- 当前串行执行所有测试；可改为分组并行（网络不互斥的项并行）+ 最大并发限制（如 3）
- 避免 UI 长时间等待

---

## 5) `app/src/main/java/com/example/myapplication1/FloatingTranslateService.kt`

### 5.1 `onSentence(en)` / `floatingPipelineCallback`

**改造点**
- 同 Main 路径，使用 `seqId` 贯通历史回填（避免重复英文错位）

---

### 5.2 `startTtsConsumer()`

**优化点（高并发）**
- 已有队列抽干策略，建议增加：
  - 最大累计长度保护（例如字符总量阈值）
  - 长句优先拆分或降速策略

---

## 6) 单元测试与并发测试清单

### 6.1 新增测试文件
- `app/src/test/java/com/example/myapplication1/translation/TranslationPipelineConcurrencyTest.kt`
- `app/src/test/java/com/example/myapplication1/translation/TranslationHistorySeqIdTest.kt`

### 6.2 必测用例（函数级）

1. **`closeParagraph` 后在途翻译完成**
   - 预期：结果仍回调；段落最终可回收
2. **翻译失败路径**
   - 预期：pending 归零；不会卡死不回收
3. **重复 `closeParagraph` 调用**
   - 预期：不重复润色，不抛异常
4. **TTL 兜底**
   - 预期：异常状态下最终释放段落内存
5. **历史回填重复英文**
   - 预期：按 `seqId` 精确命中，不串写

---

## 7) 观测指标（建议埋点）

在 `TranslationPipeline` 增加 debug 指标输出：
- `activeParagraphCount`
- `closingParagraphCount`
- `totalPendingTranslations`
- `ttlForcedCleanupCount`
- `avgTranslationMs / p95TranslationMs`

在 UI debug 面板可显示：
- 当前在途翻译数
- 当前关闭待回收段落数
- 最近 5 分钟 TTL 强制回收次数

---

## 8) 验收标准（上线门槛）

- 连续 30 分钟高频输入下：
  - 不出现 `paragraphData` 持续增长
  - 不出现翻译结果丢失（录音停止后在途结果仍能落地）
- 日志中不存在 pending 负数或永久非零
- ORT 路径无明显 native 内存持续上涨
- 历史记录在重复句场景无错位

---

## 9) 推荐提交拆分（便于回滚）

1. `feat(pipeline): paragraph closing state machine + delayed cleanup + TTL`
2. `fix(onnx): close ORT result lifecycle in on-device translation`
3. `refactor(history): seqId-based append/update API`
4. `refactor(main/floating): use seqId history update path`
5. `test: pipeline concurrency and history seqId correctness`

---

## 10) 备注

- 本方案保证：**不会删除音频缓存**（`paragraphData` 仅文本对）。
- 本方案保证：**不会因 close/stop 立即删除在途翻译数据**（pending=0 后才清理，TTL 仅作异常兜底）。

---

## 11) 即时性 + 上下文质量增强（新增方案，2026-03-31）

> 目标：在不牺牲首字延迟（TTFT）与吞吐的前提下，提升短句翻译准确率、术语一致性与多场景可控性。  
> 原则：**默认快路径**、**可选增强**、**按需注入上下文**、**可回退**。

### 11.1 功能契约（Contract）

**输入（每条待翻译句）**
- `text`: 必填，待翻译文本
- `background`: 可选，额外上下文（不需要翻译）
- `domainHint`: 可选，显式领域提示（如 `medical` / `legal` / `game`）
- `latencyMode`: 可选，`realtime | balanced | quality`

**输出**
- `translation`: 译文
- `meta`: 命中信息（`usedBackground`、`usedGlossaryId`、`route`、`modelTier`）

**错误与回退**
- 术语库不存在/不匹配语言对 → 自动降级到无术语翻译
- 上下文超限 → 截断后继续翻译（不阻塞主链路）
- 质量路径超时 → 回退到低延迟路径，保证实时输出

---

### 11.2 即时性策略（Latency First）

1. **双通道策略（推荐）**
    - `Fast Path`：低延迟模型/引擎，保证首条译文尽快出现
    - `Quality Path`：在后台使用上下文/术语增强，若结果更优再做可选“温和覆盖”

2. **SWR 风格结果更新（借鉴 stale-while-revalidate）**
    - 先展示快速结果，再异步重算高质量结果并无闪烁更新。
    - 该模式可显著降低主观等待感（参考 RFC 5861 的 `stale-while-revalidate` 思路）。

3. **请求分级**
    - 短句（UI 文案、按钮、标题）优先走低延迟 + 背景增强
    - 长句/段落在不阻塞 UI 的前提下走高质量模型

4. **并发与预算**
    - 为翻译请求设置“并发上限 + 每请求超时预算”，超时自动降级
    - 保持你现有 pipeline 的“有结果先回、后续可修正”原则

---

### 11.3 可选 `background` 输入设计

#### 语义定义
- `background` 是**仅供理解**的补充信息，不直接进入展示文本。
- 典型来源：
   - 当前会话最近 3~5 句摘要
   - 当前场景描述（会议纪要、医疗问诊、游戏语音）
   - 术语释义片段（定义/同义词）

#### 推荐长度
- 建议先控在 **80~300 字符**（按语言可调），避免拖慢首响。
- 超限时优先保留：领域定义 > 最近一句上下文 > 其他。

#### 提供方式
- UI 增加“背景信息（可选）”输入框（可关闭）
- 或自动由会话历史摘要生成（用户可见可编辑）

---

### 11.4 按需专业词汇库路由（Domain Glossary Routing）

#### 目标
- 不同场景动态选择词汇库，避免“全量词库常驻”带来的误匹配。

#### 路由优先级（建议）
1. 用户显式 `domainHint`
2. 关键词分类器（轻量规则/模型）
3. 会话历史领域延续（短时粘性，避免抖动）
4. 默认通用词库

#### 最小可行词库集合
- `general`（通用）
- `meeting`（会议）
- `medical`（医疗）
- `customer_support`（客服）
- `game`（游戏）

#### 术语冲突处理
- 同词多义冲突时按“领域优先 + 最新命中优先 + 人工白名单强制”
- 未命中术语库时不阻塞，直接走通用翻译

---

### 11.5 提示词/请求组装（引擎无关）

为避免不同翻译引擎行为漂移，建议统一请求组装层：

1. `source_text`（必填）
2. `background`（可选）
3. `glossary_terms`（按领域路由注入）
4. `style`（可选，如正式/口语）
5. `latency_mode`（realtime/balanced/quality）

并要求输出 `meta`：
- `selected_domain`
- `selected_glossary`
- `used_background_tokens`
- `fallback_reason`（若发生降级）

---

### 11.6 平台能力对照（联网调研结论）

1. **DeepL**
    - 支持 `context`（附加上下文，不计费字符）
    - 支持 `glossary_id`（需匹配语言对，且通常需显式 `source_lang`）
    - 支持 `model_type`（`latency_optimized` / `quality_optimized`）
    - 结论：非常适合“低延迟 + 可选背景 + 术语库”分层路由。

2. **Google Cloud Translation (Advanced)**
    - 支持 glossary（单向术语表/等效术语集）
    - 支持 `contextual_translation_enabled`
    - 支持 Adaptive Translation（可用参考句提升领域一致性）
    - 结论：适合做“领域词库 + 自适应参考上下文”的中高质量路径。

3. **Azure Translator**
    - 支持 dynamic dictionary（`<mstrans:dictionary ...>`）
    - 要求使用 `from`（不建议依赖自动检测）且大小写敏感
    - 支持 `category`（Custom Translator 域模型）
    - 结论：可做“命名实体强约束”与特定领域模型路由。

4. **AWS Translate**
    - 支持 Custom Terminology（品牌/术语一致性）
    - 文档明确：不保证每次都强制命中，应按上下文使用
    - 结论：适合术语纠偏，但需结合后验检查与回退策略。

---

### 11.7 推荐落地路径（不改代码版规划）

#### Phase A（1~2 天，低风险）
- 在产品方案文档中新增：`background` 字段与 `domainHint` 字段
- 设计 5 类基础领域词库与命名规范
- 定义 `latencyMode` 三档策略与降级规则

#### Phase B（2~4 天，中风险）
- 增加“路由决策日志字段”与评估报表口径（无需改核心算法）
- 建立词库版本管理（`glossary@v1`）与回滚流程
- 补充人工评估集（短句、歧义词、专有名词）

#### Phase C（持续优化）
- 引入会话摘要自动生成 `background`
- 针对高频错误术语做热更新
- 增加领域识别置信度，低置信度时回退通用路径

---

### 11.8 验收指标（针对本新增方案）

**即时性**
- TTFT（首条译文延迟）P50 / P95
- 端到端翻译延迟 P50 / P95

**质量**
- 术语命中率（按领域）
- 重复句一致性
- 短句歧义纠正率（有/无 background 对比）

**稳定性**
- 降级触发率（超时、词库缺失、语言对不匹配）
- 降级后成功率

上线门槛建议：
- `realtime` 模式 TTFT 不劣于当前基线 +5%
- 术语命中率提升 ≥ 15%
- 人工质量评分（专业场景）提升 ≥ 10%

---

### 11.9 风险与边界

- `background` 过长会抬高延迟与成本：必须限长 + 截断策略
- 领域识别抖动会造成术语风格来回切换：需要会话粘性窗口
- 术语库并非 100% 强约束（尤其部分供应商）：需结果后验检查
- 敏感数据进入 background 前需做脱敏（PII/密钥/账号）

---

### 11.10 外部参考（本次联网检索）

- DeepL Translate API（`context`、`model_type`、`glossary_id`）  
   https://developers.deepl.com/api-reference/translate
- Google Cloud Translation Glossary（含 contextual glossary）  
   https://cloud.google.com/translate/docs/advanced/glossary
- Google Adaptive Translation  
   https://cloud.google.com/translate/docs/advanced/adaptive-translation
- Azure Translator v3 Translate（dynamic dictionary、category、from）  
   https://learn.microsoft.com/en-us/azure/ai-services/translator/text-translation/reference/v3/translate
- Azure Dynamic Dictionary  
   https://learn.microsoft.com/en-us/azure/ai-services/translator/text-translation/how-to/use-dynamic-dictionary
- AWS Translate Custom Terminology  
   https://docs.aws.amazon.com/translate/latest/dg/how-custom-terminology.html
- RFC 5861（`stale-while-revalidate` 思路）  
   https://www.rfc-editor.org/rfc/rfc5861

---

## 12) UI 交互改进方案（面向“即时性 + 上下文 + 专业词库”）

> 目标：让用户“先快后优”有感知、可控且不打扰；默认简单，进阶可配。

### 12.1 交互设计原则

1. **默认极简**：用户无需理解术语库/模型即可开始翻译。
2. **渐进披露**：高级能力（background、领域词库、风格）放在“高级设置”抽屉。
3. **状态可解释**：每条译文可查看“为什么这么翻”（用了哪些上下文/词库）。
4. **可撤销更新**：质量通道覆盖快速结果时，提供“还原快译”入口。

---

### 12.2 主界面布局（建议）

#### 顶部状态条（常驻）
- 左：引擎状态（在线/离线、网络质量）
- 中：当前模式 `实时 / 平衡 / 质量`
- 右：`⚙️高级`

#### 中部会话区
- 逐条卡片显示：
   - 原文（ASR 文本）
   - 快译结果（先到先显示）
   - 若质量结果到达：显示“已优化”标记
   - 右上角 `ⓘ`：展开查看 `meta`（领域、词库、background 是否命中）

#### 底部输入控制区
- 麦克风主按钮（大按钮）
- 次按钮：`背景信息`（可选）
- 次按钮：`领域`（自动/会议/医疗/客服/游戏/通用）

---

### 12.3 关键交互流程

#### 流程 A：默认零配置（推荐默认）
1. 用户点录音
2. 系统实时产出快译
3. 后台若有更优结果，卡片出现“已优化”轻提示（不抢焦点）
4. 用户无操作即可继续

#### 流程 B：用户提供 background（可选）
1. 用户点击 `背景信息`
2. 打开半屏输入框：
    - 支持手动输入
    - 支持“一键使用最近会话摘要”
3. 保存后显示 `已应用背景（可关闭）`
4. 后续翻译请求自动携带 background，直到用户关闭

#### 流程 C：专业词库按需切换
1. 默认 `自动` 领域
2. 用户可手动指定领域（如 `医疗`）
3. 切换后展示轻提示：`已切换到医疗词库`
4. 若词库不可用，显示：`医疗词库不可用，已回退通用翻译`

---

### 12.4 组件级规范

#### 1) `LatencyModeSegmentedControl`
- 三档：`实时`、`平衡`、`质量`
- 说明文案（长按/tooltip）：
   - 实时：优先速度
   - 平衡：速度与质量折中
   - 质量：优先准确和术语一致

#### 2) `BackgroundInputSheet`
- 字段：多行文本
- 字数计数：`0/300`（建议默认上限 300）
- 操作：`清空`、`使用会话摘要`、`应用`
- 提示：`背景信息仅用于理解，不会直接原样输出`

#### 3) `DomainSelectorChipGroup`
- 选项：`自动`、`通用`、`会议`、`医疗`、`客服`、`游戏`
- 选中状态颜色区分
- 无可用词库时禁用并给出原因

#### 4) `TranslationMetaBottomSheet`
- 展示：
   - `route`: fast / quality / fallback
   - `selected_domain`
   - `selected_glossary`
   - `used_background_tokens`
   - `fallback_reason`

---

### 12.5 结果卡片的“先快后优”交互细节

每条译文卡片建议使用以下状态：

- `translating`：显示骨架屏 + “翻译中”
- `fast_ready`：显示快译正文
- `quality_upgraded`：正文替换为优译，并显示 `已优化` 标签（3 秒自动淡出）
- `fallback`：显示译文 + “已回退”灰色标签
- `error`：显示“翻译失败，点击重试”

交互约束：
- 质量结果覆盖时不改变卡片高度，避免列表跳动
- 覆盖仅允许一次，防止多次闪烁

---

### 12.6 文案与反馈规范（关键提示）

#### 成功类
- `已应用背景信息`
- `已切换到{领域}词库`
- `已优化译文`

#### 回退类
- `当前网络波动，已切换到实时翻译`
- `{领域}词库不可用，已回退通用词库`
- `上下文过长，已自动截断`

#### 错误类
- `翻译服务暂不可用，请稍后重试`
- `该语言对暂无对应词库`

---

### 12.7 可用性与可访问性（A11y）

- 语音主按钮可触达尺寸 ≥ 48dp
- 所有状态标签提供无障碍描述（TalkBack）
- 颜色之外增加图标/文案区分（不只靠颜色）
- 底部弹层支持键盘与返回手势关闭

---

### 12.8 埋点与评估（UI 维度）

新增事件建议：
- `ui_latency_mode_changed`
- `ui_background_opened` / `ui_background_applied` / `ui_background_cleared`
- `ui_domain_changed`
- `ui_translation_upgraded_shown`
- `ui_translation_reverted_to_fast`
- `ui_fallback_banner_shown`

核心漏斗：
1. 打开高级设置
2. 启用 background
3. 切换领域词库
4. 查看 meta 详情
5. 手动还原快译（若发生）

---

### 12.9 三版迭代建议（仅 UI 侧）

#### v1（最小可上线）
- 上线 `实时/平衡/质量` 切换
- 上线 `背景信息` 输入框
- 上线领域选择（自动 + 5 个领域）

#### v2（提升可解释性）
- 每条译文 `ⓘmeta` 抽屉
- “已优化/已回退”状态标签

#### v3（提升易用性）
- 自动会话摘要填充 background
- 领域自动识别 + 可一键确认

---

### 12.10 验收标准（UI 方案）

- 首次使用用户在 **30 秒内**可完成一次“录音→翻译→播放”闭环
- 至少 **70%** 用户无需进入高级设置也能完成主要任务
- 启用 background 的会话中，术语命中率提升可被观测
- “已优化”覆盖不引发明显列表跳动（视觉稳定性通过）

