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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
 * through a menu (see [TerminalLoginScreen]'s key-card panel). This is the
 * only screen in the sequence: the box door opens and its blue light turns
 * on, the user inserts the key, and the process completes on its own —
 * there is no button here and no confirmation step.
 *
 * When [slot] is known (phase 9), the sequence is real hardware, driven by
 * [CabinetHardwareController] through [onBeginReturn]/[onAwaitInsertion]:
 * Blue Light On (0x11) + Eject Door (0x23), then poll Test Micro Switch
 * (0x16) until the bolt is physically present, then secure it (0x14 —
 * field-verified to lock the peg) and turn the light off. [onCompleted]
 * only fires once that's genuinely confirmed, not on a fixed timer.
 *
 * When [slot] is null — [resolveReturningKey]'s heuristic couldn't
 * identify a unique returning key — there is no node to address, so this
 * falls back to the screen's original fixed-delay completion; that gap is
 * the same card-to-key registry limitation noted on [resolveReturningKey].
 *
 * When [videoRecordingEnabled] is true (the Admin Menu's "Return key video"
 * toggle), a recording session starts and stops with this screen silently —
 * see [VideoRecordingController] for why start()/stop() are still no-ops.
 */
@Composable
fun TerminalKeyReturnScreen(
    padding: PaddingValues,
    key: ManagedKey?,
    slot: KeySlot?,
    videoRecordingEnabled: Boolean,
    onBeginReturn: (nodeAddress: Int, onReady: () -> Unit, onFailure: (String) -> Unit) -> Unit,
    onAwaitInsertion: (nodeAddress: Int, onSecured: () -> Unit, onFailure: (String) -> Unit) -> Unit,
    onCompleted: () -> Unit,
) {
    val videoRecorder = remember { VideoRecordingController() }
    var failureMessage by remember { mutableStateOf<String?>(null) }

    DisposableEffect(videoRecordingEnabled) {
        if (videoRecordingEnabled) {
            videoRecorder.start("key_return")
        }
        onDispose { videoRecorder.stop() }
    }

    LaunchedEffect(key, slot) {
        val nodeAddress = slot?.nodeAddress
        if (nodeAddress == null) {
            delay(NO_NODE_AUTO_COMPLETE_MILLIS)
            onCompleted()
            return@LaunchedEffect
        }
        onBeginReturn(
            nodeAddress,
            { onAwaitInsertion(nodeAddress, onCompleted) { message -> failureMessage = message } },
            { message -> failureMessage = message },
        )
    }

    // A hardware failure must not strand the operator on this screen forever;
    // this is the same "no extra step" screen, just returning to idle after
    // showing what went wrong instead of silently retrying indefinitely.
    LaunchedEffect(failureMessage) {
        if (failureMessage != null) {
            delay(FAILURE_AUTO_RETURN_MILLIS)
            onCompleted()
        }
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
            val currentFailure = failureMessage
            if (currentFailure != null) {
                Text(
                    text = "Key return problem",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
                Text(currentFailure, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            } else {
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
}

private const val NO_NODE_AUTO_COMPLETE_MILLIS = 2_500L
private const val FAILURE_AUTO_RETURN_MILLIS = 3_000L

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
