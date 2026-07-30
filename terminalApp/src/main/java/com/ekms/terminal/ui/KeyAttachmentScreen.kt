package com.ekms.terminal.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ekms.shared.domain.KeySlot
import com.ekms.shared.domain.ManagedKey
import com.ekms.terminal.ui.theme.StatusTone
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Key Attachment (Part 4/5) — the deliberate flow for attaching a genuinely NEW key that isn't
 * physically in the cabinet yet, replacing the old TerminalKey-based Guided Key Enrollment as
 * this dashboard tile's destination. Distinct from the silent background auto-scan
 * (`TerminalAdminApp.triggerKeyFobAutoScan`, Part 3), which captures fobs already present with
 * no screen open at all — this screen is for the case where nothing is there yet.
 *
 * Operates on real, server-synced KeySlot/ManagedKey records only. The old TerminalKey-based
 * Guided Key Enrollment screen (`EnrollKeyScreen`, `SuperAdminRoute.ENROLL_KEY`) has been removed
 * entirely — `TerminalAdminStore.createKey`/`TerminalKey` themselves are untouched, and
 * CardEnrollmentScreen's Key-card category and the key-card-swipe return trigger still read
 * existing `TerminalKey` records exactly as before, but per the confirmed product decision no new
 * `TerminalKey` record is expected to ever be created again.
 *
 * Also hosts a second, unrelated entry point — "Enroll a key card" — purely for reachability:
 * that flow (`CardEnrollmentScreen`, NFC card-swipe enrollment feeding Return Flow's wrong-slot
 * detection) lost its only entry point when the old enrollment screen was removed, and this is
 * the nearest still-reachable screen to put it on. It shares no state, hardware sequence, or
 * mechanism with this screen's own node-based fob-attachment flow above it — rendered in its own
 * clearly separated card, below the node grid, precisely so the two are never read as one thing.
 */
private const val NODE_POLL_INTERVAL_MILLIS = 3_000L

private sealed interface AttachmentUiPhase {
    data object Idle : AttachmentUiPhase
    data class Unlocking(val nodeAddress: Int) : AttachmentUiPhase
    data class WaitingForFob(val nodeAddress: Int) : AttachmentUiPhase
    data class Saving(val nodeAddress: Int) : AttachmentUiPhase
    data class Done(val nodeAddress: Int) : AttachmentUiPhase
    data class Failed(val nodeAddress: Int, val message: String) : AttachmentUiPhase
}

private data class NodeRow(
    val nodeAddress: Int,
    val key: ManagedKey,
    val attached: Boolean,
)

@Composable
fun KeyAttachmentScreen(
    padding: PaddingValues,
    terminalId: String,
    keys: List<ManagedKey>,
    initialSlots: List<KeySlot>,
    isAttached: (managedKeyId: String) -> Boolean,
    notice: String?,
    onBack: () -> Unit,
    onLightOverview: (
        needsAttachment: List<Int>,
        alreadyAttached: List<Int>,
        onReady: () -> Unit,
        onFailure: (String) -> Unit,
    ) -> Unit,
    onClearOverview: (List<Int>) -> Unit,
    onBeginAttachment: (
        nodeAddress: Int,
        onAttached: (rawUidHex: String) -> Unit,
        onTimeout: () -> Unit,
        onFailure: (String) -> Unit,
    ) -> Unit,
    onCancelAttachment: (nodeAddress: Int) -> Unit,
    onSaveAttachment: suspend (nodeAddress: Int, managedKeyId: String, rawUidHex: String) -> Boolean,
    /** Physically flips the node's own indicator from blue to red once attached — the on-screen
     * card transitions live in the same moment; this keeps the real LED in sync with it rather
     * than leaving the cabinet's own light where beginKeyAttachment left it (off, not red). */
    onLightAttachedNode: (nodeAddress: Int) -> Unit,
    onPollSlots: suspend () -> List<KeySlot>?,
    /** Unrelated to fob attachment above — see this file's doc comment. Opens
     * CardEnrollmentScreen locked to the Key category, unscoped to any personnel record. */
    onOpenKeyCardEnrollment: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var slots by remember { mutableStateOf(initialSlots) }
    var phase by remember { mutableStateOf<AttachmentUiPhase>(AttachmentUiPhase.Idle) }
    // Attachments completed within this screen, this session — combined with the caller's own
    // isAttached() so a just-attached node flips blue → red immediately (Part 5's "no polling
    // needed for this case" requirement), without waiting for the next background sweep to
    // update the underlying store or for this screen's own slot-polling loop to notice.
    var justAttachedKeyIds by remember { mutableStateOf(setOf<String>()) }

    val rows = remember(slots, keys, justAttachedKeyIds) {
        slots.mapNotNull { slot ->
            val managedKeyId = slot.managedKeyId ?: return@mapNotNull null
            val key = keys.firstOrNull { it.id == managedKeyId } ?: return@mapNotNull null
            val attached = managedKeyId in justAttachedKeyIds || isAttached(managedKeyId)
            NodeRow(nodeAddress = slot.nodeAddress, key = key, attached = attached)
        }.sortedBy { it.nodeAddress }
    }
    val allLitNodes = remember(rows) { rows.map { it.nodeAddress } }

    // Entry: light every assigned node — blue (needs attachment) / red (already attached).
    // Exit: always clear every node this screen lit, regardless of how it was reached.
    DisposableEffect(Unit) {
        onLightOverview(
            rows.filterNot { it.attached }.map { it.nodeAddress },
            rows.filter { it.attached }.map { it.nodeAddress },
            {},
            {},
        )
        onDispose { onClearOverview(allLitNodes) }
    }

    // Reverse case (Part 5): a key deleted on web while this screen is open should turn its node
    // back to unlit. Screen-local polling only — not a general app-wide change, and stops the
    // moment this screen is left (the DisposableEffect above + this LaunchedEffect both end when
    // this composable leaves composition).
    LaunchedEffect(Unit) {
        while (true) {
            delay(NODE_POLL_INTERVAL_MILLIS)
            val fresh = onPollSlots()
            if (fresh != null) slots = fresh
        }
    }

    fun startAttachment(nodeAddress: Int, managedKeyId: String) {
        phase = AttachmentUiPhase.Unlocking(nodeAddress)
        onBeginAttachment(
            nodeAddress,
            { rawUidHex ->
                phase = AttachmentUiPhase.Saving(nodeAddress)
                scope.launch {
                    val ok = onSaveAttachment(nodeAddress, managedKeyId, rawUidHex)
                    phase = if (ok) {
                        justAttachedKeyIds = justAttachedKeyIds + managedKeyId
                        onLightAttachedNode(nodeAddress)
                        AttachmentUiPhase.Done(nodeAddress)
                    } else {
                        AttachmentUiPhase.Failed(nodeAddress, "Fob captured, but saving the enrollment failed.")
                    }
                }
            },
            { phase = AttachmentUiPhase.Failed(nodeAddress, "No fob was attached in time. Node re-locked.") },
            { message -> phase = AttachmentUiPhase.Failed(nodeAddress, message) },
        )
        // WaitingForFob is shown immediately (unlock + poll are one continuous worker-thread
        // operation from the caller's point of view — there is no separate "unlocked" callback
        // to wait for before the poll begins).
        phase = AttachmentUiPhase.WaitingForFob(nodeAddress)
    }

    TerminalPage(padding) {
        BackButton(onBack = onBack, label = "Back to Super Admin dashboard")
        HeaderCard(
            title = "Key Attachment",
            description = "Blue nodes are assigned a key but have no fob attached yet. Red nodes " +
                "already have one. Tap a blue node to attach its fob now.",
        )
        notice?.let { message -> SuperAdminNoticeCard(message) }

        val activePhase = phase
        if (activePhase !is AttachmentUiPhase.Idle) {
            SuperAdminNoticeCard(
                when (activePhase) {
                    is AttachmentUiPhase.Unlocking -> "Unlocking node ${activePhase.nodeAddress}…"
                    is AttachmentUiPhase.WaitingForFob ->
                        "Node ${activePhase.nodeAddress} unlocked — attach the new key's fob now."
                    is AttachmentUiPhase.Saving -> "Fob detected at node ${activePhase.nodeAddress} — saving…"
                    is AttachmentUiPhase.Done -> "Node ${activePhase.nodeAddress} attached and secured."
                    is AttachmentUiPhase.Failed -> activePhase.message
                    AttachmentUiPhase.Idle -> ""
                },
            )
        }
        if (activePhase is AttachmentUiPhase.WaitingForFob) {
            OutlinedButton(
                onClick = {
                    onCancelAttachment(activePhase.nodeAddress)
                    phase = AttachmentUiPhase.Idle
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Cancel attachment")
            }
        }
        if (activePhase is AttachmentUiPhase.Done || activePhase is AttachmentUiPhase.Failed) {
            Button(onClick = { phase = AttachmentUiPhase.Idle }, modifier = Modifier.fillMaxWidth()) {
                Text("OK")
            }
        }

        if (rows.isEmpty()) {
            Text("No keys are assigned a cabinet node on this terminal yet.")
        } else {
            rows.forEach { row ->
                val busy = activePhase !is AttachmentUiPhase.Idle
                StatusRingCard(
                    // NORMAL resolves to primaryContainer (this app's blue) — "needs attachment",
                    // matching the cabinet's own blue light. ALARM resolves to errorContainer
                    // (red) — "already attached", matching the cabinet's own red light. Neither
                    // is a severity judgment here, just reusing the two tones that happen to
                    // already render blue/red to mirror the physical LEDs this screen also sets.
                    tone = if (row.attached) StatusTone.ALARM else StatusTone.NORMAL,
                    onClick = if (!row.attached && !busy) {
                        { startAttachment(row.nodeAddress, row.key.id) }
                    } else {
                        null
                    },
                ) {
                    Text("Node ${row.nodeAddress} · ${row.key.displayName}", fontWeight = FontWeight.SemiBold)
                    Text(
                        if (row.attached) "Attached" else "Needs attachment — tap to attach",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        // Deliberately separated from the node grid above by its own card + heading — this is a
        // different mechanism (NFC card-swipe enrollment) sharing this screen only for
        // reachability, not a third node-attachment state. See this file's doc comment.
        SoftCard(contentPadding = 16.dp) {
            Text("Key card enrollment", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "A separate mechanism from fob attachment above — scans a key's NFC card for " +
                    "Return Flow's card-swipe and wrong-slot detection. Does not bind a key to a " +
                    "cabinet node.",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(onClick = onOpenKeyCardEnrollment, modifier = Modifier.fillMaxWidth()) {
                Text("Enroll a key card")
            }
        }
    }
}
