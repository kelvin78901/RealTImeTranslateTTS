package com.example.myapplication1.translation

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
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

abstract class TranslationEngine {
    abstract suspend fun translate(text: String): String
    /** True if this engine uses an LLM API (can combine translation + refinement in one call). */
    open val isLlmBased: Boolean = false
    open fun close() {}
}

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
 * LLM-based translation via OpenAI-compatible chat completions API.
 * Works with OpenAI, Groq, and other compatible providers.
 */
class LLMTranslation(
    private val apiKey: String,
    private val baseUrl: String = "https://api.openai.com/v1",
    private val model: String = "gpt-4o-mini"
) : TranslationEngine() {
    override val isLlmBased: Boolean = true

    companion object {
        private val sharedClient = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    override suspend fun translate(text: String): String = withContext(Dispatchers.IO) {
        val json = JSONObject().apply {
            put("model", model)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", "你是专业同声传译员。将英文翻译为地道的中文。只输出翻译结果，不要任何解释或标点修改。")
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", text)
                })
            })
            put("temperature", 0.3)
            put("max_tokens", 500)
        }

        val request = Request.Builder()
            .url("$baseUrl/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .post(json.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val response = sharedClient.newCall(request).execute()
        val body = response.body?.string() ?: throw Exception("Empty response")
        if (!response.isSuccessful) throw Exception("HTTP ${response.code}")

        JSONObject(body)
            .getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")
            .getString("content")
            .trim()
    }
}

/**
 * Local server translation (Ollama, LM Studio, etc.) via OpenAI-compatible API.
 * Uses longer timeouts for local inference.
 */
class LocalServerTranslation(
    private val serverUrl: String,
    private val model: String = "qwen2.5:7b"
) : TranslationEngine() {
    override val isLlmBased: Boolean = true

    companion object {
        private val localClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    override suspend fun translate(text: String): String = withContext(Dispatchers.IO) {
        val url = serverUrl.trimEnd('/') + "/chat/completions"
        val json = JSONObject().apply {
            put("model", model)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", "你是专业同声传译员。将英文翻译为地道的中文。只输出翻译结果，不要任何解释或标点修改。")
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", text)
                })
            })
            put("temperature", 0.3)
            put("max_tokens", 500)
            put("stream", false)
        }

        val request = Request.Builder()
            .url(url)
            .post(json.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val response = localClient.newCall(request).execute()
        val body = response.body?.string() ?: throw Exception("Empty response")
        if (!response.isSuccessful) throw Exception("HTTP ${response.code}: $body")

        JSONObject(body)
            .getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")
            .getString("content")
            .trim()
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

    override suspend fun translate(text: String): String = withContext(Dispatchers.IO) {
        val base = if (apiKey.endsWith(":fx")) "https://api-free.deepl.com" else "https://api.deepl.com"
        val formBody = FormBody.Builder()
            .add("text", text)
            .add("source_lang", "EN")
            .add("target_lang", "ZH")
            .build()

        val request = Request.Builder()
            .url("$base/v2/translate")
            .addHeader("Authorization", "DeepL-Auth-Key $apiKey")
            .post(formBody)
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
