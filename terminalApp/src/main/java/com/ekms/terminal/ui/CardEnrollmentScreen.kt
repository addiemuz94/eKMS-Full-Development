package com.ekms.terminal.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ekms.terminal.data.TerminalKey
import com.ekms.terminal.data.TerminalUser
import com.ekms.terminal.hardware.PublicCardReaderController
import com.ekms.terminal.hardware.PublicCardReaderState
import com.ekms.terminal.hardware.UidEnrollmentResult
import com.ekms.terminal.hardware.UidEnrollmentSummary

/**
 * Manual capture enrolment for both personnel-card and key-card UIDs, per
 * the fix for the personnel-vs-key card disambiguation problem: since both
 * card kinds share the same physical medium and UID space, enrolment is the
 * only place a UID's *meaning* is decided — a raw scan carries no category
 * of its own. Each capture is a single scan-and-store, unlike fingerprint or
 * face, which need feature extraction; there is nothing else to compute.
 *
 * This screen reuses the section 9 public card-swipe reader
 * (`PublicCardReaderController`, `/dev/ttyS2`) that also feeds the login/
 * return flow, but owns its own instance for the duration of this screen —
 * the shared instance in `TerminalAdminApp` is stopped automatically
 * whenever navigation leaves the login screen (see `isIdleAtLogin`), so
 * there is no port contention.
 */
@Composable
fun CardEnrollmentScreen(
    padding: PaddingValues,
    users: List<TerminalUser>,
    keys: List<TerminalKey>,
    notice: String?,
    onBack: () -> Unit,
    onEnrollPersonnelCard: (userId: String, rawUid: String) -> UidEnrollmentResult,
    onEnrollKeyCard: (keyId: String, rawUid: String) -> UidEnrollmentResult,
    onRevokePersonnelCard: (userId: String) -> UidEnrollmentSummary?,
    onRevokeKeyCard: (keyId: String) -> UidEnrollmentSummary?,
) {
    var category by remember { mutableStateOf(CardEnrollmentCategory.PERSONNEL) }
    var selectedUserId by remember(users) { mutableStateOf(users.firstOrNull()?.id.orEmpty()) }
    var selectedKeyId by remember(keys) { mutableStateOf(keys.firstOrNull()?.id.orEmpty()) }
    var readerState by remember { mutableStateOf<PublicCardReaderState>(PublicCardReaderState.Idle) }
    var scanning by remember { mutableStateOf(false) }
    var resultMessage by remember { mutableStateOf<String?>(null) }

    val selectedUser = users.firstOrNull { it.id == selectedUserId }
    val selectedKey = keys.firstOrNull { it.id == selectedKeyId }
    val canScan = when (category) {
        CardEnrollmentCategory.PERSONNEL -> selectedUser != null
        CardEnrollmentCategory.KEY -> selectedKey != null
    }

    val currentCategory by rememberUpdatedState(category)
    val currentSelectedUser by rememberUpdatedState(selectedUser)
    val currentSelectedKey by rememberUpdatedState(selectedKey)

    val cardReader = remember {
        PublicCardReaderController(
            onStateChanged = { state -> readerState = state },
            onCardDetected = { rawUid ->
                scanning = false
                resultMessage = when (currentCategory) {
                    CardEnrollmentCategory.PERSONNEL -> currentSelectedUser?.let { user ->
                        describeOutcome(onEnrollPersonnelCard(user.id, rawUid), user.displayName)
                    }

                    CardEnrollmentCategory.KEY -> currentSelectedKey?.let { key ->
                        describeOutcome(onEnrollKeyCard(key.id, rawUid), key.displayName)
                    }
                } ?: "No record was selected. Nothing was enrolled."
            },
        )
    }

    DisposableEffect(cardReader) {
        onDispose { cardReader.close() }
    }

    fun startScan() {
        resultMessage = null
        scanning = true
        cardReader.start()
    }

    fun cancelScan() {
        scanning = false
        cardReader.stop()
    }

    fun revokeSelectedRecord() {
        resultMessage = when (category) {
            CardEnrollmentCategory.PERSONNEL -> selectedUser?.let { user ->
                val summary = onRevokePersonnelCard(user.id)
                if (summary != null) "Card revoked from ${user.displayName}." else "No card was enrolled to ${user.displayName}."
            }

            CardEnrollmentCategory.KEY -> selectedKey?.let { key ->
                val summary = onRevokeKeyCard(key.id)
                if (summary != null) "Card revoked from ${key.displayName}." else "No card was enrolled to ${key.displayName}."
            }
        } ?: "No record was selected. Nothing was revoked."
    }

    TerminalPage(padding) {
        BackButton(onBack = onBack, enabled = !scanning)
        HeaderCard(
            title = "Card enrollment",
            description = "Scan a personnel card or a key card once to store its raw UID against the selected record. The same physical reader is used for both — this is the only place that decides which one a card belongs to.",
        )
        notice?.let { message -> SuperAdminNoticeCard(message) }
        resultMessage?.let { message -> SuperAdminNoticeCard(message) }

        Text("Card kind", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Box(modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = {
                    category = if (category == CardEnrollmentCategory.PERSONNEL) {
                        CardEnrollmentCategory.KEY
                    } else {
                        CardEnrollmentCategory.PERSONNEL
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !scanning,
            ) {
                Text("Selected: " + category.label + " · change")
            }
        }

        when (category) {
            CardEnrollmentCategory.PERSONNEL -> {
                Text("Personnel record", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                if (users.isEmpty()) {
                    Text("Add personnel before enrolling a personnel card.")
                } else {
                    OutlinedButton(
                        onClick = { selectedUserId = nextUserId(selectedUserId, users) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !scanning,
                    ) {
                        Text((selectedUser?.let { it.displayName + " · " + it.role.label } ?: "Select personnel") + " · change")
                    }
                }
            }

            CardEnrollmentCategory.KEY -> {
                Text("Key record", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                if (keys.isEmpty()) {
                    Text("Enroll a key before enrolling a key card.")
                } else {
                    OutlinedButton(
                        onClick = { selectedKeyId = nextKeyId(selectedKeyId, keys) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !scanning,
                    ) {
                        Text((selectedKey?.displayName ?: "Select a key") + " · change")
                    }
                }
            }
        }

        if (!scanning) {
            Button(
                onClick = ::startScan,
                modifier = Modifier.fillMaxWidth(),
                enabled = canScan,
            ) {
                Text("Scan a card to enroll")
            }
            OutlinedButton(
                onClick = ::revokeSelectedRecord,
                modifier = Modifier.fillMaxWidth(),
                enabled = canScan,
            ) {
                Text("Revoke this record's card")
            }
        } else {
            Text(
                text = when (val state = readerState) {
                    PublicCardReaderState.Connecting -> "Connecting the public card reader…"
                    is PublicCardReaderState.AwaitingCard -> state.message
                    PublicCardReaderState.CardCaptured -> "Card captured. Saving…"
                    is PublicCardReaderState.Error -> state.message
                    else -> "Present the card at the public reader…"
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(
                onClick = ::cancelScan,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Cancel scan")
            }
        }
    }
}

private enum class CardEnrollmentCategory(val label: String) {
    PERSONNEL("Personnel card"),
    KEY("Key card"),
}

private fun nextUserId(currentUserId: String, users: List<TerminalUser>): String {
    if (users.isEmpty()) return ""
    val index = users.indexOfFirst { it.id == currentUserId }
    return users[(index + 1 + users.size) % users.size].id
}

private fun nextKeyId(currentKeyId: String, keys: List<TerminalKey>): String {
    if (keys.isEmpty()) return ""
    val index = keys.indexOfFirst { it.id == currentKeyId }
    return keys[(index + 1 + keys.size) % keys.size].id
}

private fun describeOutcome(result: UidEnrollmentResult, recordLabel: String): String = when (result) {
    is UidEnrollmentResult.Saved -> if (result.replacedExisting) {
        "Card enrolled to $recordLabel, replacing its previous card."
    } else {
        "Card enrolled to $recordLabel."
    }

    UidEnrollmentResult.AlreadyAssigned -> "This card is already enrolled to a different record. Revoke it there first."
    UidEnrollmentResult.AlreadyEnrolledToSelectedRecord -> "This card is already enrolled to $recordLabel."
    UidEnrollmentResult.InvalidCard -> "That scan could not be read as a valid card UID. Try again."
    UidEnrollmentResult.StorageError -> "The card could not be saved due to a local storage error."
}
