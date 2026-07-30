package com.ekms.terminal.data

import android.content.Context

/**
 * Tracks a fact deliberately separate from `managedKeyFobStore`'s "is this key's UID known
 * locally" (EncryptedUidEnrollmentStore's `enrollmentFor() != null`): whether a human has
 * actually completed the guided attach flow for this key. The two were previously conflated —
 * the background auto-scan silently makes a UID known with no human action at all, which was
 * being misread by KeyAttachmentScreen as "fully attached" (RED). This tracker is the second,
 * separate fact: only KeyAttachmentScreen's own guided attach-flow completion (blue-node tap, or
 * the reused sequence after new-key registration) ever calls [markAttached].
 *
 * Deliberately its own tiny SharedPreferences-backed class rather than extending
 * EncryptedUidEnrollmentStore or KeySlotAssignmentTracker — a different concern (human action
 * history) from either of those.
 */
class PhysicalAttachmentTracker(context: Context) {
    private val preferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isAttached(managedKeyId: String): Boolean = preferences.getBoolean(key(managedKeyId), false)

    fun markAttached(managedKeyId: String) {
        preferences.edit().putBoolean(key(managedKeyId), true).apply()
    }

    /** Called when a node's assignment moves to a different key (mirrors
     * KeySlotAssignmentTracker's own stale-entry cleanup) — the old key's attachment history is
     * meaningless once it no longer occupies any node. */
    fun clear(managedKeyId: String) {
        preferences.edit().remove(key(managedKeyId)).apply()
    }

    private fun key(managedKeyId: String) = "key_$managedKeyId"

    private companion object {
        const val PREFS_NAME = "ekms_physical_attachment_tracker"
    }
}
