package com.ekms.terminal.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.ekms.terminal.hardware.KeyFobScanResult
import com.ekms.terminal.ui.theme.StatusTone
import kotlin.coroutines.resume
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Key Attachment — the deliberate flow for attaching a genuinely NEW key that isn't physically
 * in the cabinet yet, plus registering a brand-new key entirely from the terminal when a fob is
 * physically present at a node the web portal never assigned. Distinct from the silent
 * background auto-scan (`TerminalAdminApp.triggerKeyFobAutoScan`), which only ever touches nodes
 * the web portal has already assigned a key to.
 *
 * Two separately tracked facts per key (corrected this pass — previously conflated into one
 * boolean, which meant the background auto-scan silently making a UID *known* was misread as a
 * human having *attached* it):
 * - **uidKnown** (`isUidKnown`): `managedKeyFobStore` has a locally-stored UID for this key —
 *   can become true with zero human action, via the background auto-scan.
 * - **physicallyAttached** (`isPhysicallyAttached`): a human has completed this screen's own
 *   guided attach flow for this key — only ever set from within this screen.
 *
 * Four steady node states, driven by the cabinet's own two-light-per-node hardware (see
 * `CabinetHardwareController.lightKeyAttachmentOverview`), plus one transient overlay:
 * - OFF: no KeySlot assignment, nothing physically present.
 * - BLUE only: assigned a key, not yet physically attached — tap to run the guided attach flow.
 * - RED only: physically attached — inert, no action.
 * - BOTH on: not assigned, but a card is physically present — tap to register a brand-new key.
 * - **BLINKING RED** (any assigned node): this screen's exit check found the fob missing —
 *   overrides whatever steady state was showing. Leaving this screen is now blocked while any
 *   node is blinking (see the exit-check section) until each is either resolved — "Unlock node
 *   to return key fob" re-locks and relights the correct steady BLUE/RED once the fob is
 *   confirmed reinserted — or explicitly, individually overridden (logged, resets
 *   uidKnown/physicallyAttached since the fob really is still gone, but deliberately leaves the
 *   node still blinking — overriding unblocks navigation, it does not mean the physical problem
 *   went away). There is no dismiss-without-choosing action for this dialog.
 *
 * Also hosts a second, unrelated entry point — "Enroll a key card" — purely for reachability:
 * that flow (`CardEnrollmentScreen`, NFC card-swipe enrollment feeding Return Flow's wrong-slot
 * detection) lost its only entry point when the old TerminalKey-based Guided Key Enrollment
 * screen was removed, and this is the nearest still-reachable screen to put it on. It shares no
 * state, hardware sequence, or mechanism with this screen's own node-based flows above it —
 * rendered in its own clearly separated card, below the node grid.
 */
private const val NODE_POLL_INTERVAL_MILLIS = 3_000L

/** Which on-screen guidance [AttachmentUiPhase.WaitingForFob] should show — driven by
 * [CabinetHardwareController.beginKeyAttachment]'s hardware-tested occupied/empty branch, since
 * the two cases need the operator to do genuinely different physical things. */
private enum class AttachmentWaitGuidance {
    /** Brief, shown only until the hardware reports which branch this node is in. */
    CHECKING,
    EMPTY_SLOT,
    OCCUPIED_PULL_AND_REINSERT,
    OCCUPIED_REINSERT,
}

private sealed interface AttachmentUiPhase {
    data object Idle : AttachmentUiPhase
    data class RegisteringKey(val nodeAddress: Int) : AttachmentUiPhase
    data class OpeningDoor(val nodeAddress: Int) : AttachmentUiPhase
    data class Unlocking(val nodeAddress: Int) : AttachmentUiPhase
    data class WaitingForFob(val nodeAddress: Int, val guidance: AttachmentWaitGuidance) : AttachmentUiPhase
    data class Saving(val nodeAddress: Int) : AttachmentUiPhase
    /** [uidChangedWarning]: the occupied case's reinserted fob had a different UID than what was
     * removed — never blocks completion, only surfaced as a warning (see beginKeyAttachment). */
    data class Done(val nodeAddress: Int, val uidChangedWarning: Boolean) : AttachmentUiPhase
    data class Failed(val nodeAddress: Int, val message: String) : AttachmentUiPhase
}

private sealed interface NodeRowState {
    val nodeAddress: Int

    data class NeedsAttachment(override val nodeAddress: Int, val key: ManagedKey) : NodeRowState
    data class Attached(override val nodeAddress: Int, val key: ManagedKey) : NodeRowState
    data class AvailableForRegistration(override val nodeAddress: Int, val uidHex: String) : NodeRowState
}

/** Result of attempting to register a brand-new key from the terminal — kept separate from a
 * bare nullable so the caller's actual failure message (network error, 403 wrong-token-scope,
 * etc.) reaches the screen instead of being collapsed to a generic string. */
sealed interface RegisterNewKeyResult {
    data class Success(val key: ManagedKey) : RegisterNewKeyResult
    data class Failed(val message: String) : RegisterNewKeyResult
}

@Composable
fun KeyAttachmentScreen(
    padding: PaddingValues,
    configuredSlotCount: Int,
    keys: List<ManagedKey>,
    initialSlots: List<KeySlot>,
    isUidKnown: (managedKeyId: String) -> Boolean,
    isPhysicallyAttached: (managedKeyId: String) -> Boolean,
    /** Whether the currently signed-in session holds a real per-user server token (as opposed
     * to only a TERMINAL_DEVICE pairing token, or a purely local NFC/fingerprint/face session) —
     * new-key registration needs this; the existing node-attachment flow below does not. */
    serverLinked: Boolean,
    notice: String?,
    onBack: () -> Unit,
    onLightOverview: (
        needsAttachment: List<Int>,
        alreadyAttached: List<Int>,
        availableForRegistration: List<Int>,
        onReady: () -> Unit,
        onFailure: (String) -> Unit,
    ) -> Unit,
    onClearOverview: (List<Int>) -> Unit,
    onScanNodes: (
        nodeAddresses: List<Int>,
        onNodeResult: (nodeAddress: Int, result: KeyFobScanResult) -> Unit,
        onSweepComplete: () -> Unit,
    ) -> Unit,
    /** Part 2 — door pop on node selection: checks door status, ejects only if not already open.
     * The door is deliberately left open across multiple attachments in one screen visit. */
    onEnsureDoorOpen: (onReady: () -> Unit, onFailure: (String) -> Unit) -> Unit,
    /** Part 3 exit check, step 1 — read-only, never ejects. */
    onCheckDoorStatus: (onResult: (isOpen: Boolean) -> Unit, onFailure: (String) -> Unit) -> Unit,
    /** Hardware-tested bug fix (see CabinetHardwareController.beginKeyAttachment's doc comment):
     * [onSlotState] reports whether this node was already occupied right after unlock (purely
     * for guidance text — same call for both an empty blue node and an already-occupied amber
     * node); [onRemovalConfirmed] reports the occupied case's remove→reinsert transition;
     * [onAttached]'s second parameter flags a reinserted-fob UID that differs from what was
     * there before removal. */
    onBeginAttachment: (
        nodeAddress: Int,
        onSlotState: (occupied: Boolean) -> Unit,
        onRemovalConfirmed: () -> Unit,
        onAttached: (rawUidHex: String, uidChangedFromPrevious: Boolean) -> Unit,
        onTimeout: () -> Unit,
        onFailure: (String) -> Unit,
    ) -> Unit,
    onCancelAttachment: (nodeAddress: Int) -> Unit,
    /** On success, the caller marks BOTH uidKnown (already true in practice) AND
     * physicallyAttached (the new, human-action-gated fact) true. */
    onSaveAttachment: suspend (nodeAddress: Int, managedKeyId: String, rawUidHex: String) -> Boolean,
    /** Physically flips the node's own indicator from blue to red once attached — the on-screen
     * card transitions live in the same moment; this keeps the real LED in sync with it rather
     * than leaving the cabinet's own light where beginKeyAttachment left it (off, not red). */
    onLightAttachedNode: (nodeAddress: Int) -> Unit,
    onPollSlots: suspend () -> List<KeySlot>?,
    /** POST /v1/admin/keys then POST /v1/admin/key-slots, pinned to [nodeAddress] (not
     * lowest-unused-node logic — the node is fixed by where the physical fob already is). No
     * offline queue: a live failure (network or otherwise) is surfaced as
     * [RegisterNewKeyResult.Failed] and left for the operator to retry, never silently queued. */
    onRegisterNewKey: suspend (nodeAddress: Int, uidHex: String, displayName: String) -> RegisterNewKeyResult,
    /** Part 3, step 3 — persists a locally-logged missing-fob event for one node, regardless of
     * whether the operator later dismisses or explicitly acknowledges the on-screen notice. */
    onLogMissingFob: (nodeAddress: Int, managedKeyId: String?) -> Unit,
    /** Part 1/3 — starts the transient red-blink overlay for these nodes. */
    onSetMissingFobBlink: (nodeAddresses: List<Int>) -> Unit,
    /** Called when a missing fob is explicitly overridden (see [onOverrideMissingFob]) — clears
     * both tracked facts for this key (uidKnown and physicallyAttached) so the node correctly
     * re-renders BLUE ("needs (re)attachment") the next time this screen is opened, rather than
     * still claiming a fact that's no longer true. Not called on a successful resolve — there the
     * fob really is confirmed back, so the facts stay correct as they were. */
    onResetMissingKeyState: (managedKeyId: String) -> Unit,
    /** Mandatory exit-gate resolution — "Unlock node to return key fob": unlocks this node and
     * waits for the fob to be reinserted, reusing the same wait-for-reinsertion pattern the
     * attach flow's occupied-slot case uses (see CabinetHardwareController.resolveMissingFob's
     * doc comment). Replaces a first-pass dismissible acknowledge-and-relight mechanism, removed
     * this pass since this dialog is no longer dismissible without resolving or overriding. */
    onResolveMissingFob: (
        nodeAddress: Int,
        wasPhysicallyAttached: Boolean,
        onResolved: () -> Unit,
        onTimeout: () -> Unit,
        onFailure: (String) -> Unit,
    ) -> Unit,
    onCancelResolveMissingFob: (nodeAddress: Int) -> Unit,
    /** The only other way to clear a still-missing node from the mandatory exit gate — logs a new
     * distinct audit event (never silent) and lets the operator leave without physically
     * resolving it. Does not touch the node's blink; the physical problem is still real. */
    onOverrideMissingFob: (nodeAddress: Int, managedKeyId: String?) -> Unit,
    /** Unrelated to fob attachment above — see this file's doc comment. Opens
     * CardEnrollmentScreen locked to the Key category, unscoped to any personnel record. */
    onOpenKeyCardEnrollment: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var slots by remember { mutableStateOf(initialSlots) }
    var phase by remember { mutableStateOf<AttachmentUiPhase>(AttachmentUiPhase.Idle) }
    var discoveredAvailable by remember { mutableStateOf<Map<Int, String>>(emptyMap()) }
    // Keys registered from this screen this session — merged with the caller's own `keys` list
    // so a just-registered key renders immediately, without waiting for the next Bootstrap/
    // Download to bring its real KeySlot back down.
    var locallyRegisteredKeys by remember { mutableStateOf<Map<Int, ManagedKey>>(emptyMap()) }
    var pendingRegistration by remember { mutableStateOf<NodeRowState.AvailableForRegistration?>(null) }
    var newKeyName by remember { mutableStateOf("") }
    // Attachments completed within this screen, this session — combined with the caller's own
    // isPhysicallyAttached() so a just-attached node flips blue → red immediately (no polling
    // needed for this case), without waiting for this screen's own slot-polling loop to notice.
    var justAttachedKeyIds by remember { mutableStateOf(setOf<String>()) }
    // Exit flow (mandatory, see this file's exit-check functions below): true from the moment
    // Back is pressed until onBack() actually fires — guards against a second Back press
    // restarting the whole flow mid-dialog.
    var exitFlowActive by remember { mutableStateOf(false) }
    // Narrower flag, true only during the brief initial scan — just drives the "Checking…" notice
    // text, distinct from exitFlowActive which stays true through the mandatory dialogs too.
    var exitScanning by remember { mutableStateOf(false) }
    var missingFobNodes by remember { mutableStateOf<List<Int>>(emptyList()) }
    // Which node (at most one — the electromagnet is one-at-a-time hardware, and the UI enforces
    // that even before the hardware guard would) currently has an unlock-and-wait in progress.
    var resolvingNode by remember { mutableStateOf<Int?>(null) }
    // Nodes whose last resolve attempt timed out — informational only, cleared the moment a new
    // attempt starts for that node.
    var resolveTimedOutNodes by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var doorMustCloseDialogVisible by remember { mutableStateOf(false) }

    val effectiveKeys = remember(keys, locallyRegisteredKeys) {
        keys + locallyRegisteredKeys.values.filterNot { local -> keys.any { it.id == local.id } }
    }
    val nodeToManagedKeyId = remember(slots, locallyRegisteredKeys) {
        val fromSlots = slots.mapNotNull { slot -> slot.managedKeyId?.let { slot.nodeAddress to it } }.toMap()
        // Local registration wins if it disagrees with a not-yet-refreshed slots list.
        fromSlots + locallyRegisteredKeys.mapValues { it.value.id }
    }
    val rows = remember(nodeToManagedKeyId, effectiveKeys, justAttachedKeyIds, discoveredAvailable) {
        val assigned = nodeToManagedKeyId.mapNotNull { (nodeAddress, managedKeyId) ->
            val key = effectiveKeys.firstOrNull { it.id == managedKeyId } ?: return@mapNotNull null
            val attached = managedKeyId in justAttachedKeyIds || isPhysicallyAttached(managedKeyId)
            nodeAddress to if (attached) {
                NodeRowState.Attached(nodeAddress, key)
            } else {
                NodeRowState.NeedsAttachment(nodeAddress, key)
            }
        }.toMap()
        val available = discoveredAvailable
            .filterKeys { it !in assigned }
            .map { (nodeAddress, uidHex) -> nodeAddress to NodeRowState.AvailableForRegistration(nodeAddress, uidHex) }
            .toMap()
        (assigned + available).toSortedMap().values.toList()
    }

    // Entry: discover unassigned-but-present fobs first, THEN light the full picture — blue/red
    // for assigned nodes (by physicallyAttached, not uidKnown — see this file's doc comment),
    // both-on for freshly discovered "available" ones. Exit: clear every node in the configured
    // range regardless of which were actually lit, so leaving the screen never leaves a stray
    // light on (except a still-blinking missing-fob node — that blink is a CabinetHardwareController-
    // level concern that intentionally outlives this screen's own composition).
    LaunchedEffect(Unit) {
        val assignedNodes = slots.mapNotNull { it.managedKeyId?.let { _ -> it.nodeAddress } }.toSet()
        val candidateNodes = (1..configuredSlotCount).filterNot { it in assignedNodes }
        val discovered = if (candidateNodes.isEmpty()) {
            emptyMap()
        } else {
            suspendCancellableCoroutine<Map<Int, String>> { cont ->
                val found = mutableMapOf<Int, String>()
                onScanNodes(
                    candidateNodes,
                    { nodeAddress, result ->
                        if (result is KeyFobScanResult.CardRead) found[nodeAddress] = result.uidHex
                    },
                    { cont.resume(found.toMap()) },
                )
            }
        }
        discoveredAvailable = discovered

        val blueNodes = nodeToManagedKeyId.filterKeys { it !in discovered }
            .filterNot { (_, managedKeyId) -> isPhysicallyAttached(managedKeyId) }
            .keys.toList()
        val redNodes = nodeToManagedKeyId.filterKeys { it !in discovered }
            .filter { (_, managedKeyId) -> isPhysicallyAttached(managedKeyId) }
            .keys.toList()
        onLightOverview(blueNodes, redNodes, discovered.keys.toList(), {}, {})
    }
    DisposableEffect(Unit) {
        onDispose { onClearOverview((1..configuredSlotCount).toList()) }
    }

    // Reverse case: a key deleted on web while this screen is open should turn its node back to
    // unlit (or "available" again, if its fob is still physically present). Screen-local polling
    // only — not a general app-wide change, and stops the moment this screen is left.
    LaunchedEffect(Unit) {
        while (true) {
            delay(NODE_POLL_INTERVAL_MILLIS)
            val fresh = onPollSlots()
            if (fresh != null) slots = fresh
        }
    }

    fun startAttachment(nodeAddress: Int, managedKeyId: String) {
        phase = AttachmentUiPhase.OpeningDoor(nodeAddress)
        onEnsureDoorOpen(
            {
                phase = AttachmentUiPhase.Unlocking(nodeAddress)
                onBeginAttachment(
                    nodeAddress,
                    { occupied ->
                        phase = AttachmentUiPhase.WaitingForFob(
                            nodeAddress,
                            if (occupied) AttachmentWaitGuidance.OCCUPIED_PULL_AND_REINSERT else AttachmentWaitGuidance.EMPTY_SLOT,
                        )
                    },
                    { phase = AttachmentUiPhase.WaitingForFob(nodeAddress, AttachmentWaitGuidance.OCCUPIED_REINSERT) },
                    { rawUidHex, uidChanged ->
                        phase = AttachmentUiPhase.Saving(nodeAddress)
                        scope.launch {
                            val ok = onSaveAttachment(nodeAddress, managedKeyId, rawUidHex)
                            phase = if (ok) {
                                justAttachedKeyIds = justAttachedKeyIds + managedKeyId
                                onLightAttachedNode(nodeAddress)
                                AttachmentUiPhase.Done(nodeAddress, uidChanged)
                            } else {
                                AttachmentUiPhase.Failed(nodeAddress, "Fob captured, but saving the enrollment failed.")
                            }
                        }
                    },
                    { phase = AttachmentUiPhase.Failed(nodeAddress, "No fob was attached in time. Node re-locked.") },
                    { message -> phase = AttachmentUiPhase.Failed(nodeAddress, message) },
                )
                // WaitingForFob is shown immediately (unlock + poll are one continuous worker-
                // thread operation from the caller's point of view — there is no separate
                // "unlocked" callback to wait for before the poll begins). CHECKING is a brief
                // placeholder until onSlotState reports which real guidance applies.
                phase = AttachmentUiPhase.WaitingForFob(nodeAddress, AttachmentWaitGuidance.CHECKING)
            },
            { message -> phase = AttachmentUiPhase.Failed(nodeAddress, "Could not open the cabinet door: $message") },
        )
    }

    fun confirmRegistration() {
        val target = pendingRegistration ?: return
        val displayName = newKeyName.trim()
        pendingRegistration = null
        phase = AttachmentUiPhase.RegisteringKey(target.nodeAddress)
        scope.launch {
            when (val result = onRegisterNewKey(target.nodeAddress, target.uidHex, displayName)) {
                is RegisterNewKeyResult.Failed -> {
                    phase = AttachmentUiPhase.Failed(target.nodeAddress, result.message)
                }
                is RegisterNewKeyResult.Success -> {
                    locallyRegisteredKeys = locallyRegisteredKeys + (target.nodeAddress to result.key)
                    // The UID is already known and saved server-side/locally by onRegisterNewKey
                    // — proceed straight into the same physical-attachment sequence BLUE nodes
                    // use (including its own door-open step), so the operator can thread the
                    // actual physical key onto this fob.
                    startAttachment(target.nodeAddress, result.key.id)
                }
            }
        }
    }

    /** Treats a failed door-status read as "still open" (keeps the mandatory poll looping)
     * rather than the old dismissible warning's "assume closed" default — that old default was
     * fine when the operator could always just click through anyway; it would be the wrong
     * failure mode for a gate that no longer has an override. */
    suspend fun checkDoorOpenSuspend(): Boolean = suspendCancellableCoroutine { cont ->
        onCheckDoorStatus({ isOpen -> cont.resume(isOpen) }, { cont.resume(true) })
    }

    suspend fun scanNodesSuspend(nodeAddresses: List<Int>): Map<Int, KeyFobScanResult> =
        suspendCancellableCoroutine { cont ->
            val results = mutableMapOf<Int, KeyFobScanResult>()
            onScanNodes(nodeAddresses, { nodeAddress, result -> results[nodeAddress] = result }, { cont.resume(results.toMap()) })
        }

    // Exit gate, step 2 (last, immediately before actually leaving): the door-closed check now
    // has no override — if it's open, a non-dismissible dialog shows and this same polling loop
    // (reusing NODE_POLL_INTERVAL_MILLIS for consistency with the screen's other polling) is the
    // only way it clears, the moment the door is detected closed. No manual "check again" button
    // needed since closing the door is always the available fix.
    fun proceedPastMissingFobStep() {
        scope.launch {
            if (!checkDoorOpenSuspend()) {
                onBack()
                return@launch
            }
            doorMustCloseDialogVisible = true
            while (checkDoorOpenSuspend()) {
                delay(NODE_POLL_INTERVAL_MILLIS)
            }
            doorMustCloseDialogVisible = false
            onBack()
        }
    }

    // Exit gate, step 1: a missing-fob scan across every node that has EITHER uidKnown or
    // physicallyAttached true. Unlike the door-closed gate, this step runs FIRST — resolving a
    // missing fob via the unlock action may need the door to still be open, so the door-closed
    // check must come last, immediately before actually leaving, not before this step.
    fun attemptExit() {
        if (exitFlowActive) return
        exitFlowActive = true
        exitScanning = true
        scope.launch {
            val scanCandidates = nodeToManagedKeyId
                .filter { (_, managedKeyId) -> isUidKnown(managedKeyId) || isPhysicallyAttached(managedKeyId) }
                .keys.toList()
            val results = if (scanCandidates.isEmpty()) emptyMap() else scanNodesSuspend(scanCandidates)
            val missing = results.filterValues { it is KeyFobScanResult.NothingPresent }.keys.toList()

            // Persisted the moment it's detected, regardless of whether the operator resolves or
            // overrides it afterward via the mandatory dialog below.
            missing.forEach { nodeAddress -> onLogMissingFob(nodeAddress, nodeToManagedKeyId[nodeAddress]) }
            if (missing.isNotEmpty()) onSetMissingFobBlink(missing)

            exitScanning = false
            if (missing.isEmpty()) {
                proceedPastMissingFobStep()
            } else {
                missingFobNodes = missing
                // The dialog below is now mandatory — its own per-node resolve/override actions
                // are what eventually call proceedPastMissingFobStep() once the list is empty.
            }
        }
    }

    fun resolveMissingFobAt(nodeAddress: Int) {
        if (resolvingNode != null) return
        resolvingNode = nodeAddress
        resolveTimedOutNodes = resolveTimedOutNodes - nodeAddress
        val managedKeyId = nodeToManagedKeyId[nodeAddress]
        val wasPhysicallyAttached = managedKeyId?.let(isPhysicallyAttached) == true
        // Same door-first sequencing startAttachment already uses for the attach flow — this
        // node's electromagnet must not engage until the door is confirmed open. Reopening the
        // door here (if it had been closed) is expected and already accounted for by Change 1's
        // door-closed gate, which deliberately runs last, only after every missing node is
        // resolved/overridden — it will simply require the door to be closed again before the
        // operator can finally leave.
        onEnsureDoorOpen(
            {
                onResolveMissingFob(
                    nodeAddress,
                    wasPhysicallyAttached,
                    {
                        resolvingNode = null
                        missingFobNodes = missingFobNodes - nodeAddress
                        if (missingFobNodes.isEmpty()) proceedPastMissingFobStep()
                    },
                    {
                        resolvingNode = null
                        resolveTimedOutNodes = resolveTimedOutNodes + nodeAddress
                    },
                    { resolvingNode = null },
                )
            },
            { resolvingNode = null },
        )
    }

    fun cancelResolvingFob() {
        val nodeAddress = resolvingNode ?: return
        onCancelResolveMissingFob(nodeAddress)
        resolvingNode = null
    }

    /** The only other way to clear a node from the mandatory dialog — explicitly logged
     * (KEY_FOB_MISSING_OVERRIDDEN, distinct from the automatic KEY_FOB_MISSING detection),
     * per-node rather than a single whole-list action so each override's audit entry names
     * exactly which node/key it applies to. Resets the key's tracked facts (same as the old
     * acknowledge flow did on a confirmed-still-missing result) since the fob really is still
     * gone — but deliberately does not touch the node's blink; the physical problem is still
     * real, only navigation is unblocked. */
    fun overrideMissingFob(nodeAddress: Int) {
        val managedKeyId = nodeToManagedKeyId[nodeAddress]
        onOverrideMissingFob(nodeAddress, managedKeyId)
        if (managedKeyId != null) {
            onResetMissingKeyState(managedKeyId)
            justAttachedKeyIds = justAttachedKeyIds - managedKeyId
        }
        missingFobNodes = missingFobNodes - nodeAddress
        resolveTimedOutNodes = resolveTimedOutNodes - nodeAddress
        if (missingFobNodes.isEmpty()) proceedPastMissingFobStep()
    }

    TerminalPage(padding) {
        BackButton(onBack = ::attemptExit, enabled = !exitFlowActive, label = "Back to Super Admin dashboard")
        HeaderCard(
            title = "Key Attachment",
            description = "Blue nodes are assigned a key but have no fob attached yet. Red nodes " +
                "already have one. Blue+red together means a card is present but not registered " +
                "on web yet — tap to register a new key here. Tap a blue node to attach its fob. " +
                "A blinking red node means its fob was found missing when this screen was last left.",
        )
        notice?.let { message -> SuperAdminNoticeCard(message) }
        if (exitScanning) {
            SuperAdminNoticeCard("Checking for missing fobs before leaving…")
        }
        if (!serverLinked) {
            SuperAdminNoticeCard(
                "Sign in with your server password to register a new key from this screen — " +
                    "attaching an already-registered key's fob (blue nodes) doesn't need this.",
            )
        }

        val activePhase = phase
        if (activePhase !is AttachmentUiPhase.Idle) {
            SuperAdminNoticeCard(
                when (activePhase) {
                    is AttachmentUiPhase.RegisteringKey -> "Registering the new key…"
                    is AttachmentUiPhase.OpeningDoor -> "Opening the cabinet door…"
                    is AttachmentUiPhase.Unlocking -> "Unlocking node ${activePhase.nodeAddress}…"
                    is AttachmentUiPhase.WaitingForFob -> when (activePhase.guidance) {
                        AttachmentWaitGuidance.CHECKING -> "Checking node ${activePhase.nodeAddress}…"
                        AttachmentWaitGuidance.EMPTY_SLOT ->
                            "Node ${activePhase.nodeAddress} unlocked — attach the new key's fob now."
                        AttachmentWaitGuidance.OCCUPIED_PULL_AND_REINSERT ->
                            "Node ${activePhase.nodeAddress} unlocked — pull out the fob, attach your key, then reinsert it."
                        AttachmentWaitGuidance.OCCUPIED_REINSERT ->
                            "Fob removed from node ${activePhase.nodeAddress} — reinsert it now to secure it."
                    }
                    is AttachmentUiPhase.Saving -> "Fob detected at node ${activePhase.nodeAddress} — saving…"
                    is AttachmentUiPhase.Done -> if (activePhase.uidChangedWarning) {
                        "Node ${activePhase.nodeAddress} attached and secured — but the reinserted fob's ID differs " +
                            "from before removal. Confirm the correct fob was placed back."
                    } else {
                        "Node ${activePhase.nodeAddress} attached and secured."
                    }
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
                val tone = when (row) {
                    // NORMAL resolves to primaryContainer (this app's blue), ALARM to
                    // errorContainer (red) — mirroring the cabinet's own two lights, not a
                    // severity judgment. ATTENTION (amber) marks the new BOTH-lit state — the
                    // only tone left distinct from the other two on a single-tone StatusRingCard.
                    is NodeRowState.NeedsAttachment -> StatusTone.NORMAL
                    is NodeRowState.Attached -> StatusTone.ALARM
                    is NodeRowState.AvailableForRegistration -> StatusTone.ATTENTION
                }
                val onClick = when {
                    busy -> null
                    row is NodeRowState.NeedsAttachment -> { { startAttachment(row.nodeAddress, row.key.id) } }
                    row is NodeRowState.AvailableForRegistration && serverLinked -> {
                        {
                            pendingRegistration = row
                            newKeyName = ""
                        }
                    }
                    else -> null
                }
                StatusRingCard(tone = tone, onClick = onClick) {
                    when (row) {
                        is NodeRowState.NeedsAttachment -> {
                            Text("Node ${row.nodeAddress} · ${row.key.displayName}", fontWeight = FontWeight.SemiBold)
                            Text("Needs attachment — tap to attach", style = MaterialTheme.typography.bodySmall)
                        }
                        is NodeRowState.Attached -> {
                            Text("Node ${row.nodeAddress} · ${row.key.displayName}", fontWeight = FontWeight.SemiBold)
                            Text("Attached", style = MaterialTheme.typography.bodySmall)
                        }
                        is NodeRowState.AvailableForRegistration -> {
                            Text("Node ${row.nodeAddress} · Unregistered fob detected", fontWeight = FontWeight.SemiBold)
                            Text(
                                if (serverLinked) "Tap to register a new key here" else "Sign in with a server password to register",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
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

    val target = pendingRegistration
    if (target != null) {
        AlertDialog(
            onDismissRequest = { pendingRegistration = null },
            title = { Text("Register new key") },
            text = {
                Column {
                    Text("A card is present at node ${target.nodeAddress} with no matching web record. Name this key to register it.")
                    OutlinedTextField(
                        value = newKeyName,
                        onValueChange = { newKeyName = it },
                        label = { Text("Key name") },
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                Button(onClick = ::confirmRegistration, enabled = newKeyName.trim().length >= 2) {
                    Text("Register")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingRegistration = null }) {
                    Text("Cancel")
                }
            },
        )
    }

    // Exit gate, step 1 — mandatory missing-fob resolution. No dismiss action at all: every node
    // must either resolve (fob confirmed reinserted) or be explicitly, individually overridden
    // before this dialog goes away. The missing-fob audit log entries were already persisted the
    // moment they were detected (see attemptExit), regardless of how each node is eventually
    // cleared here.
    if (missingFobNodes.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Missing fobs must be resolved") },
            text = {
                Column {
                    Text(
                        "A fob previously known at these nodes could not be found. Unlock a node " +
                            "and return the physical fob, or override if it can't be resolved right now.",
                    )
                    missingFobNodes.sorted().forEach { nodeAddress ->
                        val managedKeyId = nodeToManagedKeyId[nodeAddress]
                        val keyName = effectiveKeys.firstOrNull { it.id == managedKeyId }?.displayName
                        Column {
                            Text(
                                "Node $nodeAddress" + (keyName?.let { " · $it" } ?: ""),
                                fontWeight = FontWeight.SemiBold,
                            )
                            if (resolvingNode == nodeAddress) {
                                Text(
                                    "Waiting for the fob to be reinserted…",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                TextButton(onClick = ::cancelResolvingFob) {
                                    Text("Cancel")
                                }
                            } else {
                                if (nodeAddress in resolveTimedOutNodes) {
                                    Text(
                                        "Timed out waiting for the fob — try again or override.",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                Button(
                                    onClick = { resolveMissingFobAt(nodeAddress) },
                                    enabled = resolvingNode == null,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text("Unlock node to return key fob")
                                }
                                TextButton(
                                    onClick = { overrideMissingFob(nodeAddress) },
                                    enabled = resolvingNode == null,
                                ) {
                                    Text("Override — acknowledge as unresolved")
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {},
        )
    }

    // Exit gate, step 2 (last, immediately before actually leaving) — mandatory door-closed
    // check, no override. Auto-dismisses itself the moment proceedPastMissingFobStep's polling
    // loop detects the door closed; no manual "check again" button, since closing the door is
    // always the available fix.
    if (doorMustCloseDialogVisible) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Close the cabinet door") },
            text = {
                Text(
                    "The cabinet door must be closed before leaving this screen. Close it now — " +
                        "this will continue automatically once it's detected closed.",
                )
            },
            confirmButton = {},
        )
    }
}
