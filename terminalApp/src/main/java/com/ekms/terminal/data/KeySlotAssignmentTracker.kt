package com.ekms.terminal.data

import android.content.Context

/**
 * Tiny local-only record of which ManagedKey id a cabinet node's captured fob UID was last
 * associated with. Lets the Key Attachment background sweep (Part 3) detect "this node was
 * reassigned to a different key since the last scan" and revoke the stale
 * EncryptedUidEnrollmentStore entry before re-capturing — without this, a reassigned node whose
 * physical fob hasn't been swapped yet would collide with that store's own AlreadyAssigned check
 * (the same UID still "owned" by the old, now-unlinked key id) and never capture for the new key.
 *
 * Deliberately separate from EncryptedUidEnrollmentStore itself (kept untouched, reused as-is
 * for the actual encrypted UID storage) — this is bookkeeping about node<->key identity over
 * time, not credential material, so a plain SharedPreferences string map is enough.
 */
class KeySlotAssignmentTracker(context: Context) {
    private val preferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun lastKnownManagedKeyId(nodeAddress: Int): String? = preferences.getString(key(nodeAddress), null)

    fun recordManagedKeyId(nodeAddress: Int, managedKeyId: String) {
        preferences.edit().putString(key(nodeAddress), managedKeyId).apply()
    }

    private fun key(nodeAddress: Int) = "node_$nodeAddress"

    private companion object {
        const val PREFS_NAME = "ekms_key_slot_assignment_tracker"
    }
}
