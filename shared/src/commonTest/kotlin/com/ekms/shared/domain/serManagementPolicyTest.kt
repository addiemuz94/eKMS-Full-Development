package com.ekms.shared.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class UserManagementPolicyTest {
    private val knownSiteIds = setOf("site_hq", "site_port")

    @Test
    fun `technician requires at least one site`() {
        val result = UserManagementPolicy.validateDraft(
            draft = UserDraft(
                displayName = "Technician Example",
                email = "tech@example.com",
                role = UserRole.TECHNICIAN,
                assignedSiteIds = emptySet(),
            ),
            knownSiteIds = knownSiteIds,
        )

        assertNotNull(result)
    }

    @Test
    fun `user lifecycle supports disable recycle restore and purge`() {
        val draft = UserDraft(
            displayName = "Vendor Example",
            email = "VENDOR@EXAMPLE.COM",
            role = UserRole.VENDOR,
            assignedSiteIds = setOf("site_port"),
        )
        val created = UserManagementPolicy.createUser(
            id = "user-1",
            draft = draft,
            nowEpochMillis = 100L,
        )
        val disabled = UserManagementPolicy.setAccountStatus(
            existing = created,
            status = AccountStatus.DISABLED,
            nowEpochMillis = 110L,
        )
        val deleted = UserManagementPolicy.moveToRecycleBin(
            existing = disabled,
            actorUserId = "admin-1",
            nowEpochMillis = 120L,
        )
        val restored = UserManagementPolicy.restoreFromRecycleBin(
            existing = deleted,
            nowEpochMillis = 130L,
        )
        val purged = UserManagementPolicy.permanentlyPurge(
            existing = restored,
            nowEpochMillis = 140L,
        )

        assertEquals("vendor@example.com", created.email)
        assertEquals(AccountStatus.DISABLED, disabled.accountStatus)
        assertEquals(RecordLifecycle.RECYCLE_BIN, deleted.lifecycle.state)
        assertEquals("admin-1", deleted.lifecycle.deletedByUserId)
        assertEquals(RecordLifecycle.ACTIVE, restored.lifecycle.state)
        assertNull(restored.lifecycle.deletedAtEpochMillis)
        assertEquals(RecordLifecycle.PURGED, purged.lifecycle.state)
    }
}
