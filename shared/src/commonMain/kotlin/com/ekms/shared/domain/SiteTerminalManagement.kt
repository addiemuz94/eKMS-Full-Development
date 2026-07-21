package com.ekms.shared.domain

import kotlinx.serialization.Serializable

/**
 * Cross-platform validation for Super Admin Site and Terminal management.
 *
 * This file deliberately contains no serial-port access. Only terminalApp's
 * Android-specific hardware layer may open the configured cabinet port.
 */
@Serializable
data class SiteEditorInput(
    val name: String,
    val address: String,
)

@Serializable
data class TerminalEditorInput(
    val name: String,
    val siteId: String,
    val boxAddressText: String,
    val serialNumber: String,
    val slotCountText: String,
    val cabinetSerialPort: String,
    val cabinetBaudRateText: String,
)

@Serializable
enum class SiteTerminalField {
    SITE_NAME,
    TERMINAL_NAME,
    TERMINAL_SITE,
    BOX_ADDRESS,
    SLOT_COUNT,
    CABINET_SERIAL_PORT,
    CABINET_BAUD_RATE,
}

@Serializable
data class SiteTerminalValidationIssue(
    val field: SiteTerminalField,
    val message: String,
)

object SiteTerminalManagementPolicy {
    const val MIN_BOX_ADDRESS = 1
    const val MAX_BOX_ADDRESS = 255
    const val MIN_NODE_ADDRESS = 1
    const val MAX_NODE_ADDRESS = 255
    const val DEFAULT_CABINET_SERIAL_PORT = "/dev/ttyS1"
    const val DEFAULT_CABINET_BAUD_RATE = 19_200

    fun validateSite(input: SiteEditorInput): List<SiteTerminalValidationIssue> = buildList {
        if (input.name.trim().length < 2) {
            add(
                SiteTerminalValidationIssue(
                    SiteTerminalField.SITE_NAME,
                    "Enter a site name with at least 2 characters.",
                ),
            )
        }
    }

    fun validateTerminal(input: TerminalEditorInput): List<SiteTerminalValidationIssue> = buildList {
        if (input.name.trim().length < 2) {
            add(
                SiteTerminalValidationIssue(
                    SiteTerminalField.TERMINAL_NAME,
                    "Enter a terminal name with at least 2 characters.",
                ),
            )
        }
        if (input.siteId.isBlank()) {
            add(
                SiteTerminalValidationIssue(
                    SiteTerminalField.TERMINAL_SITE,
                    "Select the site that owns this terminal.",
                ),
            )
        }

        val boxAddress = input.boxAddressText.trim().toIntOrNull()
        if (boxAddress == null || boxAddress !in MIN_BOX_ADDRESS..MAX_BOX_ADDRESS) {
            add(
                SiteTerminalValidationIssue(
                    SiteTerminalField.BOX_ADDRESS,
                    "Box Address must be a number from $MIN_BOX_ADDRESS to $MAX_BOX_ADDRESS.",
                ),
            )
        }

        val slotCount = input.slotCountText.trim().toIntOrNull()
        if (slotCount == null || slotCount !in MIN_NODE_ADDRESS..MAX_NODE_ADDRESS) {
            add(
                SiteTerminalValidationIssue(
                    SiteTerminalField.SLOT_COUNT,
                    "Configured slot count must be a number from $MIN_NODE_ADDRESS to $MAX_NODE_ADDRESS.",
                ),
            )
        }

        if (input.cabinetSerialPort.trim().isBlank()) {
            add(
                SiteTerminalValidationIssue(
                    SiteTerminalField.CABINET_SERIAL_PORT,
                    "Enter the cabinet serial port, for example $DEFAULT_CABINET_SERIAL_PORT.",
                ),
            )
        }

        val baudRate = input.cabinetBaudRateText.trim().toIntOrNull()
        if (baudRate == null || baudRate <= 0) {
            add(
                SiteTerminalValidationIssue(
                    SiteTerminalField.CABINET_BAUD_RATE,
                    "Enter a positive cabinet baud rate.",
                ),
            )
        }
    }

    fun mayUseNodeAddress(nodeAddress: Int, configuredSlotCount: Int): Boolean =
        nodeAddress in MIN_NODE_ADDRESS..MAX_NODE_ADDRESS && nodeAddress <= configuredSlotCount

    fun errorFor(
        issues: List<SiteTerminalValidationIssue>,
        field: SiteTerminalField,
    ): String? = issues.firstOrNull { it.field == field }?.message
}