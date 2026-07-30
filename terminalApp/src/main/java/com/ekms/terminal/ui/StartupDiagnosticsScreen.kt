package com.ekms.terminal.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.ekms.terminal.hardware.CabinetHardwareState
import com.ekms.terminal.hardware.FingerprintHardwareState
import com.ekms.terminal.hardware.NetworkStatus
import com.ekms.terminal.hardware.NetworkTransport
import com.ekms.terminal.hardware.PublicCardReaderState
import com.ekms.terminal.hardware.face.FaceCameraController

/**
 * The actual hardware-detection logic, factored out so [TerminalBootSplashScreen] (the
 * animated boot splash that now runs ahead of this screen at the same cold-launch/crash-
 * recovery trigger point) can drive the exact same checks without duplicating any of the
 * per-subsystem detection rules below.
 */
internal fun startupDiagnosticChecks(
    hardwareState: CabinetHardwareState,
    fingerprintHardwareState: FingerprintHardwareState,
    cameraAvailable: Boolean,
    publicCardReaderState: PublicCardReaderState,
    networkStatus: NetworkStatus,
): List<ConnectionHint> = listOf(
    cabinetDiagnostic(hardwareState),
    fingerprintDiagnostic(fingerprintHardwareState),
    cameraDiagnostic(cameraAvailable),
    publicReaderDiagnostic(publicCardReaderState),
    networkDiagnostic(networkStatus),
)

/**
 * Formerly the always-visible startup screen, wired directly from [TerminalAdminApp]; now
 * reached only from [TerminalBootSplashScreen]'s collapsed "View details" expansion (both the
 * initial splash and its failure warning card), reusing [ConnectionHintCard] per line rather
 * than duplicating this screen's own rendering. Presents one line per subsystem rather than
 * reusing [connectionHints]'s multi-line detail — that function is written for the Hardware
 * Status admin page, this is meant to be readable in a few seconds. Non-blocking: every check
 * here can fail without preventing sign-in or key operations; only [NetworkStatus] additionally
 * affects which *other* screens (pairing, live sync) can proceed, and even that degrades
 * gracefully rather than freezing this screen.
 */
@Composable
fun StartupDiagnosticsScreen(
    padding: PaddingValues,
    hardwareState: CabinetHardwareState,
    fingerprintHardwareState: FingerprintHardwareState,
    cameraAvailable: Boolean,
    publicCardReaderState: PublicCardReaderState,
    networkStatus: NetworkStatus,
    onContinue: () -> Unit,
) {
    val checks = startupDiagnosticChecks(
        hardwareState = hardwareState,
        fingerprintHardwareState = fingerprintHardwareState,
        cameraAvailable = cameraAvailable,
        publicCardReaderState = publicCardReaderState,
        networkStatus = networkStatus,
    )
    val hardwareChecks = checks.dropLast(1)
    val hardwareHealthy = hardwareChecks.none { it.severity == HintSeverity.FAIL }
    val stillChecking = checks.any { it.severity == HintSeverity.CHECK }

    TerminalPage(padding) {
        HeaderCard(
            title = "Startup diagnostics",
            description = "Checking cabinet, biometric, card-reader, and network hardware. " +
                "This never blocks sign-in — continue any time.",
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SoftAssistChip(
                text = if (hardwareHealthy) "Hardware: OK" else "Hardware: Check required",
                success = hardwareHealthy,
                attention = !hardwareHealthy,
            )
            SoftAssistChip(
                text = if (networkStatus.hasInternet) {
                    "Network: Online (${networkStatus.transport.displayLabel()})"
                } else {
                    "Network: Offline — local only"
                },
                success = networkStatus.hasInternet,
                attention = !networkStatus.hasInternet,
            )
        }

        checks.forEach { check -> ConnectionHintCard(check) }

        Text(
            text = "A subsystem check here does not stop the terminal from operating locally. " +
                "Full detail and re-checks live in Admin Menu → Hardware Status.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SoftPrimaryButton(
            text = if (stillChecking) "Continue (checks still running)" else "Continue",
            onClick = onContinue,
        )
    }
}

private fun cabinetDiagnostic(state: CabinetHardwareState): ConnectionHint = when {
    state.connected -> ConnectionHint(
        title = "Cabinet link",
        detail = "Serial link is open (${state.portPath} @ ${state.baudRate}).",
        severity = HintSeverity.OK,
    )

    state.busy -> ConnectionHint(
        title = "Cabinet link",
        detail = "Opening ${state.portPath}…",
        severity = HintSeverity.CHECK,
    )

    else -> ConnectionHint(title = "Cabinet link", detail = state.message, severity = HintSeverity.FAIL)
}

private fun fingerprintDiagnostic(state: FingerprintHardwareState): ConnectionHint = when {
    state.connected -> ConnectionHint(title = "Fingerprint module", detail = state.message, severity = HintSeverity.OK)
    state.busy -> ConnectionHint(
        title = "Fingerprint module",
        detail = "Opening the R503 sensor port…",
        severity = HintSeverity.CHECK,
    )

    else -> ConnectionHint(title = "Fingerprint module", detail = state.message, severity = HintSeverity.FAIL)
}

/**
 * Presence-only, deliberately not a full [FaceCameraController] instantiation: that controller
 * loads OpenCV + MediaPipe models (~41MB) and opens the camera, which would meaningfully slow
 * every single launch and open the camera before the operator has asked for face enrollment.
 * Checking that the RGB camera ID the enrollment flow actually uses is enumerable by
 * [android.hardware.camera2.CameraManager] is a much cheaper, still-real signal of whether the
 * hardware is present at all.
 */
private fun cameraDiagnostic(cameraAvailable: Boolean): ConnectionHint = if (cameraAvailable) {
    ConnectionHint(
        title = "Camera / face module",
        detail = "Camera ${FaceCameraController.RGB_CAMERA_ID} is present.",
        severity = HintSeverity.OK,
    )
} else {
    ConnectionHint(
        title = "Camera / face module",
        detail = "No camera reported at ID ${FaceCameraController.RGB_CAMERA_ID}.",
        severity = HintSeverity.FAIL,
    )
}

/**
 * Covers the public card-swipe reader path only (`/dev/ttyS2`, [com.ekms.terminal.hardware.PublicCardReaderController]
 * — the same instance [TerminalAdminApp] already auto-starts while idling at login, reused here
 * rather than opening a second owner of the same physical port). The cabinet's own node-level
 * card reads (protocol commands 0x15/0x17) run over the cabinet's `/dev/ttyS1` link with no
 * independently observable connection state beyond "cabinet connected or not" — that is already
 * covered by [cabinetDiagnostic], so it is not repeated here as a separate line.
 */
private fun publicReaderDiagnostic(state: PublicCardReaderState): ConnectionHint = when (state) {
    is PublicCardReaderState.AwaitingCard -> ConnectionHint(
        title = "NFC / card reader",
        detail = "Reader is armed and waiting for a card.",
        severity = HintSeverity.OK,
    )

    PublicCardReaderState.CardCaptured -> ConnectionHint(
        title = "NFC / card reader",
        detail = "Reader is working (last scan captured).",
        severity = HintSeverity.OK,
    )

    PublicCardReaderState.Connecting -> ConnectionHint(
        title = "NFC / card reader",
        detail = "Opening the public card reader…",
        severity = HintSeverity.CHECK,
    )

    PublicCardReaderState.Idle -> ConnectionHint(
        title = "NFC / card reader",
        detail = "Reader has not started yet.",
        severity = HintSeverity.CHECK,
    )

    PublicCardReaderState.Stopped -> ConnectionHint(
        title = "NFC / card reader",
        detail = "Reader is stopped.",
        severity = HintSeverity.CHECK,
    )

    is PublicCardReaderState.Error -> ConnectionHint(
        title = "NFC / card reader",
        detail = state.message,
        severity = HintSeverity.FAIL,
    )
}

private fun networkDiagnostic(status: NetworkStatus): ConnectionHint = when {
    status.hasInternet -> ConnectionHint(title = "Network", detail = status.message, severity = HintSeverity.OK)
    status.transport != NetworkTransport.NONE ->
        ConnectionHint(title = "Network", detail = status.message, severity = HintSeverity.CHECK)

    else -> ConnectionHint(title = "Network", detail = status.message, severity = HintSeverity.FAIL)
}

private fun NetworkTransport.displayLabel(): String = when (this) {
    NetworkTransport.ETHERNET -> "Ethernet"
    NetworkTransport.WIFI -> "Wi-Fi"
    NetworkTransport.OTHER -> "Other"
    NetworkTransport.NONE -> "None"
}
