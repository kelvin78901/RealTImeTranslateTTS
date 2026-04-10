package com.example.myapplication1

import android.content.Context
import android.os.Build

/**
 * Unified ONNX Runtime execution provider selection.
 *
 * The bundled libonnxruntime.so (via sherpa-onnx.aar) and
 * onnxruntime-android:1.17.1 both include NNAPI and XNNPACK execution
 * providers. This helper lets every Sherpa / ONNX component share a single
 * user-controllable preference: "cpu" (default), "nnapi", or "xnnpack".
 *
 * Default is "cpu" — behaviour is unchanged unless the user opts in.
 */
object AccelerationConfig {

    const val PREFS_NAME = "vri_settings"
    const val KEY = "ort_provider"

    const val CPU = "cpu"
    const val NNAPI = "nnapi"
    const val XNNPACK = "xnnpack"

    /**
     * Read the currently selected provider. Safe to call from any thread.
     * Falls back to "cpu" if the selection is unknown or not supported
     * on the current SDK (NNAPI requires API 27+).
     *
     * This is the *effective* provider — if the user picked NNAPI on an
     * older device, this returns "cpu" so UI state stays consistent with
     * what's actually loaded.
     */
    fun provider(context: Context): String {
        val raw = context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY, CPU) ?: CPU
        return when (raw) {
            NNAPI -> if (Build.VERSION.SDK_INT >= 27) NNAPI else CPU
            XNNPACK -> XNNPACK
            else -> CPU
        }
    }

    /**
     * Ordered provider-fallback list: the caller should iterate and stop on
     * the first successful initialization.  Non-CPU choices always end with
     * CPU as the last resort so one bad device driver can't break a feature.
     */
    fun providerChain(context: Context): List<String> {
        val preferred = provider(context)
        return if (preferred == CPU) listOf(CPU) else listOf(preferred, CPU)
    }

    /** Whether NNAPI should be attached to a Java OrtSession.SessionOptions. */
    fun useNnapiForOrt(context: Context): Boolean = provider(context) == NNAPI
}
