package com.ekms.shared.domain

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.serialization.Serializable

/**
 * Shared Step 4 validation for managed keys, physical cabinet slots and exact
 * access grants. This file is deliberately pure business logic: it never
 * opens a serial port, scans a card, or sends a cabinet command.
 */
@Serializable
data class ManagedTerminalOption(
    val id: String,
    val siteId: String,
    val label: String,
    /** Key-node capacity only. Door node 0 is not a key slot. */
    val configuredSlotCount: Int,
)

@Serializable
data class KeyDraft(
    val displayName: String,
    val siteId: String,
)

@Serializable
data class KeySlotDraft(
    val terminalId: String,
    val nodeAddressText: String,
    /** Blank means the physical slot is registered but currently unassigned. */
    val managedKeyId: String,
)

@Serializable
data class AccessGrantDraft(
    val userId: String,
    val siteId: String,
    val keyIds: Set<String>,
    /** Optional ISO calendar date: YYYY-MM-DD, evaluated from 00:00 UTC. */
    val validFromDateText: String,
    /** Optional ISO calendar date: YYYY-MM-DD, evaluated until 23:59:59.999 UTC. */
    val validUntilDateText: String,
)

@Serializable
enum class KeyAccessField {
    KEY_NAME,
    KEY_SITE,
    SLOT_TERMINAL,
    SLOT_NODE_ADDRESS,
    SLOT_KEY,
    GRANT_USER,
    GRANT_SITE,
    GRANT_KEYS,
    VALID_FROM,
    VALID_UNTIL,
}

@Serializable
data class KeyAccessValidationIssue(
    val field: KeyAccessField,
    val message: String,
)

object KeySlotAccessPolicy {
    const val MIN_KEY_NODE_ADDRESS = 1
    const val DOOR_NODE_ADDRESS = 0

    /** Used only by the Android Terminal protected reader flow; never by Web or Mobile UI. */
    fun normalizeFobUid(value: String): String =
        value.trim().replace(Regex("[:\\s-]"), "").uppercase()

    fun validateKey(
        draft: KeyDraft,
        knownSiteIds: Set<String>,
    ): List<KeyAccessValidationIssue> = buildList {
        if (draft.displayName.trim().length < 2) {
            add(KeyAccessValidationIssue(KeyAccessField.KEY_NAME, "Enter a key name with at least 2 characters."))
        }
        if (draft.siteId !in knownSiteIds) {
            add(KeyAccessValidationIssue(KeyAccessField.KEY_SITE, "Select an active site for this key."))
        }
    }

    fun validateSlot(
        draft: KeySlotDraft,
        terminals: List<ManagedTerminalOption>,
        activeKeys: List<ManagedKey>,
        activeSlots: List<KeySlot>,
        editingSlotId: String? = null,
    ): List<KeyAccessValidationIssue> = buildList {
        val terminal = terminals.firstOrNull { it.id == draft.terminalId }
        if (terminal == null) {
            add(KeyAccessValidationIssue(KeyAccessField.SLOT_TERMINAL, "Select an active terminal."))
        }

        val nodeAddress = draft.nodeAddressText.trim().toIntOrNull()
        if (nodeAddress == null || terminal == null ||
            nodeAddress !in MIN_KEY_NODE_ADDRESS..terminal.configuredSlotCount
        ) {
            val upper = terminal?.configuredSlotCount ?: "the configured terminal capacity"
            add(
                KeyAccessValidationIssue(
                    KeyAccessField.SLOT_NODE_ADDRESS,
                    "Node Address must be a key node from $MIN_KEY_NODE_ADDRESS to $upper. Door node $DOOR_NODE_ADDRESS is not allowed.",
                ),
            )
        } else if (activeSlots.any {
                it.id != editingSlotId && it.terminalId == terminal.id && it.nodeAddress == nodeAddress
            }
        ) {
            add(KeyAccessValidationIssue(KeyAccessField.SLOT_NODE_ADDRESS, "This terminal already has a registered slot at node $nodeAddress."))
        }

        val selectedKeyId = draft.managedKeyId.trim()
        if (selectedKeyId.isNotBlank()) {
            val key = activeKeys.firstOrNull { it.id == selectedKeyId }
            if (key == null) {
                add(KeyAccessValidationIssue(KeyAccessField.SLOT_KEY, "Select an active key or leave the slot unassigned."))
            } else if (terminal != null && key.siteId != terminal.siteId) {
                add(KeyAccessValidationIssue(KeyAccessField.SLOT_KEY, "A key can only be placed in a slot at the same site."))
            } else if (activeSlots.any {
                    it.id != editingSlotId && it.managedKeyId == selectedKeyId
                }
            ) {
                add(KeyAccessValidationIssue(KeyAccessField.SLOT_KEY, "This key is already assigned to another active cabinet slot."))
            }
        }
    }

    fun validateAccessGrant(
        draft: AccessGrantDraft,
        activeUsers: List<AdminUser>,
        knownSiteIds: Set<String>,
        activeKeys: List<ManagedKey>,
    ): List<KeyAccessValidationIssue> = buildList {
        val user = activeUsers.firstOrNull { it.id == draft.userId }
        if (user == null || user.accountStatus != AccountStatus.ACTIVE) {
            add(KeyAccessValidationIssue(KeyAccessField.GRANT_USER, "Select an active user."))
        }
        if (draft.siteId !in knownSiteIds) {
            add(KeyAccessValidationIssue(KeyAccessField.GRANT_SITE, "Select an active site."))
        } else if (user != null && user.role != UserRole.SUPER_ADMIN && draft.siteId !in user.assignedSiteIds) {
            add(
                KeyAccessValidationIssue(
                    KeyAccessField.GRANT_SITE,
                    "This user is not assigned to the selected site.",
                ),
            )
        }

        if (draft.keyIds.isEmpty()) {
            add(KeyAccessValidationIssue(KeyAccessField.GRANT_KEYS, "Select at least one exact key."))
        } else {
            val selectedKeys = activeKeys.filter { it.id in draft.keyIds }
            if (selectedKeys.size != draft.keyIds.size) {
                add(KeyAccessValidationIssue(KeyAccessField.GRANT_KEYS, "One or more selected keys are not active."))
            }
            if (selectedKeys.any { it.siteId != draft.siteId }) {
                add(KeyAccessValidationIssue(KeyAccessField.GRANT_KEYS, "Every selected key must belong to the selected site."))
            }
        }

        val validFrom = parseOptionalIsoDate(draft.validFromDateText)
        val validUntil = parseOptionalIsoDate(draft.validUntilDateText)
        if (draft.validFromDateText.isNotBlank() && validFrom == null) {
            add(KeyAccessValidationIssue(KeyAccessField.VALID_FROM, "Use YYYY-MM-DD for the start date."))
        }
        if (draft.validUntilDateText.isNotBlank() && validUntil == null) {
            add(KeyAccessValidationIssue(KeyAccessField.VALID_UNTIL, "Use YYYY-MM-DD for the expiry date."))
        }
        if (validFrom != null && validUntil != null && validFrom.compareTo(validUntil) > 0) {
            add(KeyAccessValidationIssue(KeyAccessField.VALID_UNTIL, "Expiry date must be on or after the start date."))
        }
    }

    fun validFromEpochMillis(dateText: String): Long? =
        parseOptionalIsoDate(dateText)?.atStartOfDayIn(TimeZone.UTC)?.toEpochMilliseconds()

    fun validUntilEpochMillis(dateText: String): Long? =
        parseOptionalIsoDate(dateText)
            ?.atStartOfDayIn(TimeZone.UTC)
            ?.toEpochMilliseconds()
            ?.plus(86_399_999L)

    fun errorFor(
        issues: List<KeyAccessValidationIssue>,
        field: KeyAccessField,
    ): String? = issues.firstOrNull { it.field == field }?.message

    private fun parseOptionalIsoDate(value: String): LocalDate? {
        val trimmed = value.trim()
        if (trimmed.isBlank()) return null
        return runCatching { LocalDate.parse(trimmed) }.getOrNull()
    }
}

/** Local preview records only. The backend becomes authoritative in the sync step. */
object KeySlotDemoData {
    private const val CREATED_AT = 1_783_000_000_000L

    val terminals = listOf(
        ManagedTerminalOption(
            id = "terminal_hq_demo",
            siteId = "site_hq",
            label = "Head Office Cabinet · Box 1",
            configuredSlotCount = 48,
        ),
        ManagedTerminalOption(
            id = "terminal_port_demo",
            siteId = "site_port",
            label = "Port Operations Cabinet · Box 1",
            configuredSlotCount = 24,
        ),
        ManagedTerminalOption(
            id = "terminal_service_demo",
            siteId = "site_service",
            label = "Service Centre Cabinet · Box 1",
            configuredSlotCount = 24,
        ),
    )

    fun keys(): List<ManagedKey> = listOf(
        ManagedKey(
            id = "key_hq_vehicle_demo",
            siteId = "site_hq",
            displayName = "HQ Service Vehicle",
            lifecycle = activeLifecycle(),
        ),
        ManagedKey(
            id = "key_port_crane_demo",
            siteId = "site_port",
            displayName = "Port Crane Access",
            lifecycle = activeLifecycle(),
        ),
        ManagedKey(
            id = "key_service_store_demo",
            siteId = "site_service",
            displayName = "Service Store Master",
            lifecycle = activeLifecycle(),
        ),
    )

    fun slots(): List<KeySlot> = listOf(
        KeySlot(
            id = "slot_hq_1_demo",
            terminalId = "terminal_hq_demo",
            nodeAddress = 1,
            managedKeyId = "key_hq_vehicle_demo",
            lifecycle = activeLifecycle(),
        ),
        KeySlot(
            id = "slot_port_3_demo",
            terminalId = "terminal_port_demo",
            nodeAddress = 3,
            managedKeyId = "key_port_crane_demo",
            lifecycle = activeLifecycle(),
        ),
    )

    fun accessGrants(): List<AccessGrant> = listOf(
        AccessGrant(
            id = "grant_technician_port_demo",
            userId = "usr_technician_demo",
            siteId = "site_port",
            keyIds = setOf("key_port_crane_demo"),
            lifecycle = activeLifecycle(),
        ),
    )

    private fun activeLifecycle() = LifecycleMetadata(
        createdAtEpochMillis = CREATED_AT,
        updatedAtEpochMillis = CREATED_AT,
    )
}