package com.ekms.terminal.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ekms.shared.domain.KeySlot
import com.ekms.shared.domain.ManagedKey
import com.ekms.terminal.hardware.VideoRecordingController
import kotlinx.coroutines.delay

/**
 * Smart Key Cabinet User Manual V2.1, Section 3 — Key return.
 *
 * Reached directly from the login/home screen by a key-card swipe, never
 * through a menu (see [TerminalLoginScreen]'s key-card panel). Once
 * triggered, this is the only screen in the sequence: the box door opens
 * and its blue light turns on, the user inserts the key, and the process
 * completes on its own. There is no button here and no confirmation step —
 * completion is driven by [onCompleted], simulating the hardware detecting
 * the returned key, not a user tap.
 *
 * When [videoRecordingEnabled] is true (the Admin Menu's "Return key video"
 * toggle), a recording session starts and stops with this screen silently —
 * see [VideoRecordingController] for why start()/stop() are still no-ops.
 *
 * Door/light hardware control is not wired to CabinetHardwareController
 * yet: this screen only reflects the state the manual describes. Wiring it
 * to a real, currently-connected cabinet is deferred, the same way key
 * retrieval's take action is not yet a real hardware command.
 */
@Composable
fun TerminalKeyReturnScreen(
    padding: PaddingValues,
    key: ManagedKey?,
    slot: KeySlot?,
    videoRecordingEnabled: Boolean,
    onCompleted: () -> Unit,
) {
    val videoRecorder = remember { VideoRecordingController() }

    DisposableEffect(videoRecordingEnabled) {
        if (videoRecordingEnabled) {
            videoRecorder.start("key_return")
        }
        onDispose { videoRecorder.stop() }
    }

    LaunchedEffect(key, slot) {
        delay(DOOR_OPEN_AUTO_COMPLETE_MILLIS)
        onCompleted()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 640.dp)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = if (slot != null) "Node ${slot.nodeAddress} door open" else "Door open",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Text(
                text = if (key != null) "Insert ${key.displayName} now." else "Insert the key now.",
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
        }
    }
}

private const val DOOR_OPEN_AUTO_COMPLETE_MILLIS = 2_500L

/**
 * Stand-in for matching a swiped key card to its exact ManagedKey. There is
 * no card-to-key registry for the shared model yet (only the terminal-local
 * TerminalKey/fob-fingerprint store has one, and that is a different key
 * identity — see EncryptedFobEnrollmentStore). Until that registry exists,
 * the returning key is resolved as "the only key currently marked taken":
 * a real match when exactly one key is out, not a fabricated success.
 */
internal fun resolveReturningKey(takenKeyIds: Set<String>, keys: List<ManagedKey>): ManagedKey? =
    keys.filter { it.id in takenKeyIds }.singleOrNull()
