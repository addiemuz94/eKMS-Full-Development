package com.ekms.terminal.data

import android.content.Context
import android.content.SharedPreferences
import com.ekms.shared.api.TerminalDownloadSnapshot
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Persists the last successful server download/bootstrap snapshot so retrieval
 * and return flows can use real ManagedKey/KeySlot data instead of demo fixtures.
 */
class TerminalServerCache(context: Context) {

    private val preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun load(): TerminalDownloadSnapshot? {
        val raw = preferences.getString(KEY_SNAPSHOT, null) ?: return null
        return runCatching { json.decodeFromString<TerminalDownloadSnapshot>(raw) }.getOrNull()
    }

    fun save(snapshot: TerminalDownloadSnapshot) {
        preferences.edit()
            .putString(KEY_SNAPSHOT, json.encodeToString(snapshot))
            .putLong(KEY_SAVED_AT, System.currentTimeMillis())
            .apply()
    }

    fun clear() {
        preferences.edit().remove(KEY_SNAPSHOT).remove(KEY_SAVED_AT).apply()
    }

    companion object {
        private const val PREFS_NAME = "ekms_terminal_server_cache"
        private const val KEY_SNAPSHOT = "snapshot_json"
        private const val KEY_SAVED_AT = "saved_at"
    }
}
