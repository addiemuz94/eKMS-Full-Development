package com.ekms.terminal.data

import android.content.Context
import com.ekms.shared.api.KeyCheckoutDto
import org.json.JSONObject

/**
 * Local-only "who has which key right now" record — the minimum needed to make Return Flow
 * functionally complete, kept as a local cache/mirror alongside Phase 5's real backend
 * `key_checkouts` sync (see `TerminalAdminApp.handleTakeFlowOutcome`/`handleReturnFlowOutcome`),
 * not replaced by it — the terminal must keep working from this store even when the backend
 * create/close-out call fails or the device is offline. The `backend*` fields on
 * [TerminalCheckoutRecord] are nullable and only populated once (if ever) the backend
 * `POST /key-checkouts` call for this take succeeds, mirroring the server row's own values so the
 * later return-time `PATCH /key-checkouts/:id` close-out call can pass them through unchanged
 * (that route requires the full field set, not just status) rather than the terminal having to
 * re-fetch the row — a `GET /key-checkouts/:id` call is deliberately not in the TERMINAL_DEVICE
 * allowlist, since the terminal only ever needs to close out checkouts it created itself. A null
 * [TerminalCheckoutRecord.backendCheckoutId] at return time means the create sync never
 * succeeded, so the close-out call is skipped (logged as another sync failure) rather than
 * attempted against a row that doesn't exist.
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

    /**
     * Mirrors the backend row's id/revision/due-date fields once the Phase 5 create-sync call
     * succeeds, for a record that's still open. No-ops if the record was already closed/returned
     * in the meantime (e.g. a very fast take-then-return) — nothing left to attach it to.
     */
    fun attachBackendInfo(keyId: String, created: KeyCheckoutDto) {
        val existing = find(keyId) ?: return
        open(
            existing.copy(
                backendCheckoutId = created.id,
                backendRevision = created.revision,
                backendDueAtEpochMillis = created.dueAtEpochMillis,
                backendIsEmergency = created.isEmergency,
                backendEmergencyWindowEndsAtEpochMillis = created.emergencyWindowEndsAtEpochMillis,
            ),
        )
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
    val backendCheckoutId: String? = null,
    val backendRevision: Long? = null,
    val backendDueAtEpochMillis: Long? = null,
    val backendIsEmergency: Boolean = false,
    val backendEmergencyWindowEndsAtEpochMillis: Long? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("keyId", keyId)
        .put("userId", userId ?: JSONObject.NULL)
        .put("terminalId", terminalId)
        .put("takenAtEpochMillis", takenAtEpochMillis)
        .put("backendCheckoutId", backendCheckoutId ?: JSONObject.NULL)
        .put("backendRevision", backendRevision ?: JSONObject.NULL)
        .put("backendDueAtEpochMillis", backendDueAtEpochMillis ?: JSONObject.NULL)
        .put("backendIsEmergency", backendIsEmergency)
        .put("backendEmergencyWindowEndsAtEpochMillis", backendEmergencyWindowEndsAtEpochMillis ?: JSONObject.NULL)

    companion object {
        fun fromJson(json: JSONObject) = TerminalCheckoutRecord(
            keyId = json.getString("keyId"),
            userId = if (json.isNull("userId")) null else json.getString("userId"),
            terminalId = json.getString("terminalId"),
            takenAtEpochMillis = json.getLong("takenAtEpochMillis"),
            backendCheckoutId = json.optNullableString("backendCheckoutId"),
            backendRevision = json.optNullableLong("backendRevision"),
            backendDueAtEpochMillis = json.optNullableLong("backendDueAtEpochMillis"),
            backendIsEmergency = json.optBoolean("backendIsEmergency", false),
            backendEmergencyWindowEndsAtEpochMillis = json.optNullableLong("backendEmergencyWindowEndsAtEpochMillis"),
        )

        // Tolerant of records persisted before these fields existed (json.has(...) == false).
        private fun JSONObject.optNullableString(key: String): String? =
            if (has(key) && !isNull(key)) getString(key) else null

        private fun JSONObject.optNullableLong(key: String): Long? =
            if (has(key) && !isNull(key)) getLong(key) else null
    }
}
