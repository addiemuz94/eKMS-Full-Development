package com.ekms.shared.policy

import com.ekms.shared.domain.RecordType
import com.ekms.shared.domain.UserRole
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RecycleBinPolicyTest {
    @Test
    fun `only super admin may access or purge recycle bin`() {
        assertTrue(RecycleBinPolicy.mayAccess(UserRole.SUPER_ADMIN))
        assertTrue(RecycleBinPolicy.mayPurge(UserRole.SUPER_ADMIN))
        assertFalse(RecycleBinPolicy.mayAccess(UserRole.TECHNICIAN))
        assertFalse(RecycleBinPolicy.mayPurge(UserRole.VENDOR))
    }

    @Test
    fun `entry expires after configured retention period`() {
        val deletedAt = 1_000L
        val entry = RecycleBinEntry(
            id = "bin-1",
            recordType = RecordType.KEY,
            recordId = "key-1",
            recordLabel = "Forklift Key",
            deletedByUserId = "admin-1",
            deletedAtEpochMillis = deletedAt,
            expiresAtEpochMillis = RecycleBinPolicy.expiresAt(deletedAt),
            restorePayloadVersion = 4,
        )

        assertFalse(RecycleBinPolicy.isExpired(entry, entry.expiresAtEpochMillis - 1))
        assertTrue(RecycleBinPolicy.isExpired(entry, entry.expiresAtEpochMillis))
    }
}
