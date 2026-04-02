package com.example.myapplication1.translation

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.net.HttpURLConnection
import java.net.URL

/**
 * Domain glossary system with:
 * - 5 built-in domain glossaries (general, meeting, medical, customer_support, game)
 * - Registry of downloadable open-source glossary sources
 * - User-uploaded custom glossaries (CSV/TSV)
 * - Priority-based merge: user > downloaded > built-in
 * - Keyword-based auto-detection with session stickiness
 */
object GlossaryManager {

    private const val TAG = "GlossaryManager"
    private const val MAX_USER_FILE_SIZE = 10 * 1024 * 1024 // 10 MB

    // ==================== Data classes ====================

    data class GlossaryEntry(val en: String, val zh: String)

    data class DomainGlossary(
        val domain: String,
        val label: String,
        val terms: List<GlossaryEntry>,
        val keywords: List<String>
    ) {
        val termMap: Map<String, String> by lazy {
            terms.associate { it.en.lowercase() to it.zh }
        }
    }

    /** A registry entry describing a downloadable glossary source. */
    data class GlossarySource(
        val sourceId: String,
        val name: String,
        val homepage: String,
        val downloadUrl: String,
        val license: String,
        val domains: List<String>,
        val format: String,           // csv, tsv, cedict, json
        val trustLevel: String,       // official, community, unverified
        val enabled: Boolean = true
    )

    /** Metadata about a user-uploaded glossary file. */
    data class UserGlossary(
        val id: String,
        val fileName: String,
        val domain: String,
        val entryCount: Int,
        val importedAt: Long
    )

    // ==================== State ====================

    private val builtinGlossaries = mutableMapOf<String, DomainGlossary>()
    private val downloadedTerms = mutableMapOf<String, MutableMap<String, String>>() // domain → (en→zh)
    private val userTerms = mutableMapOf<String, MutableMap<String, String>>()       // domain → (en→zh)
    private val mergedGlossaries = mutableMapOf<String, DomainGlossary>()

    private val registry = mutableListOf<GlossarySource>()
    private val userGlossaries = mutableListOf<UserGlossary>()

    @Volatile var stickyDomain: String = ""
        private set

    private const val SWITCH_THRESHOLD = 2
    private var filesDir: File? = null

    val availableDomains: List<Pair<String, String>>
        get() = mergedGlossaries.values.map { it.domain to it.label }

    val registrySources: List<GlossarySource> get() = registry.toList()
    val importedGlossaries: List<UserGlossary> get() = userGlossaries.toList()

    // ==================== Initialization ====================

    fun init(context: Context? = null) {
        if (context != null) filesDir = context.filesDir
        initBuiltins()
        initRegistry()
        if (filesDir != null) {
            loadDownloadedTerms()
            loadUserGlossaries()
        }
        rebuildMerged()
        Log.i(TAG, "Initialized: ${builtinGlossaries.size} built-in, " +
                "${downloadedTerms.values.sumOf { it.size }} downloaded terms, " +
                "${userTerms.values.sumOf { it.size }} user terms")
    }

    /** Idempotent — safe to call multiple times. */
    private fun initBuiltins() {
        if (builtinGlossaries.isNotEmpty()) return

        builtinGlossaries["general"] = DomainGlossary(
            domain = "general", label = "通用",
            terms = listOf(
                GlossaryEntry("artificial intelligence", "人工智能"),
                GlossaryEntry("machine learning", "机器学习"),
                GlossaryEntry("cloud computing", "云计算"),
                GlossaryEntry("open source", "开源"),
                GlossaryEntry("user experience", "用户体验"),
                GlossaryEntry("best practice", "最佳实践"),
                GlossaryEntry("scalability", "可扩展性"),
                GlossaryEntry("bandwidth", "带宽"),
                GlossaryEntry("latency", "延迟"),
                GlossaryEntry("throughput", "吞吐量")
            ),
            keywords = emptyList()
        )

        builtinGlossaries["meeting"] = DomainGlossary(
            domain = "meeting", label = "会议",
            terms = listOf(
                GlossaryEntry("stakeholder", "利益相关方"), GlossaryEntry("deliverable", "交付物"),
                GlossaryEntry("milestone", "里程碑"), GlossaryEntry("action item", "行动项"),
                GlossaryEntry("follow-up", "跟进"), GlossaryEntry("quarterly", "季度"),
                GlossaryEntry("OKR", "目标与关键成果"), GlossaryEntry("KPI", "关键绩效指标"),
                GlossaryEntry("roadmap", "路线图"), GlossaryEntry("stand-up", "站会"),
                GlossaryEntry("retrospective", "复盘"), GlossaryEntry("sprint", "迭代"),
                GlossaryEntry("backlog", "待办"), GlossaryEntry("blocker", "阻塞项")
            ),
            keywords = listOf("meeting", "agenda", "minutes", "stakeholder", "deliverable",
                "milestone", "action item", "follow-up", "quarterly", "okr", "kpi",
                "roadmap", "stand-up", "standup", "retrospective", "sprint", "backlog",
                "blocker", "deadline", "project")
        )

        builtinGlossaries["medical"] = DomainGlossary(
            domain = "medical", label = "医疗",
            terms = listOf(
                GlossaryEntry("diagnosis", "诊断"), GlossaryEntry("treatment", "治疗"),
                GlossaryEntry("patient", "患者"), GlossaryEntry("symptom", "症状"),
                GlossaryEntry("prescription", "处方"), GlossaryEntry("surgery", "手术"),
                GlossaryEntry("prognosis", "预后"), GlossaryEntry("chronic", "慢性"),
                GlossaryEntry("acute", "急性"), GlossaryEntry("outpatient", "门诊"),
                GlossaryEntry("inpatient", "住院"), GlossaryEntry("dosage", "剂量"),
                GlossaryEntry("side effect", "副作用"), GlossaryEntry("vital signs", "生命体征")
            ),
            keywords = listOf("doctor", "patient", "diagnosis", "treatment", "symptom",
                "medication", "prescription", "surgery", "hospital", "clinic", "disease",
                "chronic", "acute", "therapy", "dosage", "blood pressure", "heart rate",
                "prognosis", "outpatient", "inpatient")
        )

        builtinGlossaries["customer_support"] = DomainGlossary(
            domain = "customer_support", label = "客服",
            terms = listOf(
                GlossaryEntry("refund", "退款"), GlossaryEntry("shipping", "物流"),
                GlossaryEntry("tracking number", "快递单号"), GlossaryEntry("warranty", "保修"),
                GlossaryEntry("escalate", "升级处理"), GlossaryEntry("ticket", "工单"),
                GlossaryEntry("SLA", "服务等级协议"), GlossaryEntry("ETA", "预计送达时间"),
                GlossaryEntry("return policy", "退货政策"), GlossaryEntry("coupon", "优惠券"),
                GlossaryEntry("invoice", "发票"), GlossaryEntry("out of stock", "缺货")
            ),
            keywords = listOf("order", "refund", "shipping", "tracking", "warranty", "support",
                "ticket", "customer", "delivery", "return", "exchange", "coupon", "invoice",
                "complaint", "service", "account", "subscription", "cancel", "billing")
        )

        builtinGlossaries["game"] = DomainGlossary(
            domain = "game", label = "游戏",
            terms = listOf(
                GlossaryEntry("boss", "Boss"), GlossaryEntry("quest", "任务"),
                GlossaryEntry("dungeon", "副本"), GlossaryEntry("buff", "增益"),
                GlossaryEntry("nerf", "削弱"), GlossaryEntry("DPS", "输出"),
                GlossaryEntry("tank", "坦克"), GlossaryEntry("healer", "治疗"),
                GlossaryEntry("cooldown", "冷却"), GlossaryEntry("spawn", "刷新"),
                GlossaryEntry("loot", "掉落"), GlossaryEntry("PvP", "玩家对战"),
                GlossaryEntry("PvE", "玩家对环境"), GlossaryEntry("NPC", "NPC"),
                GlossaryEntry("respawn", "复活")
            ),
            keywords = listOf("game", "player", "level", "boss", "quest", "dungeon", "buff",
                "nerf", "damage", "health", "mana", "spell", "weapon", "armor", "loot",
                "spawn", "respawn", "pvp", "raid", "guild", "inventory", "cooldown", "dps",
                "tank", "healer")
        )
    }

    private fun initRegistry() {
        if (registry.isNotEmpty()) return
        // First-party curated sources
        registry.add(GlossarySource(
            sourceId = "cc_cedict", name = "CC-CEDICT",
            homepage = "https://www.mdbg.net/chinese/dictionary?page=cc-cedict",
            downloadUrl = "https://www.mdbg.net/chinese/export/cedict/cedict_1_0_ts_utf-8_mdbg.txt.gz",
            license = "CC-BY-SA-4.0", domains = listOf("general"),
            format = "cedict", trustLevel = "official"
        ))
    }

    // ==================== Domain detection ====================

    fun detectDomain(text: String): String {
        val lower = text.lowercase()
        var bestDomain = ""
        var bestHits = 0
        for ((domain, glossary) in mergedGlossaries) {
            if (glossary.keywords.isEmpty()) continue
            val hits = glossary.keywords.count { lower.contains(it) }
            if (hits > bestHits) { bestHits = hits; bestDomain = domain }
        }
        if (bestHits >= SWITCH_THRESHOLD) {
            if (stickyDomain != bestDomain) {
                Log.d(TAG, "Domain switched: $stickyDomain → $bestDomain (hits=$bestHits)")
                stickyDomain = bestDomain
            }
            return bestDomain
        }
        return stickyDomain.ifEmpty { bestDomain }
    }

    fun resolveDomain(domainHint: String, text: String): String {
        if (domainHint.isNotBlank() && domainHint != "auto") {
            return if (mergedGlossaries.containsKey(domainHint)) domainHint else "general"
        }
        return detectDomain(text).ifEmpty { "general" }
    }

    /** Get merged term map for a domain: user > downloaded > built-in. */
    fun getTerms(domain: String): Map<String, String> {
        return mergedGlossaries[domain]?.termMap ?: emptyMap()
    }

    fun getLabel(domain: String): String = mergedGlossaries[domain]?.label ?: domain
    fun resetSticky() { stickyDomain = "" }

    // ==================== Merge ====================

    /** Rebuild merged glossaries from all sources with priority: user > downloaded > built-in. */
    private fun rebuildMerged() {
        mergedGlossaries.clear()
        val allDomains = (builtinGlossaries.keys + downloadedTerms.keys + userTerms.keys).toSet()
        for (domain in allDomains) {
            val builtin = builtinGlossaries[domain]
            val merged = mutableMapOf<String, String>()
            // Layer 1: built-in (lowest priority)
            builtin?.terms?.forEach { merged[it.en.lowercase()] = it.zh }
            // Layer 2: downloaded
            downloadedTerms[domain]?.forEach { (k, v) -> merged[k] = v }
            // Layer 3: user (highest priority)
            userTerms[domain]?.forEach { (k, v) -> merged[k] = v }

            val terms = merged.map { GlossaryEntry(it.key, it.value) }
            mergedGlossaries[domain] = DomainGlossary(
                domain = domain,
                label = builtin?.label ?: domain,
                terms = terms,
                keywords = builtin?.keywords ?: emptyList()
            )
        }
    }

    // ==================== User import (CSV/TSV) ====================

    data class ImportResult(val success: Boolean, val entryCount: Int, val error: String = "")

    /**
     * Import a user glossary from a CSV or TSV file.
     * Format: two columns — source (EN) and target (ZH), one pair per line.
     * First line is skipped if it looks like a header.
     */
    fun importUserFile(context: Context, uri: Uri, domain: String, fileName: String): ImportResult {
        try {
            val inputStream = context.contentResolver.openInputStream(uri)
                ?: return ImportResult(false, 0, "无法打开文件")

            // Size check
            val bytes = inputStream.use { it.readBytes() }
            if (bytes.size > MAX_USER_FILE_SIZE) {
                return ImportResult(false, 0, "文件过大 (${bytes.size / 1024 / 1024}MB > 10MB)")
            }

            val text = try {
                String(bytes, Charsets.UTF_8)
            } catch (_: Throwable) {
                return ImportResult(false, 0, "文件编码错误，请使用 UTF-8")
            }

            val lines = text.lines().filter { it.isNotBlank() }
            if (lines.isEmpty()) return ImportResult(false, 0, "文件为空")

            // Detect separator (tab or comma)
            val separator = if (lines.first().contains('\t')) '\t' else ','

            val entries = mutableListOf<GlossaryEntry>()
            for ((i, line) in lines.withIndex()) {
                val parts = line.split(separator, limit = 2)
                if (parts.size < 2) continue
                val src = parts[0].trim().removeSurrounding("\"")
                val tgt = parts[1].trim().removeSurrounding("\"")
                // Skip header-like first line
                if (i == 0 && (src.equals("source", true) || src.equals("en", true)
                            || src.equals("english", true) || src.equals("term", true))) continue
                if (src.isBlank() || tgt.isBlank()) continue
                if (src.length > 200 || tgt.length > 200) continue // skip overlong entries
                // Basic safety: reject control characters
                if (src.any { it.isISOControl() && it != '\n' } || tgt.any { it.isISOControl() && it != '\n' }) continue
                entries.add(GlossaryEntry(src, tgt))
            }

            if (entries.isEmpty()) return ImportResult(false, 0, "未找到有效术语条目")

            // Store to user terms
            val domainMap = userTerms.getOrPut(domain) { mutableMapOf() }
            entries.forEach { domainMap[it.en.lowercase()] = it.zh }

            // Save metadata
            val id = "${domain}_${System.currentTimeMillis()}"
            userGlossaries.add(UserGlossary(id, fileName, domain, entries.size, System.currentTimeMillis()))
            saveUserGlossaries()
            saveUserTerms(domain)
            rebuildMerged()

            Log.i(TAG, "Imported $fileName: ${entries.size} terms into domain=$domain")
            return ImportResult(true, entries.size)
        } catch (e: Throwable) {
            Log.e(TAG, "Import failed: ${e.message}", e)
            return ImportResult(false, 0, "导入失败: ${e.message}")
        }
    }

    /** Delete a user-imported glossary and rebuild. */
    fun deleteUserGlossary(glossaryId: String) {
        val ug = userGlossaries.find { it.id == glossaryId } ?: return
        userGlossaries.remove(ug)
        // Reload user terms for this domain from remaining glossaries
        userTerms.remove(ug.domain)
        // Re-load remaining user term files for this domain
        reloadUserTermsForDomain(ug.domain)
        saveUserGlossaries()
        rebuildMerged()
        // Delete persisted file
        val file = File(glossaryDir(), "user/${glossaryId}.json")
        file.delete()
        Log.i(TAG, "Deleted user glossary $glossaryId")
    }

    // ==================== Download ====================

    /**
     * Download a glossary source in the background.
     * Returns the number of terms loaded, or -1 on failure.
     */
    suspend fun downloadSource(sourceId: String): Int = withContext(Dispatchers.IO) {
        val source = registry.find { it.sourceId == sourceId }
            ?: return@withContext -1.also { Log.w(TAG, "Source not found: $sourceId") }

        if (!isLicenseAllowed(source.license)) {
            Log.w(TAG, "License not allowed: ${source.license} for ${source.sourceId}")
            return@withContext -1
        }

        try {
            Log.i(TAG, "Downloading glossary: ${source.name} from ${source.downloadUrl}")
            val conn = URL(source.downloadUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("User-Agent", "RealTimeTranslateTTS/1.0")

            if (conn.responseCode != 200) {
                Log.w(TAG, "Download failed: HTTP ${conn.responseCode}")
                return@withContext -1
            }

            var inputStream: InputStream = conn.inputStream
            // Handle gzip
            if (source.downloadUrl.endsWith(".gz") || conn.contentEncoding == "gzip") {
                inputStream = java.util.zip.GZIPInputStream(inputStream)
            }

            val terms = mutableMapOf<String, String>()
            when (source.format) {
                "cedict" -> parseCedict(inputStream, terms)
                "csv" -> parseCsvStream(inputStream, ',', terms)
                "tsv" -> parseCsvStream(inputStream, '\t', terms)
                else -> Log.w(TAG, "Unknown format: ${source.format}")
            }
            conn.disconnect()

            if (terms.isEmpty()) return@withContext 0

            // Store downloaded terms under "general" (or source-specific domains)
            for (domain in source.domains) {
                val domainMap = downloadedTerms.getOrPut(domain) { mutableMapOf() }
                domainMap.putAll(terms)
            }
            saveDownloadedTerms(source)
            rebuildMerged()

            Log.i(TAG, "Downloaded ${terms.size} terms from ${source.name}")
            terms.size
        } catch (e: Throwable) {
            Log.e(TAG, "Download failed for ${source.name}: ${e.message}")
            -1
        }
    }

    /** Check source is enabled in registry. */
    fun isSourceEnabled(sourceId: String): Boolean = registry.find { it.sourceId == sourceId }?.enabled ?: false
    fun isSourceDownloaded(sourceId: String): Boolean {
        val dir = glossaryDir() ?: return false
        return File(dir, "cache/$sourceId").exists()
    }

    // ==================== Parsers ====================

    /** Parse CC-CEDICT format: Traditional Simplified [pinyin] /English1/English2/ */
    private fun parseCedict(input: InputStream, out: MutableMap<String, String>) {
        BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { reader ->
            var count = 0
            reader.lineSequence().forEach { line ->
                if (line.startsWith('#') || line.isBlank()) return@forEach
                try {
                    val spaceIdx = line.indexOf(' ')
                    if (spaceIdx < 0) return@forEach
                    val rest = line.substring(spaceIdx + 1)
                    val spaceIdx2 = rest.indexOf(' ')
                    if (spaceIdx2 < 0) return@forEach
                    val simplified = rest.substring(0, spaceIdx2)
                    val slashStart = rest.indexOf('/')
                    if (slashStart < 0) return@forEach
                    val definitions = rest.substring(slashStart).trim('/')
                        .split('/').firstOrNull()?.trim() ?: return@forEach
                    if (simplified.isNotBlank() && definitions.isNotBlank() && definitions.length < 100) {
                        out[definitions.lowercase()] = simplified
                        count++
                    }
                } catch (_: Throwable) { /* skip malformed lines */ }
            }
            Log.d(TAG, "Parsed CEDICT: $count entries")
        }
    }

    private fun parseCsvStream(input: InputStream, sep: Char, out: MutableMap<String, String>) {
        BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { reader ->
            var first = true
            reader.lineSequence().forEach { line ->
                if (line.isBlank()) return@forEach
                val parts = line.split(sep, limit = 2)
                if (parts.size < 2) return@forEach
                val src = parts[0].trim().removeSurrounding("\"")
                val tgt = parts[1].trim().removeSurrounding("\"")
                if (first) {
                    first = false
                    if (src.equals("source", true) || src.equals("en", true)) return@forEach
                }
                if (src.isNotBlank() && tgt.isNotBlank()) out[src.lowercase()] = tgt
            }
        }
    }

    // ==================== License ====================

    private val allowedLicenses = setOf(
        "CC0-1.0", "CC-BY-4.0", "CC-BY-SA-4.0", "MIT", "Apache-2.0",
        "BSD-2-Clause", "BSD-3-Clause", "public-domain"
    )

    private fun isLicenseAllowed(license: String): Boolean =
        allowedLicenses.any { license.contains(it, ignoreCase = true) }

    // ==================== Persistence ====================

    private fun glossaryDir(): File? {
        val dir = filesDir?.let { File(it, "glossary") } ?: return null
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun saveUserGlossaries() {
        val dir = glossaryDir() ?: return
        try {
            val arr = JSONArray()
            for (ug in userGlossaries) {
                arr.put(JSONObject().apply {
                    put("id", ug.id); put("fileName", ug.fileName)
                    put("domain", ug.domain); put("entryCount", ug.entryCount)
                    put("importedAt", ug.importedAt)
                })
            }
            File(dir, "user_glossaries.json").writeText(arr.toString())
        } catch (e: Throwable) { Log.e(TAG, "Save user glossaries failed: ${e.message}") }
    }

    private fun loadUserGlossaries() {
        val dir = glossaryDir() ?: return
        val file = File(dir, "user_glossaries.json")
        if (!file.exists()) return
        try {
            val arr = JSONArray(file.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                userGlossaries.add(UserGlossary(
                    o.getString("id"), o.getString("fileName"),
                    o.getString("domain"), o.getInt("entryCount"),
                    o.getLong("importedAt")
                ))
            }
            // Reload all user term files
            for (ug in userGlossaries) reloadUserTermsForDomain(ug.domain)
        } catch (e: Throwable) { Log.e(TAG, "Load user glossaries failed: ${e.message}") }
    }

    private fun saveUserTerms(domain: String) {
        val dir = glossaryDir() ?: return
        val userDir = File(dir, "user")
        if (!userDir.exists()) userDir.mkdirs()
        val terms = userTerms[domain] ?: return
        try {
            val arr = JSONArray()
            terms.forEach { (en, zh) -> arr.put(JSONObject().apply { put("en", en); put("zh", zh) }) }
            File(userDir, "${domain}_terms.json").writeText(arr.toString())
        } catch (e: Throwable) { Log.e(TAG, "Save user terms failed: ${e.message}") }
    }

    private fun reloadUserTermsForDomain(domain: String) {
        val dir = glossaryDir() ?: return
        val file = File(dir, "user/${domain}_terms.json")
        if (!file.exists()) return
        try {
            val map = mutableMapOf<String, String>()
            val arr = JSONArray(file.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                map[o.getString("en")] = o.getString("zh")
            }
            if (map.isNotEmpty()) userTerms[domain] = map
        } catch (e: Throwable) { Log.e(TAG, "Reload user terms failed: ${e.message}") }
    }

    private fun saveDownloadedTerms(source: GlossarySource) {
        val dir = glossaryDir() ?: return
        val cacheDir = File(dir, "cache/${source.sourceId}")
        if (!cacheDir.exists()) cacheDir.mkdirs()
        for (domain in source.domains) {
            val terms = downloadedTerms[domain] ?: continue
            try {
                val arr = JSONArray()
                terms.forEach { (en, zh) -> arr.put(JSONObject().apply { put("en", en); put("zh", zh) }) }
                File(cacheDir, "${domain}_terms.json").writeText(arr.toString())
            } catch (e: Throwable) { Log.e(TAG, "Save downloaded terms failed: ${e.message}") }
        }
        // Save metadata
        try {
            File(cacheDir, "meta.json").writeText(JSONObject().apply {
                put("sourceId", source.sourceId)
                put("downloadedAt", System.currentTimeMillis())
            }.toString())
        } catch (_: Throwable) {}
    }

    private fun loadDownloadedTerms() {
        val dir = glossaryDir() ?: return
        val cacheDir = File(dir, "cache")
        if (!cacheDir.exists()) return
        cacheDir.listFiles()?.forEach { sourceDir ->
            if (!sourceDir.isDirectory) return@forEach
            sourceDir.listFiles()?.filter { it.name.endsWith("_terms.json") }?.forEach { termFile ->
                val domain = termFile.name.removeSuffix("_terms.json")
                try {
                    val arr = JSONArray(termFile.readText())
                    val map = mutableMapOf<String, String>()
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        map[o.getString("en")] = o.getString("zh")
                    }
                    if (map.isNotEmpty()) {
                        downloadedTerms.getOrPut(domain) { mutableMapOf() }.putAll(map)
                    }
                } catch (e: Throwable) { Log.w(TAG, "Load cached terms failed: ${e.message}") }
            }
        }
    }
}
