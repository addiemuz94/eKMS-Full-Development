package com.ekms.terminal.data

import android.content.Context
import android.content.SharedPreferences
import com.ekms.shared.domain.RecordType
import com.ekms.shared.sync.OfflineChange
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Revision-aware offline outbox. Local edits enqueue [OfflineChange] rows that
 * [TerminalSyncCoordinator] pushes to `/v1/terminal/sync/push`.
 */
class TerminalSyncOutbox(context: Context) {

    private val preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun localRevision(): Long = preferences.getLong(KEY_LOCAL_REVISION, 1L)

    @Synchronized
    fun lastSuccessfulSyncEpochMillis(): Long? {
        val value = preferences.getLong(KEY_LAST_SYNC, -1L)
        return value.takeIf { it >= 0L }
    }

    @Synchronized
    fun markSynced(serverRevision: Long, atEpochMillis: Long) {
        preferences.edit()
            .putLong(KEY_LOCAL_REVISION, serverRevision.coerceAtLeast(localRevision()))
            .putLong(KEY_LAST_SYNC, atEpochMillis)
            .apply()
    }

    @Synchronized
    fun enqueue(
        entityType: RecordType,
        entityId: String,
        submittedByUserId: String,
        payloadJson: String,
    ): OfflineChange {
        val change = OfflineChange(
            operationId = UUID.randomUUID().toString(),
            entityType = entityType,
            entityId = entityId,
            baseRevision = localRevision(),
            submittedAtEpochMillis = System.currentTimeMillis(),
            submittedByUserId = submittedByUserId,
            payloadJson = payloadJson,
        )
        val pending = pending().toMutableList()
        pending.add(change)
        save(pending)
        preferences.edit().putLong(KEY_LOCAL_REVISION, localRevision() + 1).apply()
        return change
    }

    @Synchronized
    fun pending(): List<OfflineChange> {
        val raw = preferences.getString(KEY_OUTBOX, "[]") ?: "[]"
        val array = JSONArray(raw)
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                add(
                    OfflineChange(
                        operationId = item.getString("operationId"),
                        entityType = RecordType.valueOf(item.getString("entityType")),
                        entityId = item.getString("entityId"),
                        baseRevision = item.getLong("baseRevision"),
                        submittedAtEpochMillis = item.getLong("submittedAtEpochMillis"),
                        submittedByUserId = item.getString("submittedByUserId"),
                        payloadJson = item.getString("payloadJson"),
                    ),
                )
            }
        }
    }

    @Synchronized
    fun removeAccepted(operationIds: Collection<String>) {
        if (operationIds.isEmpty()) return
        val remaining = pending().filterNot { it.operationId in operationIds }
        save(remaining)
    }

    private fun save(changes: List<OfflineChange>) {
        val array = JSONArray()
        changes.forEach { change ->
            array.put(
                JSONObject()
                    .put("operationId", change.operationId)
                    .put("entityType", change.entityType.name)
                    .put("entityId", change.entityId)
                    .put("baseRevision", change.baseRevision)
                    .put("submittedAtEpochMillis", change.submittedAtEpochMillis)
                    .put("submittedByUserId", change.submittedByUserId)
                    .put("payloadJson", change.payloadJson),
            )
        }
        preferences.edit().putString(KEY_OUTBOX, array.toString()).apply()
    }

    companion object {
        private const val PREFS_NAME = "ekms_terminal_sync_outbox"
        private const val KEY_OUTBOX = "outbox"
        private const val KEY_LOCAL_REVISION = "local_revision"
        private const val KEY_LAST_SYNC = "last_successful_sync"
    }
}
