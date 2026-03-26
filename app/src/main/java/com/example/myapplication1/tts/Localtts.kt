package com.example.myapplication1.tts

import android.content.Context
import android.util.Log

class LocalTts(
    private val context: Context,
    private val assetDir: String = "tts/vits_cn"
) {

    companion object { private const val TAG = "LocalTts" }

    fun isReady() = false

    suspend fun init(): Boolean {
        Log.i(TAG, "LocalTts disabled (legacy pipeline removed)")
        return false
    }

    suspend fun speak(text: String, noise: Float? = null, length: Float? = null, noiseW: Float? = null) {
        Log.i(TAG, "LocalTts.speak ignored: legacy pipeline removed")
    }

    fun close() { /* no-op */ }
}
