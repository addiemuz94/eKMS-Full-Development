package com.ekms.terminal.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Continuous multi-key return session (CLAUDE.md "Terminal App UX Baseline (Production)" §2
 * rework): shown after a key return completes — success, failure, or abandonment — instead of
 * dropping straight back to standby, so the operator can scan the next fob immediately. Ends
 * via [onDone] or an idle timeout; the timeout itself is owned by `TerminalAdminApp` (not this
 * screen), since it must keep counting across this screen being torn down and rebuilt every
 * time a new scan restarts the underlying return attempt.
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
            SoftCard(contentPadding = 14.dp) {
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
        SoftPrimaryButton(text = "Done", onClick = onDone)
    }
}
