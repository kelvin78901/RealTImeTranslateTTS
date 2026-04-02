package com.example.myapplication1.translation

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

abstract class TranslationEngine {
    abstract suspend fun translate(text: String): String

    /**
     * Context-aware translation. Engines that support background/glossary/latency
     * override this; others fall back to the plain [translate].
     */
    open suspend fun translate(text: String, context: TranslationContext): String = translate(text)

    /** True if this engine uses an LLM API (can combine translation + refinement in one call). */
    open val isLlmBased: Boolean = false
    open fun close() {}
}

/**
 * Shared system prompt for simultaneous interpreting.
 * Concise and deterministic: low temperature + brief output instruction.
 */
private const val SI_SYSTEM_PROMPT =
    "你是专业同声传译员（英译中）。" +
    "请将用户输入的英文直接翻译为地道、简洁的中文。" +
    "只输出翻译结果，不得包含任何解释、标注或额外标点。"

/**
 * MLKit offline translation (EN→ZH).
 */
class MlKitTranslation(
    private val translator: com.google.mlkit.nl.translate.Translator
) : TranslationEngine() {
    override suspend fun translate(text: String): String = suspendCancellableCoroutine { cont ->
        translator.translate(text)
            .addOnSuccessListener { cont.resume(it) }
            .addOnFailureListener { cont.resumeWithException(it) }
    }
}

/**
 * LLM-based translation via OpenAI-compatible chat completions API (streaming).
 *
 * Uses SSE streaming so the first tokens arrive as soon as the model starts generating,
 * reducing time-to-first-byte compared to waiting for the full non-streaming response.
 * The complete translated string is returned once the stream ends.
 *
 * Works with OpenAI, Groq, and other compatible providers.
 */
class LLMTranslation(
    private val apiKey: String,
    private val baseUrl: String = "https://api.openai.com/v1",
    private val model: String = "gpt-4o-mini"
) : TranslationEngine() {
    override val isLlmBased: Boolean = true

    companion object {
        private const val TAG = "LLMTranslation"

        private val sharedClient = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()

        /**
         * Estimate a safe max_tokens bound for a single-sentence translation.
         * Chinese is ~1.5x English word count; each Chinese char ≈ 2 BPE tokens → factor ≈ 3.
         * Upper bound is 600 (single sentence); use a higher cap for multi-sentence paragraph work.
         */
        private fun maxTokensFor(text: String): Int {
            val words = text.trim().split(Regex("""\s+""")).size
            return (words * 3).coerceIn(80, 600)
        }
    }

    override suspend fun translate(text: String): String = translate(text, TranslationContext())

    override suspend fun translate(text: String, context: TranslationContext): String = withContext(Dispatchers.IO) {
        val systemPrompt = buildContextPrompt(SI_SYSTEM_PROMPT, context)

        val json = JSONObject().apply {
            put("model", model)
            put("messages", JSONArray().apply {
                put(JSONObject().apply { put("role", "system"); put("content", systemPrompt) })
                put(JSONObject().apply { put("role", "user"); put("content", text) })
            })
            put("temperature", 0.2)
            put("max_tokens", maxTokensFor(text))
            put("stream", true)
        }

        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .post(json.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val response = sharedClient.newCall(request).execute()
        if (!response.isSuccessful) {
            val errBody = response.body?.string() ?: ""
            throw Exception("HTTP ${response.code}: $errBody")
        }

        collectSseTokens(response.body?.charStream()?.buffered())
    }
}

/**
 * Local server translation (Ollama, LM Studio, etc.) via OpenAI-compatible API.
 * Uses streaming + longer timeouts for local inference.
 */
class LocalServerTranslation(
    private val serverUrl: String,
    private val model: String = "qwen2.5:7b"
) : TranslationEngine() {
    override val isLlmBased: Boolean = true

    companion object {
        private const val TAG = "LocalServerTranslation"

        private val localClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    override suspend fun translate(text: String): String = translate(text, TranslationContext())

    override suspend fun translate(text: String, context: TranslationContext): String = withContext(Dispatchers.IO) {
        val url = serverUrl.trimEnd('/') + "/chat/completions"
        val systemPrompt = buildContextPrompt(SI_SYSTEM_PROMPT, context)

        val json = JSONObject().apply {
            put("model", model)
            put("messages", JSONArray().apply {
                put(JSONObject().apply { put("role", "system"); put("content", systemPrompt) })
                put(JSONObject().apply { put("role", "user"); put("content", text) })
            })
            put("temperature", 0.2)
            put("max_tokens", 600)
            put("stream", true)
        }

        val request = Request.Builder()
            .url(url)
            .post(json.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val response = localClient.newCall(request).execute()
        if (!response.isSuccessful) {
            val errBody = response.body?.string() ?: ""
            throw Exception("HTTP ${response.code}: $errBody")
        }

        collectSseTokens(response.body?.charStream()?.buffered())
    }
}

/**
 * DeepL API translation.
 * Automatically detects free vs pro plan based on API key suffix.
 */
class DeepLTranslation(private val apiKey: String) : TranslationEngine() {

    companion object {
        private val sharedClient = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    override suspend fun translate(text: String): String = translate(text, TranslationContext())

    override suspend fun translate(text: String, context: TranslationContext): String = withContext(Dispatchers.IO) {
        val base = if (apiKey.endsWith(":fx")) "https://api-free.deepl.com" else "https://api.deepl.com"
        val formBuilder = FormBody.Builder()
            .add("text", text)
            .add("source_lang", "EN")
            .add("target_lang", "ZH")

        // DeepL context parameter: additional context for disambiguation (not billed)
        if (context.background.isNotBlank()) {
            formBuilder.add("context", context.background.take(300))
        }

        // DeepL model_type: latency_optimized or quality_optimized
        when (context.latencyMode) {
            LatencyMode.REALTIME -> formBuilder.add("model_type", "latency_optimized")
            LatencyMode.QUALITY -> formBuilder.add("model_type", "quality_optimized")
            else -> {} // balanced uses DeepL default
        }

        val request = Request.Builder()
            .url("$base/v2/translate")
            .addHeader("Authorization", "DeepL-Auth-Key $apiKey")
            .post(formBuilder.build())
            .build()

        val response = sharedClient.newCall(request).execute()
        val body = response.body?.string() ?: throw Exception("Empty response")
        if (!response.isSuccessful) throw Exception("DeepL ${response.code}")

        JSONObject(body)
            .getJSONArray("translations")
            .getJSONObject(0)
            .getString("text")
            .trim()
    }
}

// ---------------------------------------------------------------------------
// Context prompt builder — shared by LLM engines
// ---------------------------------------------------------------------------

/**
 * Augment a base system prompt with optional background context and glossary terms.
 * Returns the original prompt unmodified if context has nothing to add.
 */
internal fun buildContextPrompt(base: String, context: TranslationContext): String {
    if (context.background.isBlank() && context.glossaryTerms.isEmpty()) return base
    return buildString {
        append(base)
        if (context.background.isNotBlank()) {
            append("\n\n背景信息（仅供理解上下文，无需翻译）：")
            append(context.background.take(300))
        }
        if (context.glossaryTerms.isNotEmpty()) {
            append("\n\n参考术语（请优先使用以下译法）：")
            context.glossaryTerms.entries.take(20).forEach { (en, zh) ->
                append("\n- $en → $zh")
            }
        }
    }
}

// ---------------------------------------------------------------------------
// SSE streaming helper — shared by LLMTranslation and LocalServerTranslation
// ---------------------------------------------------------------------------

/**
 * Collects all delta tokens from an OpenAI-compatible SSE stream and returns
 * the concatenated result.  Handles both `data: {...}` lines and the `[DONE]`
 * sentinel.  Returns an empty string on parse errors rather than throwing.
 */
internal fun collectSseTokens(reader: BufferedReader?): String {
    if (reader == null) return ""
    val sb = StringBuilder()
    try {
        reader.use { br ->
            br.lineSequence().forEach { line ->
                if (!line.startsWith("data:")) return@forEach
                val payload = line.removePrefix("data:").trim()
                if (payload == "[DONE]") return@forEach
                try {
                    val delta = JSONObject(payload)
                        .getJSONArray("choices")
                        .getJSONObject(0)
                        .getJSONObject("delta")
                    val content = delta.optString("content", "")
                    if (content.isNotEmpty()) sb.append(content)
                } catch (_: Exception) { /* skip malformed lines */ }
            }
        }
    } catch (e: Exception) {
        Log.w("SSE", "Stream read error: ${e.message}")
    }
    return sb.toString().trim()
}
