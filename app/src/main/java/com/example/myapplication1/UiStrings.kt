package com.example.myapplication1

import android.content.Context
import org.json.JSONObject

/**
 * UI localization system — loads strings from JSON files in assets/strings/.
 *
 * Supported languages: zh (Chinese), en (English).
 * String files: assets/strings/zh.json, assets/strings/en.json
 *
 * Usage: S("auto_speak") returns the localized string for the current UI language.
 * Add new strings by editing the JSON files — no code changes needed.
 */
object UiStrings {
    var lang: String = "zh"
    private var strings: Map<String, String> = emptyMap()
    private val cache = mutableMapOf<String, Map<String, String>>()

    /** Load strings for the given language. Call once on app start and when language changes. */
    fun load(context: Context, language: String = lang) {
        lang = language
        strings = cache.getOrPut(language) {
            try {
                val json = context.assets.open("strings/$language.json")
                    .bufferedReader().use { it.readText() }
                val obj = JSONObject(json)
                val map = mutableMapOf<String, String>()
                obj.keys().forEach { key -> map[key] = obj.getString(key) }
                map
            } catch (_: Throwable) {
                emptyMap()
            }
        }
    }

    /** Get a localized string by key. Returns the key itself if not found. */
    fun get(key: String): String = strings[key] ?: key
}

/** Shorthand for UiStrings.get(key). */
fun S(key: String): String = UiStrings.get(key)
