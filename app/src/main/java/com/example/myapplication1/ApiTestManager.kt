package com.example.myapplication1

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * Tests each API endpoint step-by-step and reports where failures occur.
 *
 * Steps tested per API:
 * 1. DNS resolution / network reachability
 * 2. TCP connection (TLS handshake for HTTPS)
 * 3. Authentication (API key validity)
 * 4. Functional request (actual translation / ASR / TTS call)
 */
class ApiTestManager {

    data class StepResult(
        val step: String,
        val success: Boolean,
        val detail: String = "",
        val durationMs: Long = 0
    )

    data class ApiTestResult(
        val apiName: String,
        val steps: List<StepResult>,
        val overallSuccess: Boolean
    ) {
        val failedStep: StepResult? get() = steps.firstOrNull { !it.success }
    }

    companion object {
        private val testClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    // ===================== OpenAI Chat (Translation) =====================

    suspend fun testOpenAiTranslation(apiKey: String): ApiTestResult = withContext(Dispatchers.IO) {
        val steps = mutableListOf<StepResult>()
        val name = "OpenAI 翻译"

        // Step 1: DNS
        val dns = testDns("api.openai.com")
        steps.add(dns)
        if (!dns.success) return@withContext ApiTestResult(name, steps, false)

        // Step 2: Connection
        val conn = testConnection("https://api.openai.com/v1/models")
        steps.add(conn)
        if (!conn.success) return@withContext ApiTestResult(name, steps, false)

        // Step 3: Auth
        val auth = testOpenAiAuth(apiKey)
        steps.add(auth)
        if (!auth.success) return@withContext ApiTestResult(name, steps, false)

        // Step 4: Functional - translate
        val func = testChatCompletion(apiKey, "https://api.openai.com/v1", "gpt-4o-mini")
        steps.add(func)

        ApiTestResult(name, steps, steps.all { it.success })
    }

    // ===================== Groq Chat (Translation) =====================

    suspend fun testGroqTranslation(apiKey: String): ApiTestResult = withContext(Dispatchers.IO) {
        val steps = mutableListOf<StepResult>()
        val name = "Groq 翻译"

        val dns = testDns("api.groq.com")
        steps.add(dns)
        if (!dns.success) return@withContext ApiTestResult(name, steps, false)

        val conn = testConnection("https://api.groq.com/openai/v1/models")
        steps.add(conn)
        if (!conn.success) return@withContext ApiTestResult(name, steps, false)

        val auth = testGroqAuth(apiKey)
        steps.add(auth)
        if (!auth.success) return@withContext ApiTestResult(name, steps, false)

        val func = testChatCompletion(apiKey, "https://api.groq.com/openai/v1", "llama-3.3-70b-versatile")
        steps.add(func)

        ApiTestResult(name, steps, steps.all { it.success })
    }

    // ===================== DeepL Translation =====================

    suspend fun testDeepL(apiKey: String): ApiTestResult = withContext(Dispatchers.IO) {
        val steps = mutableListOf<StepResult>()
        val name = "DeepL 翻译"
        val isFree = apiKey.endsWith(":fx")
        val host = if (isFree) "api-free.deepl.com" else "api.deepl.com"
        val base = "https://$host"

        val dns = testDns(host)
        steps.add(dns)
        if (!dns.success) return@withContext ApiTestResult(name, steps, false)

        val conn = testConnection("$base/v2/usage")
        steps.add(conn)
        if (!conn.success) return@withContext ApiTestResult(name, steps, false)

        // Step 3: Auth via usage endpoint
        val auth = testDeepLAuth(apiKey, base)
        steps.add(auth)
        if (!auth.success) return@withContext ApiTestResult(name, steps, false)

        // Step 4: Functional translate
        val func = testDeepLTranslate(apiKey, base)
        steps.add(func)

        ApiTestResult(name, steps, steps.all { it.success })
    }

    // ===================== OpenAI Whisper ASR =====================

    suspend fun testOpenAiWhisper(apiKey: String): ApiTestResult = withContext(Dispatchers.IO) {
        val steps = mutableListOf<StepResult>()
        val name = "OpenAI Whisper ASR"

        val dns = testDns("api.openai.com")
        steps.add(dns)
        if (!dns.success) return@withContext ApiTestResult(name, steps, false)

        val conn = testConnection("https://api.openai.com/v1/models")
        steps.add(conn)
        if (!conn.success) return@withContext ApiTestResult(name, steps, false)

        val auth = testOpenAiAuth(apiKey)
        steps.add(auth)
        if (!auth.success) return@withContext ApiTestResult(name, steps, false)

        // Step 4: Functional — we can't test real audio without a mic, so we test model access
        val func = testModelAccess(apiKey, "https://api.openai.com/v1", "whisper-1")
        steps.add(func)

        ApiTestResult(name, steps, steps.all { it.success })
    }

    // ===================== Groq Whisper ASR =====================

    suspend fun testGroqWhisper(apiKey: String): ApiTestResult = withContext(Dispatchers.IO) {
        val steps = mutableListOf<StepResult>()
        val name = "Groq Whisper ASR"

        val dns = testDns("api.groq.com")
        steps.add(dns)
        if (!dns.success) return@withContext ApiTestResult(name, steps, false)

        val conn = testConnection("https://api.groq.com/openai/v1/models")
        steps.add(conn)
        if (!conn.success) return@withContext ApiTestResult(name, steps, false)

        val auth = testGroqAuth(apiKey)
        steps.add(auth)
        if (!auth.success) return@withContext ApiTestResult(name, steps, false)

        val func = testModelAccess(apiKey, "https://api.groq.com/openai/v1", "whisper-large-v3-turbo")
        steps.add(func)

        ApiTestResult(name, steps, steps.all { it.success })
    }

    // ===================== OpenAI TTS =====================

    suspend fun testOpenAiTts(apiKey: String): ApiTestResult = withContext(Dispatchers.IO) {
        val steps = mutableListOf<StepResult>()
        val name = "OpenAI TTS"

        val dns = testDns("api.openai.com")
        steps.add(dns)
        if (!dns.success) return@withContext ApiTestResult(name, steps, false)

        val conn = testConnection("https://api.openai.com/v1/models")
        steps.add(conn)
        if (!conn.success) return@withContext ApiTestResult(name, steps, false)

        val auth = testOpenAiAuth(apiKey)
        steps.add(auth)
        if (!auth.success) return@withContext ApiTestResult(name, steps, false)

        val func = testModelAccess(apiKey, "https://api.openai.com/v1", "tts-1")
        steps.add(func)

        ApiTestResult(name, steps, steps.all { it.success })
    }

    // ===================== Edge TTS =====================

    suspend fun testEdgeTts(): ApiTestResult = withContext(Dispatchers.IO) {
        val steps = mutableListOf<StepResult>()
        val name = "Edge TTS"

        val dns = testDns("speech.platform.bing.com")
        steps.add(dns)
        if (!dns.success) return@withContext ApiTestResult(name, steps, false)

        // Step 2: HTTPS connection
        val t2 = System.currentTimeMillis()
        try {
            val request = Request.Builder()
                .url("https://speech.platform.bing.com/consumer/speech/synthesize/readaloud/voices/list?trustedclienttoken=6A5AA1D4EAFF4E9FB37E23D68491D6F4")
                .build()
            val resp = testClient.newCall(request).execute()
            val d2 = System.currentTimeMillis() - t2
            if (resp.isSuccessful) {
                val body = resp.body?.string() ?: ""
                val voiceCount = try { JSONArray(body).length() } catch (_: Throwable) { 0 }
                steps.add(StepResult("连接", true, "HTTP ${resp.code}", d2))
                steps.add(StepResult("认证", true, "免费服务，无需 API Key", 0))
                steps.add(StepResult("功能", true, "可用语音: ${voiceCount}个", d2))
            } else {
                steps.add(StepResult("连接", true, "HTTP ${resp.code}", d2))
                steps.add(StepResult("认证", false, "HTTP ${resp.code}: 服务可能被限制", d2))
            }
        } catch (e: Throwable) {
            steps.add(StepResult("连接", false, "连接失败: ${e.message}", System.currentTimeMillis() - t2))
        }

        ApiTestResult(name, steps, steps.all { it.success })
    }

    // ===================== Google Translate TTS =====================

    suspend fun testGoogleTts(): ApiTestResult = withContext(Dispatchers.IO) {
        val steps = mutableListOf<StepResult>()
        val name = "Google 翻译 TTS"

        val dns = testDns("translate.google.com")
        steps.add(dns)
        if (!dns.success) return@withContext ApiTestResult(name, steps, false)

        val t2 = System.currentTimeMillis()
        try {
            val url = "https://translate.google.com/translate_tts?ie=UTF-8&client=tw-ob&tl=zh-CN&q=%E4%BD%A0%E5%A5%BD"
            val request = Request.Builder().url(url).build()
            val resp = testClient.newCall(request).execute()
            val d2 = System.currentTimeMillis() - t2
            val contentType = resp.header("Content-Type") ?: ""
            if (resp.isSuccessful && contentType.contains("audio")) {
                steps.add(StepResult("连接", true, "HTTP ${resp.code}", d2))
                steps.add(StepResult("认证", true, "免费服务，无需 API Key", 0))
                steps.add(StepResult("功能", true, "音频返回正常 ($contentType)", d2))
            } else {
                steps.add(StepResult("连接", true, "HTTP ${resp.code}", d2))
                steps.add(StepResult("功能", false, "HTTP ${resp.code}: ${contentType.ifBlank { "无音频数据" }}", d2))
            }
        } catch (e: Throwable) {
            steps.add(StepResult("连接", false, "请求失败: ${e.message}", System.currentTimeMillis() - t2))
        }

        ApiTestResult(name, steps, steps.all { it.success })
    }

    // ===================== Local Server =====================

    suspend fun testLocalServer(serverUrl: String, model: String): ApiTestResult = withContext(Dispatchers.IO) {
        val steps = mutableListOf<StepResult>()
        val name = "本地服务器"

        // Extract host from URL
        val host = try {
            java.net.URL(serverUrl).host
        } catch (e: Throwable) {
            steps.add(StepResult("DNS解析", false, "URL格式错误: $serverUrl", 0))
            return@withContext ApiTestResult(name, steps, false)
        }

        val dns = testDns(host)
        steps.add(dns)
        if (!dns.success) return@withContext ApiTestResult(name, steps, false)

        // Step 2: Connection — try /models endpoint
        val baseUrl = serverUrl.trimEnd('/')
        val t2 = System.currentTimeMillis()
        try {
            val request = Request.Builder().url("$baseUrl/models").build()
            val resp = testClient.newCall(request).execute()
            val d2 = System.currentTimeMillis() - t2
            if (resp.isSuccessful) {
                steps.add(StepResult("连接", true, "服务器响应正常", d2))
            } else {
                steps.add(StepResult("连接", false, "HTTP ${resp.code}: 服务器拒绝", d2))
                return@withContext ApiTestResult(name, steps, false)
            }
        } catch (e: Throwable) {
            steps.add(StepResult("连接", false, "无法连接: ${e.message}", System.currentTimeMillis() - t2))
            return@withContext ApiTestResult(name, steps, false)
        }

        steps.add(StepResult("认证", true, "本地服务器无需认证", 0))

        // Step 4: Functional — test chat completions
        val func = testChatCompletion("", baseUrl, model)
        steps.add(func)

        ApiTestResult(name, steps, steps.all { it.success })
    }

    // ===================== Shared Test Steps =====================

    private fun testDns(host: String): StepResult {
        val t = System.currentTimeMillis()
        return try {
            val addr = InetAddress.getByName(host)
            StepResult("DNS解析", true, "$host → ${addr.hostAddress}", System.currentTimeMillis() - t)
        } catch (e: Throwable) {
            StepResult("DNS解析", false, "无法解析 $host: ${e.message}", System.currentTimeMillis() - t)
        }
    }

    private fun testConnection(url: String): StepResult {
        val t = System.currentTimeMillis()
        return try {
            val request = Request.Builder().url(url).head().build()
            val resp = testClient.newCall(request).execute()
            val d = System.currentTimeMillis() - t
            // 401/403 is expected without auth — but proves connection works
            StepResult("连接", true, "HTTP ${resp.code} (${d}ms)", d)
        } catch (e: Throwable) {
            StepResult("连接", false, "连接失败: ${e.message}", System.currentTimeMillis() - t)
        }
    }

    private fun testOpenAiAuth(apiKey: String): StepResult {
        if (apiKey.isBlank()) return StepResult("认证", false, "API Key 为空", 0)
        val t = System.currentTimeMillis()
        return try {
            val request = Request.Builder()
                .url("https://api.openai.com/v1/models")
                .addHeader("Authorization", "Bearer $apiKey")
                .build()
            val resp = testClient.newCall(request).execute()
            val d = System.currentTimeMillis() - t
            if (resp.isSuccessful) {
                StepResult("认证", true, "API Key 有效", d)
            } else if (resp.code == 401) {
                StepResult("认证", false, "API Key 无效 (401 Unauthorized)", d)
            } else {
                StepResult("认证", false, "HTTP ${resp.code}", d)
            }
        } catch (e: Throwable) {
            StepResult("认证", false, "验证失败: ${e.message}", System.currentTimeMillis() - t)
        }
    }

    private fun testGroqAuth(apiKey: String): StepResult {
        if (apiKey.isBlank()) return StepResult("认证", false, "API Key 为空", 0)
        val t = System.currentTimeMillis()
        return try {
            val request = Request.Builder()
                .url("https://api.groq.com/openai/v1/models")
                .addHeader("Authorization", "Bearer $apiKey")
                .build()
            val resp = testClient.newCall(request).execute()
            val d = System.currentTimeMillis() - t
            if (resp.isSuccessful) {
                StepResult("认证", true, "API Key 有效", d)
            } else if (resp.code == 401) {
                StepResult("认证", false, "API Key 无效 (401 Unauthorized)", d)
            } else {
                StepResult("认证", false, "HTTP ${resp.code}", d)
            }
        } catch (e: Throwable) {
            StepResult("认证", false, "验证失败: ${e.message}", System.currentTimeMillis() - t)
        }
    }

    private fun testDeepLAuth(apiKey: String, base: String): StepResult {
        if (apiKey.isBlank()) return StepResult("认证", false, "API Key 为空", 0)
        val t = System.currentTimeMillis()
        return try {
            val request = Request.Builder()
                .url("$base/v2/usage")
                .addHeader("Authorization", "DeepL-Auth-Key $apiKey")
                .build()
            val resp = testClient.newCall(request).execute()
            val body = resp.body?.string()
            val d = System.currentTimeMillis() - t
            if (resp.isSuccessful && body != null) {
                val json = JSONObject(body)
                val used = json.optLong("character_count", 0)
                val limit = json.optLong("character_limit", 0)
                StepResult("认证", true, "已用 $used / $limit 字符", d)
            } else if (resp.code == 403) {
                StepResult("认证", false, "API Key 无效 (403 Forbidden)", d)
            } else {
                StepResult("认证", false, "HTTP ${resp.code}", d)
            }
        } catch (e: Throwable) {
            StepResult("认证", false, "验证失败: ${e.message}", System.currentTimeMillis() - t)
        }
    }

    private fun testDeepLTranslate(apiKey: String, base: String): StepResult {
        val t = System.currentTimeMillis()
        return try {
            val formBody = FormBody.Builder()
                .add("text", "Hello")
                .add("source_lang", "EN")
                .add("target_lang", "ZH")
                .build()
            val request = Request.Builder()
                .url("$base/v2/translate")
                .addHeader("Authorization", "DeepL-Auth-Key $apiKey")
                .post(formBody)
                .build()
            val resp = testClient.newCall(request).execute()
            val body = resp.body?.string()
            val d = System.currentTimeMillis() - t
            if (resp.isSuccessful && body != null) {
                val text = JSONObject(body).getJSONArray("translations").getJSONObject(0).getString("text")
                StepResult("功能", true, "\"Hello\" → \"$text\"", d)
            } else {
                StepResult("功能", false, "翻译失败: HTTP ${resp.code}", d)
            }
        } catch (e: Throwable) {
            StepResult("功能", false, "翻译请求失败: ${e.message}", System.currentTimeMillis() - t)
        }
    }

    private fun testChatCompletion(apiKey: String, baseUrl: String, model: String): StepResult {
        val t = System.currentTimeMillis()
        return try {
            val json = JSONObject().apply {
                put("model", model)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", "Translate to Chinese: Hello")
                    })
                })
                put("max_tokens", 50)
                put("temperature", 0.1)
            }
            val reqBuilder = Request.Builder()
                .url("${baseUrl.trimEnd('/')}/chat/completions")
                .post(json.toString().toRequestBody("application/json".toMediaType()))
            if (apiKey.isNotBlank()) {
                reqBuilder.addHeader("Authorization", "Bearer $apiKey")
            }
            val resp = testClient.newCall(reqBuilder.build()).execute()
            val body = resp.body?.string()
            val d = System.currentTimeMillis() - t
            if (resp.isSuccessful && body != null) {
                val reply = JSONObject(body).getJSONArray("choices")
                    .getJSONObject(0).getJSONObject("message").getString("content").trim()
                StepResult("功能", true, "\"Hello\" → \"${reply.take(50)}\"", d)
            } else {
                val errMsg = body?.let {
                    try { JSONObject(it).optJSONObject("error")?.optString("message") } catch (_: Throwable) { null }
                } ?: "HTTP ${resp.code}"
                StepResult("功能", false, "请求失败: $errMsg", d)
            }
        } catch (e: Throwable) {
            StepResult("功能", false, "请求失败: ${e.message}", System.currentTimeMillis() - t)
        }
    }

    private fun testModelAccess(apiKey: String, baseUrl: String, modelId: String): StepResult {
        val t = System.currentTimeMillis()
        return try {
            val request = Request.Builder()
                .url("${baseUrl.trimEnd('/')}/models/$modelId")
                .addHeader("Authorization", "Bearer $apiKey")
                .build()
            val resp = testClient.newCall(request).execute()
            val d = System.currentTimeMillis() - t
            if (resp.isSuccessful) {
                StepResult("功能", true, "模型 $modelId 可访问", d)
            } else if (resp.code == 404) {
                StepResult("功能", false, "模型 $modelId 不存在或无权限", d)
            } else {
                StepResult("功能", false, "模型查询失败: HTTP ${resp.code}", d)
            }
        } catch (e: Throwable) {
            StepResult("功能", false, "模型查询失败: ${e.message}", System.currentTimeMillis() - t)
        }
    }
}
