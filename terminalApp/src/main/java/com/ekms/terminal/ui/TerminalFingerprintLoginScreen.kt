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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
            BackButton(onBack, enabled = !scanning)
            SoftWaitPanel(
                tone = if (errorText != null) StatusTone.ALARM else StatusTone.NORMAL,
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
