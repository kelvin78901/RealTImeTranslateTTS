package com.example.myapplication1.tts

import android.content.Context
import android.content.res.AssetManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.min

class SherpaOnnxTts(
    private val context: Context,
    private val assetDir: String = "tts/vits-icefall-zh-aishell3"
) : AutoCloseable {

    companion object { private const val TAG = "SherpaOnnxTts" }

    @Volatile private var ready = false
    private var ttsObj: Any? = null
    private var sampleRate: Int = 22050
    private var track: AudioTrack? = null

    fun isReady() = ready

    fun init(): Boolean {
        try {
            tryLoadSherpaJni()

            // 1) 复制 assets 到 filesDir（用于“文件模式”）
            val outDir = File(context.filesDir, "sherpa-onnx-tts").apply { mkdirs() }
            copyAssetDirRecursively(assetDir, outDir)

            // 2) 关键文件(文件模式用绝对路径)
            val modelOnnx = findFirstFile(outDir, listOf(".onnx"))
                ?: throw IllegalStateException("model.onnx 未找到（assets/$assetDir）")
            val tokensTxt = findFirstFile(outDir, listOf("tokens.txt"))
                ?: throw IllegalStateException("tokens.txt 未找到（assets/$assetDir）")
            val lexiconTxt = findFirstFile(outDir, listOf("lexicon.txt", "lexicon-zh.txt")) // 可选

            // 3) 反射类
            val clsOfflineTts = Class.forName("com.k2fsa.sherpa.onnx.OfflineTts")
            val clsOfflineTtsConfig = Class.forName("com.k2fsa.sherpa.onnx.OfflineTtsConfig")
            val clsOfflineTtsModelConfig = Class.forName("com.k2fsa.sherpa.onnx.OfflineTtsModelConfig")
            val clsVits = Class.forName("com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig")

            logConstructors("OfflineTts", clsOfflineTts)
            logConstructors("OfflineTtsConfig", clsOfflineTtsConfig)
            logConstructors("OfflineTtsVitsModelConfig", clsVits)

            // ---------- 先构建“文件模式”的 Config ----------
            val vitsFileCfg = newNoArg(clsVits)?.also {
                setByName(it, "model", modelOnnx.absolutePath)
                setByName(it, "tokens", tokensTxt.absolutePath)
                lexiconTxt?.let { f -> setByName(it, "lexicon", f.absolutePath) }
                setByName(it, "dict", "")
                setByName(it, "data", "")
                setByName(it, "noiseScale", 0.667f)
                setByName(it, "lengthScale", 1.0f)
                setByName(it, "noiseScaleW", 0.8f)
            } ?: newByExactCtorOrNull(
                clsVits,
                arrayOf(
                    String::class.java, String::class.java, String::class.java,
                    String::class.java, String::class.java,
                    java.lang.Float.TYPE, java.lang.Float.TYPE, java.lang.Float.TYPE
                ),
                arrayOf(
                    modelOnnx.absolutePath,
                    tokensTxt.absolutePath,
                    lexiconTxt?.absolutePath ?: "",
                    "", "", 0.667f, 1.0f, 0.8f
                )
            ) ?: error("无法实例化 OfflineTtsVitsModelConfig（文件模式）")

            val modelFileCfg =
                newBySingleArgCtorOrNull(clsOfflineTtsModelConfig, vitsFileCfg)
                    ?: newNoArg(clsOfflineTtsModelConfig)?.also { setByName(it, "vits", vitsFileCfg) }
                    ?: error("无法创建 OfflineTtsModelConfig（文件模式）")

            val ttsFileCfg =
                newNoArg(clsOfflineTtsConfig)?.also {
                    setByName(it, "model", modelFileCfg)
                    setByName(it, "provider", "cpu")
                    setByName(it, "numThreads", 2)
                    setByName(it, "debugPath", "")
                } ?: newByExactCtorOrNull(
                    clsOfflineTtsConfig,
                    arrayOf(
                        clsOfflineTtsModelConfig,
                        String::class.java, String::class.java,
                        java.lang.Integer.TYPE, java.lang.Float.TYPE
                    ),
                    arrayOf(modelFileCfg, "cpu", "", 2, 1.0f)
                ) ?: error("无法创建 OfflineTtsConfig（文件模式）")

            // ---------- 优先使用“文件模式”：AssetManager 传 null ----------
            val ctor = clsOfflineTts.getDeclaredConstructor(AssetManager::class.java, clsOfflineTtsConfig)
            try {
                ttsObj = ctor.newInstance(null, ttsFileCfg) // ★ 关键：assetManager=null → 走绝对路径读取
                sampleRate = (findZeroArgMethod(clsOfflineTts, "getSampleRate")?.invoke(ttsObj) as? Int) ?: 22050
                ready = true
                Log.i(TAG, "✅ Sherpa-ONNX TTS 文件模式初始化成功，sr=$sampleRate")
                return true
            } catch (e: Throwable) {
                Log.w(TAG, "文件模式构造失败，切换资产模式重试：${e.message}")
            }

            // ---------- 资产模式兜底：路径改为 assets 相对路径 + 传入非空 AssetManager ----------
            val assetModel = "$assetDir/${modelOnnx.name}"
            val assetTokens = "$assetDir/${tokensTxt.name}"
            val assetLexicon = lexiconTxt?.let { "$assetDir/${it.name}" } ?: ""

            val vitsAssetCfg = newNoArg(clsVits)?.also {
                setByName(it, "model", assetModel)
                setByName(it, "tokens", assetTokens)
                setByName(it, "lexicon", assetLexicon)
                setByName(it, "dict", "")
                setByName(it, "data", "")
                setByName(it, "noiseScale", 0.667f)
                setByName(it, "lengthScale", 1.0f)
                setByName(it, "noiseScaleW", 0.8f)
            } ?: newByExactCtorOrNull(
                clsVits,
                arrayOf(
                    String::class.java, String::class.java, String::class.java,
                    String::class.java, String::class.java,
                    java.lang.Float.TYPE, java.lang.Float.TYPE, java.lang.Float.TYPE
                ),
                arrayOf(assetModel, assetTokens, assetLexicon, "", "", 0.667f, 1.0f, 0.8f)
            ) ?: error("无法实例化 OfflineTtsVitsModelConfig（资产模式）")

            val modelAssetCfg =
                newBySingleArgCtorOrNull(clsOfflineTtsModelConfig, vitsAssetCfg)
                    ?: newNoArg(clsOfflineTtsModelConfig)?.also { setByName(it, "vits", vitsAssetCfg) }
                    ?: error("无法创建 OfflineTtsModelConfig（资产模式）")

            val ttsAssetCfg =
                newNoArg(clsOfflineTtsConfig)?.also {
                    setByName(it, "model", modelAssetCfg)
                    setByName(it, "provider", "cpu")
                    setByName(it, "numThreads", 2)
                    setByName(it, "debugPath", "")
                } ?: newByExactCtorOrNull(
                    clsOfflineTtsConfig,
                    arrayOf(
                        clsOfflineTtsModelConfig,
                        String::class.java, String::class.java,
                        java.lang.Integer.TYPE, java.lang.Float.TYPE
                    ),
                    arrayOf(modelAssetCfg, "cpu", "", 2, 1.0f)
                ) ?: error("无法创建 OfflineTtsConfig（资产模式）")

            ttsObj = ctor.newInstance(context.assets, ttsAssetCfg)
            sampleRate = (findZeroArgMethod(clsOfflineTts, "getSampleRate")?.invoke(ttsObj) as? Int) ?: 22050
            ready = true
            Log.i(TAG, "✅ Sherpa-ONNX TTS 资产模式初始化成功，sr=$sampleRate")
            return true

        } catch (t: Throwable) {
            Log.e(TAG, "❌ 初始化失败: ${t.message}", t)
            ready = false
            return false
        }
    }

    fun speak(text: String): Boolean {
        if (!ready || ttsObj == null) return false
        return try {
            val cls = ttsObj!!::class.java
            val sigs: Array<Array<Class<*>>> = arrayOf(
                arrayOf(String::class.java),
                arrayOf(String::class.java, java.lang.Integer.TYPE),
                arrayOf(String::class.java, java.lang.Integer.TYPE, java.lang.Float.TYPE)
            )
            val argsList: Array<Array<Any>> = arrayOf(
                arrayOf(text),
                arrayOf(text, 0),
                arrayOf(text, 0, 1.0f)
            )
            var wave: Wave? = null
            loop@ for (i in sigs.indices) {
                val m = findMethodByNameAndParams(cls, "generate", sigs[i])
                if (m != null) {
                    val r = try { m.invoke(ttsObj, *argsList[i]) } catch (_: Throwable) { null }
                    wave = extractWave(r)
                    if (wave != null) break@loop
                }
            }
            if (wave == null) {
                Log.w(TAG, "生成失败：未找到可用的 generate 重载或返回为空")
                return false
            }
            playPcmFloat(wave.samples, wave.sampleRate ?: sampleRate)
            true
        } catch (t: Throwable) {
            Log.e(TAG, "合成失败: ${t.message}", t)
            false
        }
    }

    override fun close() {
        stopPlayer()
        try {
            ttsObj?.let { obj ->
                val cls = obj::class.java
                findZeroArgMethod(cls, "close")?.invoke(obj)
                    ?: findZeroArgMethod(cls, "release")?.invoke(obj)
            }
        } catch (_: Throwable) {}
        ttsObj = null
        ready = false
    }

    // ============ 反射辅助 ============

    private fun tryLoadSherpaJni() {
        val candidates = arrayOf("sherpa-onnx-jni", "onnxruntime4j_jni", "onnxruntime")
        for (n in candidates) {
            try { System.loadLibrary(n); Log.i(TAG, "✅ JNI 库加载成功"); return } catch (_: Throwable) {}
        }
        Log.i(TAG, "ℹ️ JNI 库未显式加载（通常由 AAR 自动完成）")
    }

    private fun logConstructors(tag: String, cls: Class<*>) {
        try {
            Log.i(TAG, "——— $tag constructors of ${cls.name} ———")
            for (c in cls.constructors) {
                val ps = c.parameterTypes.joinToString(", ") { it.simpleName }
                Log.i(TAG, "  <init>($ps)")
            }
        } catch (_: Throwable) {}
    }

    private fun newNoArg(cls: Class<*>) =
        try { cls.getDeclaredConstructor().newInstance() } catch (_: Throwable) { null }

    private fun newByExactCtorOrNull(
        cls: Class<*>,
        paramTypes: Array<Class<*>>,
        args: Array<Any?>
    ) = try {
        cls.getDeclaredConstructor(*paramTypes).newInstance(*args)
    } catch (_: Throwable) { null }

    private fun newBySingleArgCtorOrNull(cls: Class<*>, arg: Any): Any? {
        for (ctor in cls.constructors) {
            val p = ctor.parameterTypes
            if (p.size == 1 && p[0].isAssignableFrom(arg.javaClass)) {
                return try { ctor.newInstance(arg) } catch (_: Throwable) { null }
            }
        }
        return null
    }

    private fun setByName(holder: Any, key: String, value: Any) {
        val cls = holder::class.java
        // setter
        val setters = cls.methods.filter { m ->
            m.parameterTypes.size == 1 && (
                    m.name.equals("set${key}", true) ||
                            m.name.equals("with${key}", true) ||
                            (m.name.startsWith("set", true) && m.name.contains(key, true))
                    )
        }
        for (m in setters) {
            val pt = m.parameterTypes[0]
            val v = coerceArg(pt, value) ?: continue
            try { m.invoke(holder, v); return } catch (_: Throwable) { }
        }
        // 字段
        val fields = cls.fields.filter { f -> f.name.equals(key, true) || f.name.contains(key, true) }
        for (f in fields) {
            val v = coerceArg(f.type, value) ?: continue
            try { f.isAccessible = true; f.set(holder, v); return } catch (_: Throwable) { }
        }
    }

    private fun coerceArg(expect: Class<*>, v: Any): Any? = when {
        expect.isAssignableFrom(v.javaClass) -> v
        expect == String::class.java && v is File -> v.absolutePath
        expect == java.lang.Integer.TYPE || expect == Integer::class.java -> when (v) {
            is Int -> v
            is Number -> v.toInt()
            is String -> v.toIntOrNull()
            else -> null
        }
        expect == java.lang.Float.TYPE || expect == java.lang.Float::class.java -> when (v) {
            is Float -> v
            is Number -> v.toFloat()
            is String -> v.toFloatOrNull()
            else -> null
        }
        else -> null
    }

    private fun findZeroArgMethod(cls: Class<*>, name: String) =
        cls.methods.firstOrNull { it.name.equals(name, true) && it.parameterTypes.isEmpty() }

    private fun toBoxed(c: Class<*>): Class<*> = when (c) {
        java.lang.Integer.TYPE -> java.lang.Integer::class.java
        java.lang.Float.TYPE -> java.lang.Float::class.java
        java.lang.Long.TYPE -> java.lang.Long::class.java
        java.lang.Double.TYPE -> java.lang.Double::class.java
        java.lang.Boolean.TYPE -> java.lang.Boolean::class.java
        java.lang.Short.TYPE -> java.lang.Short::class.java
        java.lang.Byte.TYPE -> java.lang.Byte::class.java
        java.lang.Character.TYPE -> java.lang.Character::class.java
        else -> c
    }

    private fun paramMatches(a: Class<*>, b: Class<*>): Boolean {
        if (a == b) return true
        if (toBoxed(a) == toBoxed(b)) return true
        return a.isAssignableFrom(b)
    }

    private fun findMethodByNameAndParams(
        cls: Class<*>,
        name: String,
        sig: Array<out Class<*>>
    ): java.lang.reflect.Method? {
        methodLoop@ for (m in cls.methods) {
            if (!m.name.equals(name, true)) continue
            val p = m.parameterTypes
            if (p.size != sig.size) continue
            for (i in p.indices) {
                if (!paramMatches(p[i], sig[i])) continue@methodLoop
            }
            return m
        }
        return null
    }

    // ============ 结果解析 & 播放 ============

    private data class Wave(val samples: FloatArray, val sampleRate: Int?)

    private fun extractWave(ret: Any?): Wave? {
        if (ret == null) return null
        if (ret is FloatArray) return Wave(ret, null)

        val cls = ret::class.java
        val getSamples = findZeroArgMethod(cls, "getSamples")
        val getSr = cls.methods.firstOrNull { it.name.contains("SampleRate", true) && it.parameterTypes.isEmpty() }
        val samplesAny = try { getSamples?.invoke(ret) } catch (_: Throwable) { null }
        val srAny = try { getSr?.invoke(ret) } catch (_: Throwable) { null }

        val arr: FloatArray? = when (samplesAny) {
            is FloatArray -> samplesAny
            is Array<*> -> flattenFloatArray(samplesAny)
            else -> null
        }
        return arr?.let { Wave(it, srAny as? Int) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun flattenFloatArray(any: Any?): FloatArray? {
        if (any == null) return null
        val cls = any::class.java
        return when {
            cls.isArray && cls.componentType == java.lang.Float.TYPE -> any as FloatArray
            cls.isArray -> {
                val n = java.lang.reflect.Array.getLength(any)
                val list = ArrayList<Float>(n)
                for (i in 0 until n) {
                    val sub = flattenFloatArray(java.lang.reflect.Array.get(any, i)) ?: continue
                    list.addAll(sub.asList())
                }
                list.toFloatArray()
            }
            else -> null
        }
    }

    private fun playPcmFloat(wave: FloatArray, sr: Int) {
        stopPlayer()

        val peak = wave.maxOfOrNull { abs(it) } ?: 0f
        val gain = if (peak > 0f) (0.9f / peak).coerceAtMost(4f) else 1f
        val shorts = ShortArray(wave.size) { i ->
            val v = (wave[i] * gain).coerceIn(-1f, 1f) * Short.MAX_VALUE
            v.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }

        val minBuf = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val format = AudioFormat.Builder()
            .setSampleRate(sr)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()

        val t = AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(format)
            .setTransferMode(if (shorts.size <= minBuf) AudioTrack.MODE_STATIC else AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(minBuf.coerceAtLeast(shorts.size * 2))
            .build()
        track = t

        if (shorts.size <= minBuf) {
            val wrote = if (Build.VERSION.SDK_INT >= 23)
                t.write(shorts, 0, shorts.size, AudioTrack.WRITE_BLOCKING)
            else
                t.write(shorts, 0, shorts.size)
            Log.i(TAG, "AudioTrack STATIC wrote=$wrote/${shorts.size}")
            t.play()
        } else {
            t.play()
            var off = 0
            val chunk = 4096
            while (off < shorts.size) {
                val len = min(chunk, shorts.size - off)
                val wrote = if (Build.VERSION.SDK_INT >= 23)
                    t.write(shorts, off, len, AudioTrack.WRITE_BLOCKING)
                else
                    t.write(shorts, off, len)
                if (wrote <= 0) break
                off += wrote
            }
        }
    }

    private fun stopPlayer() {
        try { track?.stop() } catch (_: Throwable) {}
        try { track?.release() } catch (_: Throwable) {}
        track = null
    }

    // ============ 资源 ============

    private fun copyAssetDirRecursively(assetDir: String, dstDir: File) {
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
                copyAssetDirRecursively(path, File(dstDir, name))
            }
        }
    }

    private fun findFirstFile(dir: File, namesOrExt: List<String>): File? {
        val files = dir.listFiles() ?: return null
        for (n in namesOrExt) files.firstOrNull { it.name.equals(n, ignoreCase = true) }?.let { return it }
        for (n in namesOrExt) if (n.startsWith(".")) files.firstOrNull { it.name.lowercase().endsWith(n.lowercase()) }?.let { return it }
        return null
    }
}
