# SWR 优译链路问题闭环文档（2026-04-02）

> 目的：针对最新代码检查中发现的“优译链路未完全闭环”问题，给出可执行修复方案与验收标准。  
> 范围：`MainActivity`、`FloatingTranslateService`、`TranslationPipeline`、`TranslationHistory`、`GlossaryManager`。

---

## 1. 问题摘要（本次发现）

### P0-1：`qualityEngine` 未完成连通

**现象**
- `TranslationPipeline` 已支持 `setQualityEngine(...)` 与 `onTranslationUpgraded(...)`。
- 但主链路中未确认存在 `setQualityEngine(...)` 的初始化调用。

**影响**
- “先快后优（SWR）”升级回调可能不触发。
- UI 虽有“已优化”状态，但可能长期只停留在快译结果。

---

### P0-2：优译结果可能无法落库（历史记录）

**现象**
- `onTranslationUpgraded(...)` 中调用 `translationHistory.updateZhBySeqId(seqId, zh)`。
- 当前 `updateZhBySeqId` 仅更新 `zh.isBlank()` 的条目。
- 快译先写入后，优译再来时该条件不满足，更新被跳过。

**影响**
- 会话历史可能保留旧快译而非最终优译。
- 复盘/导出时术语一致性与质量表现下降。

---

### P1-1：`GlossaryManager` 初始化时机可能不稳

**现象**
- 目前可见 `MainActivity` 中有 `GlossaryManager.init()`。
- 若仅启动悬浮服务路径（未经过主界面），词库可能未初始化。

**影响**
- 自动领域识别与术语注入可能退化为空映射。

---

## 2. 修复目标（Done 定义）

1. **优译链路可触发**：`BALANCED/QUALITY` 模式下，确实能收到 `onTranslationUpgraded`。
2. **优译可持久化**：历史记录最终保存优译版本（而非只保存快译）。
3. **服务独立可靠**：无论主界面或悬浮窗启动，词库系统都可用。
4. **不破坏实时性**：`REALTIME` 模式行为不变，TTFT 不显著劣化。

---

## 3. 修复方案（按文件）

### 3.1 `MainActivity` / `FloatingTranslateService`

**动作**
- 在 pipeline 初始化处明确设置：
  - `setEngine(fastEngine)`
  - `setQualityEngine(qualityEngine)`
- 切换翻译引擎、模式或关键配置后，同步刷新 fast/quality 引擎绑定。

**注意**
- `REALTIME` 模式允许 qualityEngine 已设置但不触发（由 pipeline 决策）。
- `BALANCED` 与 `QUALITY` 的行为差异仅体现在超时策略。

---

### 3.2 `TranslationHistory`

**动作（推荐二选一）**

#### 方案 A（推荐）
新增函数：`upsertZhBySeqId(seqId, zh)`
- 优先按 `seqId` 命中。
- 若已存在条目，不论 `zh` 是否为空，均允许覆盖为最新值。
- 保留 `updateZhBySeqId` 兼容历史逻辑，但主链路改用 `upsertZhBySeqId`。

#### 方案 B（最小改动）
修改 `updateZhBySeqId` 条件：
- 从 `it.seqId == seqId && it.zh.isBlank()`
- 调整为 `it.seqId == seqId`

**推荐理由**
- 方案 A 语义更清晰，便于区分快译首次写入与优译覆盖。

---

### 3.3 `TranslationPipeline`

**动作**
- 保持现有 `onTranslationUpgraded` 回调机制。
- 增加调试日志字段：
  - `qualityPathTriggered`
  - `qualityPathTimeout`
  - `qualityPathSuccess`
  - `qualityPathSkippedReason`

**目的**
- 快速验证“为何某条未触发优译”：模式原因、超时、引擎空、文本为空等。

---

### 3.4 `GlossaryManager` 初始化兜底

**动作**
- 在以下入口增加幂等初始化调用（`init()` 本身应幂等）：
  1. `MainActivity.onCreate`
  2. `FloatingTranslateService.onCreate`

**目的**
- 避免仅服务启动时词库不可用。

---

## 4. 验收用例（必须通过）

### Case 1：优译链路触发
- 条件：`latencyMode = BALANCED`，qualityEngine 可用。
- 预期：先收到 `onTranslationResult`，后收到 `onTranslationUpgraded`（部分句子）。

### Case 2：历史覆盖正确
- 条件：同一 `seqId` 先写快译，再写优译。
- 预期：历史最终 `zh` 为优译文本。

### Case 3：REALTIME 不触发优译
- 条件：`latencyMode = REALTIME`。
- 预期：仅快译回调，无 `onTranslationUpgraded`。

### Case 4：悬浮窗独立启动
- 条件：不打开主界面，直接走浮窗路径。
- 预期：词库系统可正常初始化，领域解析可用。

### Case 5：回退稳定性
- 条件：qualityEngine 超时或异常。
- 预期：快译已展示且不回退为错误；日志包含失败原因。

---

## 5. 测试建议（最小集）

### 单元测试
- `TranslationHistorySeqIdUpgradeTest`
  - 验证同 `seqId` 快译→优译覆盖。

- `TranslationPipelineQualityPathTest`
  - 验证 `REALTIME` 跳过优译。
  - 验证 `BALANCED` 超时策略。

### 集成测试（可手工）
- 主界面模式切换 + 悬浮窗路径各执行一轮。
- 观察日志与历史会话落盘一致性。

---

## 6. 发布与回滚策略

### 发布顺序（建议）
1. `refactor(history): add seqId upsert API for quality overwrite`
2. `feat(pipeline): wire quality engine in activity/service`
3. `chore(glossary): init guard in floating service`
4. `test: add quality-path and history-upgrade cases`

### 回滚策略
- 如优译路径导致异常延迟：
  - 先将模式强制为 `REALTIME`（配置降级）
  - 保留快译主链路，不影响基础可用性

---

## 7. 风险提示

- 若优译覆盖策略过于激进，可能出现“前后译文跳变”主观不适。
  - 建议仅在差异显著且质量评分更高时覆盖。
- 历史覆盖后，若需审计快译原文，建议新增字段保存 `fastZh`（可选）。

---

## 8. 新增需求（术语库自动获取 + 本地加载 + 用户上传）

> 新要求：系统需要查找网上开源的不同术语库，根据用户需求自动下载或加载到本地，并支持用户自定义上传。

### 8.1 需求拆解

1. **开源术语源接入**
  - 建立“术语源目录（registry）”，支持多个开源来源。
2. **按需自动下载/加载**
  - 依据用户当前需求（语言对、领域、模式）自动选择并加载词库。
3. **用户自定义上传**
  - 支持用户上传术语文件，参与优先级合并。
4. **许可与安全合规**
  - 下载前检查许可证与来源可信度；上传文件做格式与安全校验。

---

## 9. 开源术语源策略（Registry）

### 9.1 建议首批接入源（可执行优先级）

#### A 级（优先接入，工程可行性高）
1. **Argos 包索引（语言模型/包元数据）**  
  - 用途：离线翻译包与语言覆盖补齐，可作为术语候选来源之一。  
  - 参考：`https://github.com/argosopentech/argos-translate`（支持包索引与自动安装思路）

2. **OPUS 公开语料目录（按领域筛选）**  
  - 用途：抽取高频术语对，构建领域词典（meeting/medical 等）。  
  - 参考：`https://opus.nlpl.eu/`、`https://github.com/Helsinki-NLP/OpusTools`

3. **CC-CEDICT（中英词典）**  
  - 用途：通用中英词条兜底，补全实体/术语映射。  
  - 许可：CC BY-SA 4.0（需保留署名与同协议约束）  
  - 参考：`https://www.mdbg.net/chinese/dictionary?page=cc-cedict`

#### B 级（按场景增量）
4. **Wikidata（CC0）**  
  - 用途：实体别名、领域实体映射、术语扩展（品牌/组织/专有名词）。  
  - 参考：`https://www.wikidata.org/wiki/Wikidata:Data_access`

> 说明：HuggingFace 上“glossary”数据集众多，质量与许可证不统一，建议纳入“候选池”，通过 license + 质量门槛后再入库。

---

### 9.2 术语源目录数据结构（建议）

`registry.json`（本地可更新）建议字段：

- `sourceId`: 唯一标识（如 `cc_cedict`）
- `name`: 来源名
- `homepage`: 官方主页
- `downloadUrlTemplate`: 下载模板
- `license`: SPDX 或文本（如 `CC-BY-SA-4.0`）
- `supportsLanguagePairs`: 语言对列表
- `domains`: 适配领域
- `format`: `csv|tsv|tbx|tmx|cedict|json`
- `trustLevel`: `official|community|unverified`
- `checksum`: 校验信息（可选）
- `lastVerifiedAt`: 最近验证时间

---

## 10. 自动下载/加载流程（按用户需求）

### 10.1 触发条件

当用户满足任一条件触发“按需加载”：
- 切换语言对（如 `en->zh`）
- 切换领域（如 `medical`）
- 首次启用“术语增强”
- 本地词库缺失或版本过旧

### 10.2 决策顺序

1. 读取本地缓存是否存在可用词库（语言对 + 领域 + 版本）
2. 不存在则查 `registry` 可用来源
3. 按信任级与许可证白名单筛选来源
4. 后台下载并解析入本地索引
5. 成功后热加载到 `GlossaryManager`
6. 失败则回退到已有词库/通用词库，不阻塞主翻译

### 10.3 本地存储建议

- `filesDir/glossary/registry.json`
- `filesDir/glossary/cache/{sourceId}/{langPair}/{domain}/...`
- `filesDir/glossary/user/{userGlossaryId}.json`
- `filesDir/glossary/index.sqlite`（或 json 索引）

---

## 11. 用户自定义上传方案

### 11.1 支持格式（首版建议）

- `CSV/TSV`（两列：`source,target`）
- `TBX`（术语标准格式）
- `TMX`（翻译记忆格式，抽取术语）
- `CEDICT`（中英专用，可选）

### 11.2 上传校验

1. 文件大小限制（如 10MB）
2. 编码限制（UTF-8）
3. 列/字段校验（空值、重复、超长）
4. 语言检测抽样（避免错语种）
5. 恶意内容过滤（脚本、控制字符）

### 11.3 合并优先级

`user glossary > organization glossary > open-source glossary > built-in glossary`

### 11.4 冲突策略

- 同词多译：以优先级高者覆盖
- 同级冲突：保留最新版本并记录冲突日志
- 提供 UI“冲突审查”入口（可选后续）

---

## 12. 代码改造点（在原计划基础上追加）

### 12.1 `GlossaryManager`

新增职责：
- `loadFromRegistry(...)`
- `downloadIfNeeded(...)`
- `importUserGlossary(...)`
- `mergeGlossaries(...)`
- `getTerms(domain, langPair)`（增加语言对维度）

### 12.2 `MainActivity` / `FloatingTranslateService`

新增交互：
- “术语库来源管理”页（启用/禁用来源）
- “上传术语文件”入口（选择文件 + 解析预览）
- “语言对/领域自动下载开关”

### 12.3 `TranslationPipeline`

保持原有 `resolveContext`，但增强：
- 传入 `langPair`
- 词库不可用时记录 `qualityPathSkippedReason=glossary_unavailable`

---

## 13. 验收补充（针对新增需求）

### Case 6：按需自动下载
- 条件：用户切换到本地无缓存的 `langPair + domain`。
- 预期：后台下载成功，下一句开始命中新词库。

### Case 7：离线回退
- 条件：无网络且本地无新词库。
- 预期：不阻塞翻译，回退内置/通用词库并提示。

### Case 8：用户上传生效
- 条件：上传包含同词覆盖条目。
- 预期：翻译优先命中用户上传译法。

### Case 9：许可证拦截
- 条件：来源 license 不在白名单。
- 预期：拒绝自动下载并给出原因。

### Case 10：格式错误上传
- 条件：上传文件缺列/乱码。
- 预期：校验失败，不污染本地索引。

---

## 14. 发布顺序（更新后）

1. `refactor(history): add seqId upsert API for quality overwrite`
2. `feat(pipeline): wire quality engine in activity/service`
3. `chore(glossary): init guard in floating service`
4. `feat(glossary): registry + local cache + auto download loader`
5. `feat(glossary-ui): user upload + source management`
6. `test: add quality-path/history-upgrade/glossary-download/upload cases`

---

## 15. 合规与运维要求

1. **许可证白名单机制**：仅允许白名单 license 自动导入。
2. **来源可追溯**：每条词条记录 `sourceId + version + importedAt`。
3. **增量更新**：支持 ETag/版本号避免重复下载。
4. **资源控制**：下载/解析任务后台限流，避免影响实时翻译。
5. **可回滚**：支持“一键回退到上一个词库索引版本”。


