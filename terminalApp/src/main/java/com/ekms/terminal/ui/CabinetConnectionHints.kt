package com.ekms.terminal.ui

import com.ekms.terminal.hardware.CabinetHardwareController
import com.ekms.terminal.hardware.CabinetHardwareState

enum class HintSeverity {
    /** Most likely place the fault is. */
    LIKELY,

    /** Worth verifying. */
    CHECK,

    /** Healthy / informational. */
    OK,
}

data class ConnectionHint(
    val title: String,
    val detail: String,
    val severity: HintSeverity,
)

/**
 * Maps [CabinetHardwareState] into ordered “where is the problem?” hints for
 * the Hardware Status page. Pure — no Android / serial I/O.
 */
fun connectionHints(state: CabinetHardwareState): List<ConnectionHint> {
    if (state.connected && !state.busy) {
        return listOf(
            ConnectionHint(
                title = "Cabinet bus",
                detail = "Serial link is open. Door and node commands can run from Controls.",
                severity = HintSeverity.OK,
            ),
            ConnectionHint(
                title = "Port settings",
                detail = "${state.portPath} @ ${state.baudRate} · Box ${state.boxAddress}",
                severity = HintSeverity.OK,
            ),
        )
    }

    if (state.busy && !state.connected) {
        return listOf(
            ConnectionHint(
                title = "Opening serial port",
                detail = "Wait until the port finishes opening. Do not change path or baud mid-connect.",
                severity = HintSeverity.CHECK,
            ),
            ConnectionHint(
                title = "Target port",
                detail = "Connecting to ${state.portPath} @ ${state.baudRate}.",
                severity = HintSeverity.CHECK,
            ),
        )
    }

    val hints = mutableListOf<ConnectionHint>()
    val message = state.message
    val lower = message.lowercase()

    when {
        "permission" in lower || "eacces" in lower -> {
            hints += ConnectionHint(
                title = "Serial permission",
                detail = "The app cannot open ${state.portPath}. On the F7G18P, confirm the Terminal process may access the serial device.",
                severity = HintSeverity.LIKELY,
            )
        }
        "no such file" in lower || "enoent" in lower || "not found" in lower -> {
            hints += ConnectionHint(
                title = "Serial path",
                detail = "${state.portPath} does not exist on this device. Use a physical F7G18P — emulators have no /dev/ttyS1 cabinet port.",
                severity = HintSeverity.LIKELY,
            )
        }
        "failed" in lower -> {
            hints += ConnectionHint(
                title = "Cabinet connection",
                detail = message.removePrefix("Cabinet connection failed: ").ifBlank { message },
                severity = HintSeverity.LIKELY,
            )
        }
        else -> {
            hints += ConnectionHint(
                title = "Serial port ${CabinetHardwareController.DEFAULT_PORT_PATH}",
                detail = "Cabinet is not connected. Open the port on a real F7G18P terminal (not an emulator).",
                severity = HintSeverity.LIKELY,
            )
        }
    }

    if (state.portPath != CabinetHardwareController.DEFAULT_PORT_PATH) {
        hints += ConnectionHint(
            title = "Port path",
            detail = "Current path is ${state.portPath}. Default cabinet bus is ${CabinetHardwareController.DEFAULT_PORT_PATH}.",
            severity = HintSeverity.CHECK,
        )
    }

    if (state.baudRate != CabinetHardwareController.DEFAULT_BAUD_RATE) {
        hints += ConnectionHint(
            title = "Baud rate",
            detail = "Current baud is ${state.baudRate}. Cabinet default is ${CabinetHardwareController.DEFAULT_BAUD_RATE} 8N1.",
            severity = HintSeverity.CHECK,
        )
    }

    if (state.keyReturnMonitoring) {
        hints += ConnectionHint(
            title = "Key return in progress",
            detail = "A return monitor is active. Wait until the slot is secured before reconnecting or sending commands.",
            severity = HintSeverity.CHECK,
        )
    }

    if ("take is in progress" in lower || "key take" in lower) {
        hints += ConnectionHint(
            title = "Key take in progress",
            detail = "Finish or abandon the take flow before changing the cabinet connection.",
            severity = HintSeverity.CHECK,
        )
    }

    hints += ConnectionHint(
        title = "Cabinet power & cable",
        detail = "Confirm the cabinet controller is powered and the internal serial link to the Terminal is seated.",
        severity = HintSeverity.CHECK,
    )

    return hints
}
