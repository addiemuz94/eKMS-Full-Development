package com.ekms.terminal.data

import android.content.Context
import org.json.JSONObject

/**
 * Local-only "who has which key right now" record — the minimum needed to make Return Flow
 * functionally complete today. This is a deliberate stand-in, not Phase 5: it holds only
 * keyId/userId/terminalId/takenAtEpochMillis, has no overdue/emergency/extension fields, and is
 * never synced to the backend. At most one open record per key (keyed by keyId) — a return
 * closes (removes) it. Phase 5's job is to replace this with the real, backend-synced version,
 * not to extend this class in place.
 */
class TerminalCheckoutStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun open(record: TerminalCheckoutRecord) {
        preferences.edit().putString(recordKey(record.keyId), record.toJson().toString()).apply()
    }

    fun find(keyId: String): TerminalCheckoutRecord? {
        val serialized = preferences.getString(recordKey(keyId), null) ?: return null
        return runCatching { TerminalCheckoutRecord.fromJson(JSONObject(serialized)) }.getOrNull()
    }

    /** Removes the open record for [keyId], if any, and returns what was closed out. */
    fun close(keyId: String): TerminalCheckoutRecord? {
        val existing = find(keyId) ?: return null
        preferences.edit().remove(recordKey(keyId)).apply()
        return existing
    }

    private fun recordKey(keyId: String) = "checkout_$keyId"

    private companion object {
        const val PREFERENCES_NAME = "ekms_terminal_checkouts"
    }
}

data class TerminalCheckoutRecord(
    val keyId: String,
    val userId: String?,
    val terminalId: String,
    val takenAtEpochMillis: Long,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("keyId", keyId)
        .put("userId", userId ?: JSONObject.NULL)
        .put("terminalId", terminalId)
        .put("takenAtEpochMillis", takenAtEpochMillis)

    companion object {
        fun fromJson(json: JSONObject) = TerminalCheckoutRecord(
            keyId = json.getString("keyId"),
            userId = if (json.isNull("userId")) null else json.getString("userId"),
            terminalId = json.getString("terminalId"),
            takenAtEpochMillis = json.getLong("takenAtEpochMillis"),
        )
    }
}
