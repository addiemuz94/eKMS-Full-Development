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
import androidx.compose.ui.unit.dp

/**
 * Continuous multi-key return session (CLAUDE.md "Terminal App UX Baseline (Production)" §2
 * rework): shown after a key return completes — success, failure, or abandonment — instead of
 * dropping straight back to standby, so the operator can scan the next fob immediately. Ends
 * via [onDone] or an idle timeout; the timeout itself is owned by `TerminalAdminApp` (not this
 * screen), since it must keep counting across this screen being torn down and rebuilt every
 * time a new scan restarts the underlying return attempt.
 *
 * Phase 9F (visual/theme only — no idle-timeout logic here to touch, per this file's own doc
 * above; `SESSION_IDLE_TIMEOUT_MILLIS` lives in `TerminalAdminApp.kt`, untouched): the "Returned
 * this session" card gets a light `outlineVariant` border (was bare/borderless) plus explicit
 * spacing between its title and each key name (was zero gap — `SoftCard`'s content lambda has no
 * `verticalArrangement` of its own). "Done" is now [IconActionButton] ([ActionButtonType.ACCEPT])
 * in place of [SoftPrimaryButton] — a clean fit, the one confirm action ending this session.
 */
@Composable
fun ReturnSessionScreen(
    padding: PaddingValues,
    returnedKeyNames: List<String>,
    onDone: () -> Unit,
) {
    TerminalPage(padding) {
        HeaderCard(
            title = "Key returned",
            description = "Scan the next key to keep returning, or tap Done when finished.",
        )
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
        IconActionButton(
            type = ActionButtonType.ACCEPT,
            label = "Done",
            onClick = onDone,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
