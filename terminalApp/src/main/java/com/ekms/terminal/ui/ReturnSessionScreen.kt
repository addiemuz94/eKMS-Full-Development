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
 * Continuous multi-key return session (CLAUDE.md "Terminal App UX Baseline (Production)" §2 —
 * Return Flow session rebuild, Jul 2026, full scrap-and-rebuild of this screen's own ending
 * logic, not layered on top of it): shown between key returns — after success, failure, or
 * abandonment all count — instead of dropping straight back to standby, so the operator can
 * scan the next fob immediately. Door-close is now the session's **sole** ending trigger
 * (`CabinetHardwareController.beginReturnSessionDoorMonitor`, owned by `TerminalAdminApp`, not
 * this screen) — there is no Done button and no idle timeout any more. The old
 * `SESSION_IDLE_TIMEOUT_MILLIS`/Done-button model this superseded is documented as such in
 * `CLAUDE_TERMINAL.md`, not silently dropped from history.
 */
@Composable
fun ReturnSessionScreen(
    padding: PaddingValues,
    returnedKeyNames: List<String>,
) {
    TerminalPage(padding) {
        HeaderCard(
            title = "Key returned",
            description = "Scan the next key to keep returning, or close the door to finish.",
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
    }
}
