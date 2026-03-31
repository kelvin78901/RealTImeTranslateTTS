package com.example.myapplication1

import android.util.Log
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Session-based translation history with paragraph grouping,
 * titles, search, and archive support.
 */
class TranslationHistory(private val filesDir: File) {

    data class Entry(val en: String, val zh: String, val timestamp: Long)
    data class Session(
        val id: String,
        val startTime: Long,
        var title: String = "",
        val entries: MutableList<Entry> = mutableListOf()
    ) {
        val enParagraph: String get() = entries.joinToString(" ") { it.en }
        val zhParagraph: String get() = entries.joinToString("") { it.zh }
        /** Auto-generate title from first entry if not set */
        val displayTitle: String get() {
            if (title.isNotBlank()) return title
            val first = entries.firstOrNull()?.en ?: return "空会话"
            return if (first.length > 40) first.take(40) + "…" else first
        }
    }

    private val file = File(filesDir, "translation_history_v2.json")
    private val oldFile = File(filesDir, "translation_history.json")
    private val sessions = mutableListOf<Session>()
    private var currentSessionId: String? = null
    private var saveJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun load(): List<Session> {
        sessions.clear()
        if (!file.exists() && oldFile.exists()) {
            migrateOldFormat()
            return sessions.toList()
        }
        if (!file.exists()) return emptyList()
        try {
            val arr = JSONArray(file.readText())
            for (i in 0 until arr.length()) {
                val sObj = arr.getJSONObject(i)
                val entries = mutableListOf<Entry>()
                val eArr = sObj.getJSONArray("entries")
                for (j in 0 until eArr.length()) {
                    val e = eArr.getJSONObject(j)
                    entries.add(Entry(e.getString("en"), e.getString("zh"), e.getLong("ts")))
                }
                sessions.add(Session(
                    sObj.getString("id"),
                    sObj.getLong("startTime"),
                    sObj.optString("title", ""),
                    entries
                ))
            }
        } catch (e: Throwable) {
            Log.e("History", "Load failed: ${e.message}")
        }
        return sessions.toList()
    }

    private fun migrateOldFormat() {
        try {
            val arr = JSONArray(oldFile.readText())
            if (arr.length() == 0) return
            val entries = mutableListOf<Entry>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                entries.add(Entry(obj.getString("en"), obj.getString("zh"), obj.getLong("ts")))
            }
            val session = Session(UUID.randomUUID().toString(), entries.first().timestamp, "", entries)
            sessions.add(session)
            save()
            oldFile.delete()
        } catch (e: Throwable) {
            Log.e("History", "Migration failed: ${e.message}")
        }
    }

    fun newSession(title: String = ""): String {
        val id = UUID.randomUUID().toString()
        sessions.add(Session(id, System.currentTimeMillis(), title))
        currentSessionId = id
        scheduleSave()
        return id
    }

    fun continueOrNew(): String {
        if (sessions.isNotEmpty()) {
            currentSessionId = sessions.last().id
            return currentSessionId!!
        }
        return newSession()
    }

    fun currentSession(): Session? = sessions.find { it.id == currentSessionId }

    fun allSessions(): List<Session> = sessions.toList()

    fun append(en: String, zh: String) {
        if (currentSessionId == null) newSession()
        val session = sessions.find { it.id == currentSessionId } ?: return
        session.entries.add(Entry(en, zh, System.currentTimeMillis()))
        scheduleSave()
    }

    /** Update the Chinese translation for a specific English entry. */
    fun updateLastZh(en: String, zh: String) {
        val session = sessions.find { it.id == currentSessionId } ?: return
        val idx = session.entries.indexOfLast { it.en == en && it.zh.isBlank() }
        if (idx >= 0) {
            session.entries[idx] = Entry(en, zh, session.entries[idx].timestamp)
            scheduleSave()
        }
    }

    fun renameSession(id: String, newTitle: String) {
        sessions.find { it.id == id }?.title = newTitle
        scheduleSave()
    }

    fun search(query: String): List<Session> {
        if (query.isBlank()) return sessions.toList()
        val q = query.lowercase()
        return sessions.filter { s ->
            s.displayTitle.lowercase().contains(q) ||
            s.entries.any { e -> e.en.lowercase().contains(q) || e.zh.contains(q) }
        }
    }

    fun flush() {
        saveJob?.cancel()
        save()
    }

    fun clearAll() {
        sessions.clear()
        currentSessionId = null
        saveJob?.cancel()
        file.delete()
    }

    fun deleteSession(id: String) {
        sessions.removeAll { it.id == id }
        if (currentSessionId == id) currentSessionId = null
        scheduleSave()
    }

    fun close() {
        flush()
        scope.cancel()
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(2000)
            save()
        }
    }

    private fun save() {
        try {
            val arr = JSONArray()
            for (s in sessions) {
                arr.put(JSONObject().apply {
                    put("id", s.id)
                    put("startTime", s.startTime)
                    put("title", s.title)
                    val eArr = JSONArray()
                    for (e in s.entries) {
                        eArr.put(JSONObject().apply {
                            put("en", e.en)
                            put("zh", e.zh)
                            put("ts", e.timestamp)
                        })
                    }
                    put("entries", eArr)
                })
            }
            file.writeText(arr.toString())
        } catch (e: Throwable) {
            Log.e("History", "Save failed: ${e.message}")
        }
    }
}
