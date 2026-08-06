package com.ekms.terminal.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ekms.terminal.hardware.FingerprintIdentifyOutcome
import com.ekms.terminal.ui.theme.StatusTone

/**
 * Fingerprint login — new work (Phase 3). Reuses [com.ekms.terminal.hardware.FingerprintHardwareController.identifyFingerprint]
 * (built alongside this screen — `R503FingerprintProtocol.autoIdentify` previously had zero
 * callers anywhere) rather than a second scan implementation; this screen only renders progress
 * and the outcome. A no-match or hardware failure shows a clear error and never silently falls
 * through to another method — the operator must explicitly retry or go back and pick a
 * different one.
 *
 * **Auto-start (UI/trigger-timing pass)**: scanning begins automatically on screen entry rather
 * than waiting for a manual tap — see the `LaunchedEffect(Unit)` below for why `Unit` is the
 * deliberately-correct key here (unlike face login, there's no reactive permission-grant signal
 * this needs to wait for; fingerprint hardware needs no runtime permission). The button lower on
 * the screen remains a *retry* affordance for after a `NoMatch`/`Failed` outcome.
 */
@Composable
fun TerminalFingerprintLoginScreen(
    padding: PaddingValues,
    onBack: () -> Unit,
    onIdentify: (
        onProgress: (String) -> Unit,
        onOutcome: (FingerprintIdentifyOutcome) -> Unit,
    ) -> Unit,
    onMatched: (templateId: Int) -> Unit,
) {
    var scanning by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf<String?>(null) }
    var errorText by remember { mutableStateOf<String?>(null) }

    // Declared before the auto-start LaunchedEffect below — local `fun`s resolve by source
    // order, not execution order (see TerminalFaceLoginScreen's matching note / CLAUDE.md's
    // Phase 3 postLoginRoute gotcha).
    fun startScan() {
        errorText = null
        statusText = "Preparing fingerprint sensor…"
        scanning = true
        onIdentify(
            { step -> statusText = step },
            { outcome ->
                scanning = false
                when (outcome) {
                    is FingerprintIdentifyOutcome.Matched -> {
                        statusText = null
                        onMatched(outcome.templateId)
                    }

                    FingerprintIdentifyOutcome.NoMatch -> {
                        statusText = null
                        errorText = "Fingerprint not recognized. Try again or choose a different method."
                    }

                    is FingerprintIdentifyOutcome.Failed -> {
                        statusText = null
                        errorText = outcome.message
                    }
                }
            },
        )
    }

    // Auto-start: runs exactly once per screen entry. Unit is the deliberately-correct key here
    // — there is no reactive state (like face login's camera-permission grant) this needs to
    // wait for or respond a second time to; identifyFingerprint() handles its own sensor
    // connection state internally (untouched by this pass, per scope). A fresh screen entry
    // means a fresh composition instance, so this fires fresh every time the screen is
    // (re-)entered, same "once per entry, not per recomposition" requirement as the face login
    // screen's keyed-on-hasCameraPermission effect.
    LaunchedEffect(Unit) {
        startScan()
    }

    val tone = if (errorText != null) StatusTone.ALARM else StatusTone.NORMAL

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 640.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BackButton(onBack, enabled = !scanning)
            // Modest size bump (this pass): a "place your finger" illustration didn't exist
            // before — SoftWaitPanel itself is a shared component used across many other
            // screens (Take/Return Flow, NFC login), so its own size/padding is deliberately
            // left untouched here to avoid an unintended blast radius; this large tone-colored
            // icon is local to this screen only and is the actual size/prominence increase.
            // Readability pass: 88dp -> 106dp (x1.2).
            Icon(
                imageVector = Icons.Filled.Fingerprint,
                contentDescription = null,
                tint = statusToneColor(tone),
                modifier = Modifier.size(106.dp),
            )
            SoftWaitPanel(
                tone = tone,
                title = "Fingerprint",
                message = errorText ?: statusText ?: "Place a finger on the sensor to sign in.",
                showProgress = scanning,
                modifier = Modifier.widthIn(max = 640.dp),
            )
            if (!scanning) {
                OutlinedButton(onClick = ::startScan, modifier = Modifier.fillMaxWidth()) {
                    Text(if (errorText != null) "Try again" else "Scan fingerprint")
                }
            }
        }
    }
}
