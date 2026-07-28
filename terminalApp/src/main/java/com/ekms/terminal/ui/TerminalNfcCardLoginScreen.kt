package com.ekms.terminal.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ekms.terminal.ui.theme.StatusTone

/**
 * NFC Card login — purely informational, per Phase 3's spec: "since it's always listening
 * anyway." The public card reader (`cardReaderController` in `TerminalAdminApp.kt`) already
 * listens for both personnel-card and key-card swipes for as long as `route ==
 * SuperAdminRoute.LOGIN`, completely independent of which method screen is on top — this screen
 * changes nothing about that detection, it only gives the operator a clear full-screen
 * confirmation that tapping a card right now will be picked up. A successful personnel-card
 * match still authenticates via the same `CardUidResolver` -> `store.authenticateByUserId` path
 * this project already used before this rework.
 *
 * [onSimulateKeyCardTap] is the old single-screen login's "Key card" tile, preserved here rather
 * than dropped silently — it's a hardware-free testing convenience (see `resolveReturningKey`'s
 * doc in `TerminalKeyReturnScreen.kt`) for simulating a key-card return with no reader attached,
 * not one of the 5 specified login methods, so it doesn't get its own logo — but it's a real,
 * documented capability (CLAUDE.md's Key Return Flow notes), not incidental old code, so it's
 * kept reachable as a small secondary action instead of silently disappearing.
 */
@Composable
fun TerminalNfcCardLoginScreen(
    padding: PaddingValues,
    onBack: () -> Unit,
    onSimulateKeyCardTap: (() -> Unit)? = null,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 640.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            BackButton(onBack)
            SoftWaitPanel(
                tone = StatusTone.NORMAL,
                title = "NFC Card",
                message = "Tap your personnel card on the reader now.",
                assistText = "Listening…",
                modifier = Modifier.widthIn(max = 640.dp),
            )
            if (onSimulateKeyCardTap != null) {
                SoftTextButton(
                    text = "Simulate key-card return (test only, no reader needed)",
                    onClick = onSimulateKeyCardTap,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
