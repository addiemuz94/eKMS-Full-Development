package com.ekms.terminal.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ekms.terminal.ui.returnflow.ReturnSessionController

/**
 * Continuous multi-key return session (CLAUDE.md "Terminal App UX Baseline (Production)" §2 —
 * Return Flow session rebuild, Jul 2026, full scrap-and-rebuild of this screen's own ending
 * logic, not layered on top of it): shown between key returns — after success, failure, or
 * abandonment all count — instead of dropping straight back to standby, so the operator can
 * scan the next fob immediately. Door-close is now the session's **sole** ending trigger
 * (`CabinetHardwareController.beginReturnSessionDoorMonitor`, owned by
 * `ReturnSessionController`, not this screen) — there is no Done button and no idle timeout any
 * more. The old `SESSION_IDLE_TIMEOUT_MILLIS`/Done-button model this superseded is documented as
 * such in `CLAUDE_TERMINAL.md`, not silently dropped from history.
 *
 * **Return Flow rewrite, Tier 3: takes [controller] directly instead of three loose params**
 * (`returnedKeyNames`/`blockedWrongSlotNodes`/`sessionComplete`, each previously an independent
 * `TerminalAdminApp`-local `remember` this screen was handed piecemeal) — those three are now
 * properties on the one [ReturnSessionController] instance that already owns every other piece
 * of Return Flow state, so this screen reads them off it directly rather than the caller having
 * to keep three separate vars in sync with the same underlying session.
 *
 * **Multi-key Return hard-block (found via ad hoc hardware testing):**
 * [ReturnSessionController.wrongSlotBlockedNodes] is non-empty exactly when
 * `CabinetHardwareController.beginReturnSessionWrongSlotSweep` currently has one or more nodes
 * flagged wrong — a hard block, not just an alarm: `ReturnSessionController.onKeyCardScanned`'s
 * own gate rejects a new scan while this set is non-empty, so this screen's own "scan the next
 * key" instruction would be actively misleading while blocked, and is replaced entirely by the
 * block card below. This intentionally moved out of `TerminalKeyReturnScreen` (formerly rendered
 * wrong-slot state on whichever node's own screen was active) — the sweep is session-scoped, not
 * tied to any one node's cycle, so it surfaces here, on the session's own idle/listening screen,
 * instead. A concurrently-active `AwaitingInsertion` cycle at an unrelated node is unaffected
 * either way; this screen only renders during [ReturnSession.Waiting][com.ekms.terminal.ui.returnflow.ReturnSession.Waiting],
 * never alongside one.
 */
@Composable
fun ReturnSessionScreen(
    padding: PaddingValues,
    controller: ReturnSessionController,
) {
    val returnedKeyNames = controller.returnedKeyNames
    val blockedWrongSlotNodes = controller.wrongSlotBlockedNodes
    // Auto-return-to-login pass: true for a brief window right after the door is confirmed
    // closed (the session's sole ending trigger, regardless of what happened inside it), before
    // the controller tears the session down and its onSessionEnded callback returns to login.
    // Takes priority over the wrong-slot block card (moot by definition: the door can't be
    // closed while a wrong-slot block is active, since that's a hard block on ending the
    // session, but this keeps the two states mutually exclusive on-screen regardless).
    val sessionComplete = controller.sessionComplete
    TerminalPage(padding) {
        if (sessionComplete) {
            HeaderCard(
                title = "Session complete",
                description = "The door is closed. Returning to login…",
            )
        } else if (blockedWrongSlotNodes.isNotEmpty()) {
            HeaderCard(
                title = "Wrong key detected",
                description = "Scanning is paused until the wrong key is removed.",
            )
            // Same alarm-tone recipe TerminalKeyReturnScreen's own wrong-slot card used before
            // this rework moved that concern to session scope: 2.dp colorScheme.error border,
            // 4.dp elevation, errorContainer fill — the most urgent card on screen, matching the
            // hardware's own red-light escalation.
            SoftCard(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentPadding = 16.dp,
                elevation = 4.dp,
                border = BorderStroke(2.dp, MaterialTheme.colorScheme.error),
            ) {
                val nodeList = blockedWrongSlotNodes.sorted().joinToString(", ")
                Text(
                    text = if (blockedWrongSlotNodes.size == 1) "Node $nodeList" else "Nodes $nodeList",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = "Remove the wrong key from the node listed above before scanning another key.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        } else {
            HeaderCard(
                title = "Key returned",
                description = "Scan the next key to keep returning, or close the door to finish.",
            )
        }
        if (returnedKeyNames.isNotEmpty()) {
            SoftCard(
                contentPadding = 14.dp,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "Returned this session",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    returnedKeyNames.forEach { name ->
                        Text(text = name, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}
