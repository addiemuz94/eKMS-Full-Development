package com.ekms.mobile.ui.digitalkey

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/**
 * Digital Key's only current UI surface. Deliberately a dialog, not a screen — tapping the
 * Digital Key entry in the bottom bar never navigates anywhere. Copy is intentionally neutral
 * and brief; the hardware-feasibility reasoning behind "coming soon" lives in CLAUDE.md's
 * Hardware Feature Findings section only, never in end-user-facing copy.
 */
@Composable
fun DigitalKeyComingSoonDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("OK") }
        },
        title = { Text("Digital Key is coming soon") },
        text = { Text("We're working on letting your phone act as a key. Check back in a future update.") },
    )
}
