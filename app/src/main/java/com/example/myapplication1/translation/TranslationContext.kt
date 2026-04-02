package com.example.myapplication1.translation

/**
 * Latency vs quality trade-off for translation requests.
 */
enum class LatencyMode {
    /** Fast path only — lowest latency, no quality upgrade. */
    REALTIME,
    /** Fast path + background quality with timeout — balanced. */
    BALANCED,
    /** Fast path + background quality without timeout — best quality. */
    QUALITY
}

/**
 * Context attached to a translation request.
 * Engines that support context will use it to improve translation quality;
 * engines that don't will silently ignore it (via the default [TranslationEngine.translate]).
 */
data class TranslationContext(
    /** Supplementary background for comprehension only — not translated. */
    val background: String = "",
    /** Explicit domain hint (e.g. "medical", "meeting"). Empty or "auto" for auto-detect. */
    val domainHint: String = "",
    /** Glossary terms (EN→ZH) injected from the active domain glossary. */
    val glossaryTerms: Map<String, String> = emptyMap(),
    /** Desired latency vs quality trade-off. */
    val latencyMode: LatencyMode = LatencyMode.REALTIME
)

/**
 * Metadata returned alongside a translation result for observability.
 */
data class TranslationMeta(
    /** Which path produced this result: "fast", "quality", or "fallback". */
    val route: String = "fast",
    /** Domain selected by auto-detection or explicit hint. */
    val selectedDomain: String = "",
    /** Glossary ID used, if any. */
    val selectedGlossary: String = "",
    /** Whether background context was used. */
    val usedBackground: Boolean = false,
    /** Reason for fallback, if any. */
    val fallbackReason: String = ""
)
