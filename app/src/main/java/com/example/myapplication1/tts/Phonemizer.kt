package com.example.myapplication1.tts

import android.content.Context
import android.util.Log
import androidx.annotation.Keep
import java.io.File
import java.io.FileOutputStream

@Keep
object Phonemizer {

    private const val TAG = "Phonemizer"

    @Volatile private var jniLoaded = false
    @Volatile private var initTried = false
    @Volatile private var appDataDir: String? = null // 传给 native 的“包含 espeak-ng-data 的父目录”

    @Volatile private var defaultVoice: String = "cmn" // 由上层设置

    // ===== JNI 方法（与 C++ 名称对应）=====
    private external fun nativeInit(dataDir: String?)
    private external fun ipaFromText(text: String): String
    private external fun nativeTerminate()
    private external fun nativeSetVoice(name: String): Boolean
    private external fun nativeListVoices(): Array<String>

    /** 供应用在启动时调用：准备 espeak 数据并初始化 JNI */
    @Synchronized
    fun init(context: Context): Boolean {
        if (jniLoaded) return true
        // 1) 确保 assets 中的 espeak-ng-data 拷贝到 files 目录
        val parent = context.filesDir
        val esData = File(parent, "espeak-ng-data")
        try {
            if (!esData.exists()) {
                Log.i(TAG, "📦 开始拷贝 espeak-ng-data...")
                copyAssetDirRecursively(context, "espeak-ng-data", esData)
                Log.i(TAG, "📦 espeak-ng-data 已拷贝: ${esData.absolutePath}")
            } else {
                Log.i(TAG, "📦 espeak-ng-data 已存在: ${esData.absolutePath}")
            }

            // 验证关键文件存在
            var phonData = File(esData, "phondata")
            var voicesDir = File(esData, "voices")
            if (!phonData.exists() || !voicesDir.exists() || !voicesDir.isDirectory) {
                Log.w(TAG, "⚠️ 检测到 espeak-ng-data 不完整，尝试重新复制...")
                // 尝试清理后完整复制一遍
                esData.deleteRecursively()
                copyAssetDirRecursively(context, "espeak-ng-data", esData)
                // 复制后再次验证
                phonData = File(esData, "phondata")
                voicesDir = File(esData, "voices")
            }
            if (!phonData.exists()) {
                Log.e(TAG, "❌ phondata 不存在: ${phonData.absolutePath}")
                return false
            }
            if (!voicesDir.exists() || !voicesDir.isDirectory) {
                Log.e(TAG, "❌ voices 目录不存在: ${voicesDir.absolutePath}")
                return false
            }
            Log.i(TAG, "✅ 关键文件验证通过")

        } catch (t: Throwable) {
            Log.e(TAG, "❌ 拷贝 espeak-ng-data 失败: ${t.message}", t)
            return false
        }

        // 设置 native 所需的根目录（其下应包含 espeak-ng-data 子目录）
        appDataDir = parent.absolutePath
        Log.i(TAG, "🔑 将传递给 native: $appDataDir")
        return ensureLoadedInternal()
    }

    /** 仅在已设置 appDataDir 时进行 native 加载 */
    @Synchronized
    private fun ensureLoadedInternal(): Boolean {
        if (jniLoaded) return true
        if (appDataDir == null) {
            Log.e(TAG, "❌ appDataDir is null, cannot initialize")
            return false
        }
        if (initTried) return false

        return try {
            initTried = true
            Log.i(TAG, "loadLibrary(espeak-jni) ...")
            System.loadLibrary("espeak-jni")
            Log.i(TAG, "calling nativeInit(dataDir=$appDataDir) ...")
            nativeInit(appDataDir)
            jniLoaded = true
            Log.i(TAG, "✅ espeak-jni loaded & inited (dataDir=$appDataDir)")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "❌ load/init espeak-jni failed", t)
            false
        }
    }

    /** 显式设置 eSpeak voice（例如根据 Piper config.espeak.voice）*/
    fun setVoice(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        if (!jniLoaded && !ensureLoadedInternal()) return false
        return try {
            val ok = nativeSetVoice(name)
            if (ok) defaultVoice = name
            Log.i(TAG, if (ok) "✅ setVoice($name)" else "❌ setVoice($name) failed")
            ok
        } catch (t: Throwable) {
            Log.w(TAG, "setVoice failed: ${t.message}")
            false
        }
    }

    fun setDefaultVoice(name: String) {
        if (name.isNotBlank()) defaultVoice = name
    }

    fun listVoices(): List<String> {
        if (!jniLoaded && !ensureLoadedInternal()) return emptyList()
        return try {
            nativeListVoices().toList()
        } catch (t: Throwable) {
            Log.w(TAG, "listVoices failed: ${t.message}")
            emptyList()
        }
    }

    /** 供外部在 onDestroy 时调用，释放 eSpeak 资源 */
    fun shutdown() {
        if (!jniLoaded) return
        try {
            nativeTerminate()
            Log.i(TAG, "🧹 espeak-jni terminated")
        } catch (t: Throwable) {
            Log.w(TAG, "terminate failed: ${t.message}")
        } finally {
            jniLoaded = false
            initTried = false
        }
    }

    /** Piper 风格：整段使用默认 voice（通常为 cmn）取 IPA，不再插入 (lang) 标记，也不再切换到 en */
    fun textToPhonemes(text: String, normalizeTone: Boolean = true): List<String> {
        if (text.isBlank()) return emptyList()
        if (!jniLoaded && !ensureLoadedInternal()) return emptyList()

        // 确保使用默认 voice
        try {
            nativeSetVoice(defaultVoice)
        } catch (_: Throwable) {
            // ignore
        }

        val raw = try {
            ipaFromText(text)
        } catch (t: Throwable) {
            Log.e(TAG, "ipaFromText crashed for [$text]", t)
            ""
        }
        val tokens = cleanIpa(raw, normalizeTone)
        Log.i(TAG, "🎙 IPA(raw): ${tokens.joinToString(" ")}")
        return tokens
    }

    // 语言切分逻辑已不再使用，保留以备调试
    private fun splitByLangRuns(s: String): List<Pair<String, String>> {
        val res = ArrayList<Pair<String,String>>()
        val sb = StringBuilder()
        var cur = ""
        fun flush() { if (sb.isNotEmpty()) { res.add(cur to sb.toString()); sb.setLength(0) } }
        for (ch in s) {
            val lang = when {
                ch.isAsciiLetterOrDigit() || ch.isAsciiPunct() -> "en"
                ch.isCjk() -> "cmn"
                else -> "cmn" // 默认归入目标语言
            }
            if (cur != lang && cur.isNotEmpty()) {
                flush()
            }
            if (cur != lang) cur = lang
            sb.append(ch)
        }
        flush()
        return res
    }

    private fun Char.isAsciiLetterOrDigit(): Boolean = this.code in 0x30..0x39 || this.code in 0x41..0x5A || this.code in 0x61..0x7A
    private fun Char.isAsciiPunct(): Boolean = this in listOf(' ', '.', ',', '?', '!', ';', ':', '\'', '"', '-', '_', '(', ')', '/', '\\')
    private fun Char.isCjk(): Boolean {
        val cp = this.code
        return (cp in 0x4E00..0x9FFF) || (cp in 0x3400..0x4DBF) || (cp in 0x20000..0x2EBEF)
    }

    // 严格按 Piper：仅把 eSpeak 返回中的斜杠作为分隔转空格，并标准化空白；可选把调号归一化为 1-5
    private fun cleanIpa(raw: String, normalizeTone: Boolean): List<String> {
        if (raw.isBlank()) return emptyList()
        var s = raw
            .replace('/', ' ')
        if (normalizeTone) {
            s = s.replace("˥", "5")
                .replace("˦", "4")
                .replace("˧", "3")
                .replace("˨", "2")
                .replace("˩", "1")
        }
        s = s.replace("\\s+".toRegex(), " ").trim()
        return s.split(' ').filter { it.isNotBlank() }
    }

    // 递归复制 asset 目录
    private fun copyAssetDirRecursively(context: Context, assetDir: String, dstDir: File) {
        if (!dstDir.exists()) dstDir.mkdirs()
        val list = context.assets.list(assetDir) ?: emptyArray()
        for (name in list) {
            val path = "$assetDir/$name"
            val children = context.assets.list(path)
            if (children.isNullOrEmpty()) {
                val outFile = File(dstDir, name)
                context.assets.open(path).use { input ->
                    FileOutputStream(outFile).use { output -> input.copyTo(output) }
                }
            } else {
                copyAssetDirRecursively(context, path, File(dstDir, name))
            }
        }
    }
}
