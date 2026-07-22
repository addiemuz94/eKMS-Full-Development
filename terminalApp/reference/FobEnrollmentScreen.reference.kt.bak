package com.ekms.terminal.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ekms.terminal.hardware.EncryptedFobEnrollmentStore
import com.ekms.terminal.hardware.FobEnrollmentResult
import com.ekms.terminal.hardware.FobEnrollmentSummary
import com.ekms.terminal.hardware.PublicCardReaderController
import com.ekms.terminal.hardware.PublicCardReaderState

/**
 * Supplier-style Admin -> Key Registration -> physical fob enrolment.
 * The raw UID is never shown or written to the audit text; it is retained only
 * inside [EncryptedFobEnrollmentStore].
 */
@Composable
internal fun FobEnrollmentScreen(
    padding: PaddingValues,
    key: WorkflowKey,
    bulkRegistration: Boolean,
    onBack: () -> Unit,
    onEnrollmentCompleted: (FobEnrollmentSummary) -> Unit,
    onEnrollmentRevoked: () -> Unit,
) {
    val applicationContext = LocalContext.current.applicationContext
    val enrollmentStore = remember(applicationContext) { EncryptedFobEnrollmentStore(applicationContext) }
    var readerState by remember { mutableStateOf<PublicCardReaderState>(PublicCardReaderState.Idle) }
    var capturedRawUid by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    val storedEnrollment = remember(key.id, revision) { enrollmentStore.enrollmentFor(key.id) }
    val readerController = remember(key.id) {
        PublicCardReaderController(
            onStateChanged = { readerState = it },
            onCardDetected = { rawUid ->
                // The raw value remains only in memory until the administrator confirms.
                capturedRawUid = rawUid
            },
        )
    }

    DisposableEffect(readerController) {
        onDispose { readerController.close() }
    }

    val isScanning = readerState is PublicCardReaderState.Connecting ||
            readerState is PublicCardReaderState.AwaitingCard

    WorkflowPage(
        padding = padding,
        title = if (bulkRegistration) "Register all keys" else "Physical fob enrolment",
        description = "${key.displayName} · Slot ${key.nodeAddress}. Scan its physical fob using the Terminal reader.",
        onBack = onBack,
    ) {
        NoticeCard(
            "Reader path: /dev/ttyS2 at 9600 baud. Close the supplier/demo app before scanning so only this Terminal app owns the reader. " +
                    "This process does not open a cabinet door or operate a key peg.",
        )
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("1. Confirm key", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("Physical fob status: ${if (storedEnrollment != null || key.fobEnrolled) "enrolled (reference protected)" else "not enrolled"}")
                Text("The fob UID is never displayed on this screen or in audit records.", style = MaterialTheme.typography.bodySmall)
            }
        }
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("2. Scan physical fob", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(readerState.label())
                Button(
                    onClick = {
                        notice = null
                        readerController.start()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isScanning && capturedRawUid == null,
                ) {
                    Text(if (storedEnrollment == null && !key.fobEnrolled) "Start fob scan" else "Scan replacement fob")
                }
                if (isScanning) {
                    OutlinedButton(onClick = readerController::stop, modifier = Modifier.fillMaxWidth()) { Text("Stop scan") }
                }
            }
        }

        capturedRawUid?.let { rawUid ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("3. Confirm protected enrolment", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("A fob was detected for ${key.displayName}. Its identifier is intentionally hidden.")
                    Button(
                        onClick = {
                            when (val result = enrollmentStore.enroll(key.id, rawUid, System.currentTimeMillis())) {
                                is FobEnrollmentResult.Saved -> {
                                    onEnrollmentCompleted(result.summary)
                                    notice = if (result.replacedExisting) {
                                        "The protected fob reference was replaced for ${key.displayName}."
                                    } else {
                                        "The physical fob was enrolled for ${key.displayName}."
                                    }
                                    revision += 1
                                }

                                FobEnrollmentResult.AlreadyAssigned -> {
                                    notice = "This fob is already assigned to another key. Revoke it there before enrolment."
                                }

                                FobEnrollmentResult.AlreadyEnrolledToSelectedKey -> {
                                    notice = "This fob is already enrolled to ${key.displayName}; no change was made."
                                }

                                FobEnrollmentResult.InvalidCard -> {
                                    notice = "The reader response was not a valid supported fob identifier."
                                }

                                FobEnrollmentResult.StorageError -> {
                                    notice = "The fob could not be stored securely. No enrolment was changed."
                                }
                            }
                            capturedRawUid = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Confirm enrolment")
                    }
                    TextButton(onClick = { capturedRawUid = null }) { Text("Discard scanned fob") }
                }
            }
        }

        if (storedEnrollment != null || key.fobEnrolled) {
            OutlinedButton(
                onClick = {
                    enrollmentStore.revoke(key.id)
                    onEnrollmentRevoked()
                    revision += 1
                    notice = "The protected local fob enrollment was revoked for ${key.displayName}."
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Revoke protected fob")
            }
        }

        notice?.let { NoticeCard(it) }
    }
}

@Composable
internal fun ReturnScanScreen(
    padding: PaddingValues,
    onBack: () -> Unit,
    onKeyMatched: (String) -> Unit,
    onWorkflowTestReturn: () -> Unit,
) {
    val applicationContext = LocalContext.current.applicationContext
    val enrollmentStore = remember(applicationContext) { EncryptedFobEnrollmentStore(applicationContext) }
    var readerState by remember { mutableStateOf<PublicCardReaderState>(PublicCardReaderState.Idle) }
    var notice by remember { mutableStateOf<String?>(null) }
    val readerController = remember {
        PublicCardReaderController(
            onStateChanged = { readerState = it },
            onCardDetected = { rawUid ->
                val matchedKeyId = enrollmentStore.keyIdFor(rawUid)
                if (matchedKeyId == null) {
                    notice = "This fob is not registered on this Terminal. No return action was started."
                } else {
                    onKeyMatched(matchedKeyId)
                }
            },
        )
    }

    DisposableEffect(readerController) {
        onDispose { readerController.close() }
    }

    val isScanning = readerState is PublicCardReaderState.Connecting ||
            readerState is PublicCardReaderState.AwaitingCard

    WorkflowPage(
        padding = padding,
        title = "Return a key",
        description = "Present the returned physical key fob. The Terminal identifies the correct registered slot without displaying the UID.",
        onBack = onBack,
    ) {
        NoticeCard("The supplier flow opens the matched slot after the fob is identified. This screen does not send a cabinet command until physical return execution is explicitly enabled.")
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Public fob reader", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("/dev/ttyS2 · 9600 baud")
                Text(readerState.label(), style = MaterialTheme.typography.bodyMedium)
                Button(
                    onClick = {
                        notice = null
                        readerController.start()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isScanning,
                ) {
                    Text("Start return scan")
                }
                if (isScanning) {
                    OutlinedButton(onClick = readerController::stop, modifier = Modifier.fillMaxWidth()) { Text("Stop scan") }
                }
            }
        }
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Workflow test only", fontWeight = FontWeight.SemiBold)
                Text("Use this only to verify the return navigation when the physical reader is not available. It never simulates a raw UID or opens a cabinet.", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = onWorkflowTestReturn, modifier = Modifier.fillMaxWidth()) {
                    Text("Test return for Fleet Van Key")
                }
            }
        }
        notice?.let { NoticeCard(it) }
    }
}

private fun PublicCardReaderState.label(): String = when (this) {
    PublicCardReaderState.Idle -> "Reader is stopped."
    PublicCardReaderState.Connecting -> "Connecting to the public card reader…"
    is PublicCardReaderState.AwaitingCard -> message
    PublicCardReaderState.CardCaptured -> "Fob captured. Identifying its registered key…"
    PublicCardReaderState.Stopped -> "Reader scan stopped."
    is PublicCardReaderState.Error -> message
}