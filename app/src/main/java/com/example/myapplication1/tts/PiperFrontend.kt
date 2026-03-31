package com.example.myapplication1.tts

import android.content.Context
import android.util.Log
import org.json.JSONObject

class PiperFrontend(
    private val context: Context,
    private val symbolToId: Map<String, Int>,
    private val cfg: JSONObject
) {
    private val tag = "PiperFrontend"
    private val bos = "^"
    private val eos = "$"

    init {
        // 设置 espeak 语音为 cmn（与 Windows 配置一致），用反射避免编译期依赖
        try {
            val espk = cfg.optJSONObject("espeak")
            val voice = espk?.optString("voice")?.ifBlank { null } ?: "cmn"
            val clazz = Class.forName("com.example.myapplication1.tts.Phonemizer")
            val m = clazz.methods.firstOrNull { it.name == "setVoice" && it.parameterTypes.size == 1 && it.parameterTypes[0] == String::class.java }
            if (m != null) {
                m.invoke(null, voice)
                Log.i(tag, "espeak voice set to: $voice")
            } else {
                Log.i(tag, "Phonemizer.setVoice(String) not found, skip")
            }
        } catch (e: Throwable) {
            Log.w(tag, "set espeak voice failed: ${e.message}")
        }
    }

    // 与 Piper 一致：按 Unicode 码点逐字符映射到 phoneme_id_map
    fun encode(text: String): IntArray {
        val seq = ArrayList<Int>(64)
        symbolToId[bos]?.let { seq.add(it) }

        val ipaTokens = try {
            // 复用现有 JNI：拿到一串 token，再按“字符级”展开
            Phonemizer.textToPhonemes(text)
        } catch (_: Throwable) {
            emptyList()
        }

        if (ipaTokens.isNotEmpty()) {
            for (tok in ipaTokens) {
                for (ch in iterateCodePoints(tok)) {
                    symbolToId[ch]?.let { seq.add(it) }
                }
            }
        } else {
            // 兜底：直接对输入文本逐字符查表（不理想，但不再进行任何自定义音素处理）
            for (ch in iterateCodePoints(text)) {
                symbolToId[ch]?.let { seq.add(it) }
            }
        }

        symbolToId[eos]?.let { seq.add(it) }
        Log.i(tag, "🧩 ids=${seq.size} (char-level, piper-style)")
        return seq.toIntArray()
    }

    private fun iterateCodePoints(s: String): Sequence<String> = sequence {
        var i = 0
        val n = s.length
        while (i < n) {
            val cp = Character.codePointAt(s, i)
            val len = Character.charCount(cp)
            yield(String(Character.toChars(cp)))
            i += len
        }
    }
}
