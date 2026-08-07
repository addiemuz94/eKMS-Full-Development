package com.ekms.terminal.ui.returnflow

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ekms.shared.api.KeyCheckoutStatus
import com.ekms.shared.api.UpdateKeyCheckoutRequest
import com.ekms.shared.domain.AuditEventType
import com.ekms.shared.domain.CardUidMatch
import com.ekms.shared.domain.CardUidResolver
import com.ekms.shared.domain.KeySlot
import com.ekms.shared.domain.ManagedKey
import com.ekms.shared.domain.RecordType
import com.ekms.terminal.data.StoreResult
import com.ekms.terminal.data.TerminalAdminStore
import com.ekms.terminal.data.TerminalApiClient
import com.ekms.terminal.data.TerminalCabinetSettings
import com.ekms.terminal.data.TerminalCheckoutRecord
import com.ekms.terminal.data.TerminalCheckoutStore
import com.ekms.terminal.hardware.CabinetHardwareController
import com.ekms.terminal.hardware.EncryptedUidEnrollmentStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Return Flow rewrite (Tier 1 — see CLAUDE_TERMINAL.md for the full before/after once Tier 4
 * lands). Unified session state machine, replacing the old `TerminalAdminApp`-local `ReturnFlow`
 * sealed interface plus `CabinetHardwareController`'s `returnMonitoring`/`returnSessionMonitoring`/
 * `allowReturnSessionReentry` guard trio, which were three independently-mutated sources of truth
 * for the same underlying question ("is a return session/node-cycle currently active") — the
 * documented root cause class behind the fob-scan-doesn't-respond bug this rewrite targets, even
 * though the live repro itself was never captured.
 *
 * States ([ReturnSession]): [ReturnSession.Closed] (no session) -> [ReturnSession.AwaitingCertification]
 * (only if Key Return Certification is enabled) -> [ReturnSession.Unlocking] (hardware
 * blue-light/engage/eject sequence in flight) -> [ReturnSession.AwaitingInsertion] (node unlocked,
 * waiting for the fob, wrong-key tracked as a field on this same state, not a separate variant —
 * an active node cycle must keep rendering/functioning unaffected by a wrong-key condition
 * elsewhere) -> [ReturnSession.Waiting] (session open, no node active, listening for the next
 * scan) -> back to a fresh cycle, or to [ReturnSession.Closed] only via a physically confirmed
 * door-close. This exact "every node-cycle terminal outcome (success/abandoned/failed) returns to
 * Waiting, never straight to Closed" invariant is carried forward unchanged from the pre-rewrite
 * code, including its one quirk: a hardware fault on a session's very first-ever scan (door never
 * actually opened) still lands in Waiting/arms the reader exactly like a real continuing session
 * would — not a new behavior, a faithfully-preserved one; flag separately if that's ever worth
 * fixing.
 *
 * Cross-cutting, session-scoped and orthogonal to the state above (a wrong-slot condition or a
 * door-close reminder at the session level must never interrupt an unrelated, legitimately active
 * node cycle — same reasoning the pre-rewrite hard-block design already established correctly):
 * [wrongSlotBlockedNodes], [doorCloseWarningActive], [moreKeyReturnHoldUntilEpochMillis].
 *
 * [sessionId] (generated once per `Closed -> *` transition) and each node cycle's own [attemptId]
 * (generated once per fob scan) are this rewrite's TOCTOU-safety identities — any delayed
 * callback that mutates session state re-checks its captured id against the *live* state
 * immediately before acting, never trusting a closure variable alone. This generalizes the
 * pre-existing, already-hardware-confirmed-correct `attemptId`-keyed fix for the
 * duplicate-abandonment bug (see CLAUDE_TERMINAL.md's Return Flow rework history) to the
 * session-level timers, which had no equivalent guard before this rewrite.
 *
 * Audio is deliberately NOT triggered from this class. It only exposes state
 * ([ReturnSession], [doorCloseWarningActive], [moreKeyReturnHoldUntilEpochMillis]) for whichever
 * layer owns the (Tier 2-consolidated, single shared) `AudioFeedbackController` instance to react
 * to — matching the existing separation where flow logic and audio triggering are already two
 * different layers, not merging them into a third concern here.
 *
 * Deny-list reader-arming (this rewrite's fix for the fob-scan entry point): [sessionReaderArmed]
 * is `true` in exactly [ReturnSession.Closed] and [ReturnSession.Waiting] — every other state
 * actively needs exclusive reader access (certification pending, hardware mid-sequence) and is
 * therefore excluded. New states default to armed unless explicitly listed here, the opposite of
 * the old allow-list `cardReaderShouldBeActive` boolean, which defaulted new states to *not*
 * armed unless someone remembered to extend the OR — exactly the shape that produced the
 * session-reentry bug this session's docs already describe once. The caller (`TerminalAdminApp`,
 * Tier 3) ANDs this with the separate, unrelated "is the login screen showing" condition that
 * arms the same physical reader for personnel-card taps — that's a different reason to listen,
 * not this controller's concern.
 */
sealed interface ReturnSession {
    data object Closed : ReturnSession

    data class AwaitingCertification(
        val sessionId: Long,
        val matchedKey: ManagedKey?,
        val matchedSlot: KeySlot?,
        val attemptId: Long,
        val abandonAtEpochMillis: Long?,
        val loginError: String? = null,
    ) : ReturnSession

    /** Hardware sequence in flight: blue light on, engage electromagnet, ensure door open. */
    data class Unlocking(
        val sessionId: Long,
        val nodeAddress: Int,
        val matchedKey: ManagedKey?,
        val matchedSlot: KeySlot?,
        val attemptId: Long,
        val abandonAtEpochMillis: Long?,
    ) : ReturnSession

    /** [nodeAddress] is null only for the pre-existing hardware-free testing convenience (no
     * real card-UID match resolved a slot) — see [ReturnSessionController.resolveTarget]. */
    data class AwaitingInsertion(
        val sessionId: Long,
        val nodeAddress: Int?,
        val matchedKey: ManagedKey?,
        val matchedSlot: KeySlot?,
        val attemptId: Long,
        val abandonAtEpochMillis: Long?,
        val checkoutSummary: String?,
        val wrongKeyPresent: Boolean = false,
        val wrongKeyDisplayName: String? = null,
    ) : ReturnSession

    data class Waiting(val sessionId: Long) : ReturnSession

    /** Shown for [ReturnSessionController]'s `EXIT_AUTO_RETURN_MILLIS` (3s) after a node cycle
     * ends in `"ABANDONED"` or `"FAILED"` — never `"SUCCESS"`/`"NO_NODE_TEST"`, which go straight
     * to [Waiting] with no message, matching the pre-rewrite screen's own timing exactly. The
     * operator needs a moment to read what happened before the session drops back to "scan the
     * next key." **Correction to this rewrite's own Tier 1 pass**, caught while wiring the screen
     * in Tier 3: Tier 1 initially transitioned Abandoned/Failed straight to [Waiting] with no
     * message at all — a real, if brief, dropped requirement, not present in the pre-rewrite
     * `TerminalKeyReturnScreen`'s own `Failed`/`Abandoned` stages (each shown for 3s before
     * `onNodeCycleComplete`). Not shown for [AwaitingCertification]'s own 20s-ceiling abandonment
     * — the pre-rewrite code didn't show one there either (that abandonment's `LaunchedEffect`
     * called `onNodeCycleComplete` directly), so this state's transitions never originate there. */
    data class NodeOutcomeMessage(
        val sessionId: Long,
        val outcome: String,
        val matchedKey: ManagedKey?,
        val failureMessage: String? = null,
    ) : ReturnSession
}

/**
 * Owns the Return Flow state machine end to end: fob-scan entry, certification-login gating,
 * driving [CabinetHardwareController]'s Return-side hardware calls, wrong-key/wrong-slot
 * tracking, session-level door-close/more-key-return timing, and the same audit/checkout-store/
 * backend-sync calls the pre-rewrite `TerminalAdminApp.handleReturnFlowOutcome` made, from the
 * same logical points (insertion success / abandonment / failure). Constructed once via
 * `remember` in `TerminalAdminApp` (Tier 3) — a plain class holding Compose `mutableStateOf`
 * fields, the same shape `CabinetHardwareController` itself already uses for its own published
 * state, not a `@Composable` function.
 *
 * Every "live" piece of app state this controller needs (personnel list, retrieval keys/slots,
 * taken-key set, cabinet settings) is injected as an accessor lambda, not copied in once — the
 * same live-supplier pattern `beginReturnSessionWrongSlotSweep`'s `candidateNodeAddresses`/
 * `activeNodeAddress` already established, since these change over the session's lifetime and a
 * frozen snapshot would go stale.
 */
class ReturnSessionController(
    private val hardwareController: CabinetHardwareController,
    private val store: TerminalAdminStore,
    private val checkoutStore: TerminalCheckoutStore,
    private val apiClient: TerminalApiClient,
    private val scope: CoroutineScope,
    private val personnelCardStore: EncryptedUidEnrollmentStore,
    private val keyCardStore: EncryptedUidEnrollmentStore,
    private val cabinetSettings: () -> TerminalCabinetSettings,
    private val retrievalKeys: () -> List<ManagedKey>,
    private val retrievalSlots: () -> List<KeySlot>,
    private val takenKeyIds: () -> Set<String>,
    private val actorUserId: () -> String?,
    private val personnelDisplayName: (userId: String) -> String?,
    /** Replaces the old `takenKeyIds = takenKeyIds - returnedKey.id` line from the pre-rewrite
     * `handleReturnFlowOutcome`'s Success branch — a real gap in this rewrite's own Tier 1 pass,
     * caught while cross-checking against the original during Tier 3: `takenKeyIds` is
     * `TerminalAdminApp`-owned (the retrieval grid's "currently taken" bookkeeping), so this
     * controller needs a write-back callback, not just the existing read-only `takenKeyIds`
     * accessor. */
    private val onKeyReturned: (keyId: String) -> Unit,
    /** Replaces the old inline `notice = "..."` assignment for the wrong-slot block-gate
     * rejection message — the only user-facing message this controller itself originates. */
    private val onNotice: (String) -> Unit,
    /** Replaces `returnToLoginAfterSessionComplete()` — fired once the completion message has
     * shown and the session has fully torn down. Must not disconnect the hardware link. */
    private val onSessionEnded: () -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    var state: ReturnSession by mutableStateOf(ReturnSession.Closed)
        private set

    /** Cross-node wrong-slot hard-block (unchanged design, Aug 2026) — orthogonal to [state],
     * see class doc. */
    var wrongSlotBlockedNodes: Set<Int> by mutableStateOf(emptySet())
        private set

    /** Session-level "please close the door" cyclic reminder driver — set once the
     * Door-Close Warning Time expires (via [CabinetHardwareController.beginReturnSessionDoorMonitor])
     * or once [moreKeyReturnHoldUntilEpochMillis] elapses uninterrupted; cleared by a fresh scan
     * or session end. Whoever owns the shared audio instance (Tier 2/3) reacts to this. */
    var doorCloseWarningActive: Boolean by mutableStateOf(false)
        private set

    /** Non-null for the 5s window after a confirmed insertion, during which a fresh scan
     * cancels it and anything else lets it elapse into [doorCloseWarningActive]. The transition
     * from null to non-null is itself the "play MORE_KEY_RETURN once" signal for whoever owns
     * the shared audio instance — this class does not play audio itself, see class doc. */
    var moreKeyReturnHoldUntilEpochMillis: Long? by mutableStateOf(null)
        private set

    var returnedKeyNames: List<String> by mutableStateOf(emptyList())
        private set

    /** True for a brief window between the door being confirmed physically closed and the
     * actual session teardown/[onSessionEnded] call — lets `ReturnSessionScreen` show a
     * completion message first, same as the pre-rewrite `returnSessionCompletionMessageActive`. */
    var sessionComplete: Boolean by mutableStateOf(false)
        private set

    /** Deny-list reader-arming — see class doc. */
    val sessionReaderArmed: Boolean
        get() = state is ReturnSession.Closed || state is ReturnSession.Waiting

    /** Generation counter guarding the two delayed one-shots that have no more specific id of
     * their own to re-check against ([NO_NODE_AUTO_COMPLETE_MILLIS], [MORE_KEY_RETURN_HOLD_MILLIS])
     * — bumped on every fresh scan and on session end, so a superseded timer's callback becomes a
     * no-op instead of acting on stale state. The certification-abandonment and session-door-close
     * timers use their own [ReturnSession.AwaitingCertification.attemptId]/[ReturnSession.Waiting.sessionId]
     * instead, since those already carry a more specific identity to re-check. */
    private var pendingTimerGeneration: Long = 0L

    /** Entry point for a fob scan that [CardUidResolver] has already resolved to
     * [CardUidMatch.Key] — replaces the old `startKeyCardReturn`. Both params null is the
     * pre-existing hardware-free testing convenience (see [resolveTarget]). */
    fun onKeyCardScanned(matchedKey: ManagedKey? = null, matchedSlot: KeySlot? = null) {
        val blocked = wrongSlotBlockedNodes
        if (blocked.isNotEmpty()) {
            val nodeList = blocked.sorted().joinToString(", ")
            Log.d(LOG_TAG, "ReturnFlowDiag: session=${sessionIdOf(state) ?: "none"} block gate rejected scan, blockedNodes=$nodeList")
            onNotice("Remove the wrong key from node $nodeList before scanning another key.")
            return
        }

        val isFreshSession = state is ReturnSession.Closed
        val sessionId = if (isFreshSession) System.nanoTime() else sessionIdOf(state) ?: System.nanoTime()
        if (isFreshSession) returnedKeyNames = emptyList()

        // Per-scan reset — mirrors the pre-rewrite startKeyCardReturn exactly: every scan, first
        // or subsequent, resets the session-level Door-Close Warning Time and cancels any
        // in-flight "more keys to return?" hold, since a new scan means the operator is back at
        // the cabinet.
        hardwareController.resetReturnSessionDoorCloseWarning()
        doorCloseWarningActive = false
        moreKeyReturnHoldUntilEpochMillis = null
        pendingTimerGeneration += 1

        val attemptId = System.nanoTime()
        val abandonAtEpochMillis = if (matchedKey != null && matchedSlot != null) {
            System.currentTimeMillis() + CabinetHardwareController.RETURN_FLOW_ABANDONMENT_TIMEOUT_MILLIS
        } else {
            null
        }

        if (cabinetSettings().keyReturnCertificationEnabled) {
            transitionTo(
                ReturnSession.AwaitingCertification(sessionId, matchedKey, matchedSlot, attemptId, abandonAtEpochMillis),
                trigger = "fob scan, certification required, freshSession=$isFreshSession",
            )
            scheduleCertificationAbandonment(sessionId, attemptId, abandonAtEpochMillis)
        } else {
            beginUnlockSequence(sessionId, matchedKey, matchedSlot, attemptId, abandonAtEpochMillis)
        }
    }

    /** Attempts the Key Return Certification login for the current [ReturnSession.AwaitingCertification]
     * attempt — replaces the inline `store.authenticate(...)` call from the old render-dispatch
     * closure. No-ops if the state has already moved on (e.g. the 20s ceiling fired first). */
    fun attemptCertificationLogin(username: String, password: String) {
        val current = state as? ReturnSession.AwaitingCertification ?: return
        when (val result = store.authenticate(username, password)) {
            is StoreResult.Success -> beginUnlockSequence(
                current.sessionId,
                current.matchedKey,
                current.matchedSlot,
                current.attemptId,
                current.abandonAtEpochMillis,
            )

            is StoreResult.Error -> transitionTo(
                current.copy(loginError = result.message),
                trigger = "certification login failed",
            )
        }
    }

    private fun beginUnlockSequence(
        sessionId: Long,
        matchedKey: ManagedKey?,
        matchedSlot: KeySlot?,
        attemptId: Long,
        abandonAtEpochMillis: Long?,
    ) {
        // Re-resolved here, not reused from onKeyCardScanned's own params, matching the
        // pre-rewrite behavior exactly: certification success re-ran resolveNodeActiveState
        // rather than trusting whatever was captured at swipe time (a fresh checkoutSummaryFor
        // read, and a fresh heuristic pass, for however long certification login took).
        val (resolvedKey, resolvedSlot) = resolveTarget(matchedKey, matchedSlot)
        val nodeAddress = resolvedSlot?.nodeAddress

        if (nodeAddress == null || abandonAtEpochMillis == null) {
            // Hardware-free testing convenience only — see class doc on AwaitingInsertion.nodeAddress.
            // No audit log, no checkout close: this was never a real return, matching the
            // pre-rewrite TerminalKeyReturnScreen's identical fallback exactly.
            transitionTo(
                ReturnSession.AwaitingInsertion(
                    sessionId, null, resolvedKey, resolvedSlot, attemptId, null,
                    resolvedKey?.let(::checkoutSummaryFor),
                ),
                trigger = "no node to unlock (test fallback)",
            )
            val generation = ++pendingTimerGeneration
            mainHandler.postDelayed(
                {
                    if (pendingTimerGeneration == generation) handleNodeCycleOutcome(sessionId, "NO_NODE_TEST")
                },
                NO_NODE_AUTO_COMPLETE_MILLIS,
            )
            return
        }

        transitionTo(
            ReturnSession.Unlocking(sessionId, nodeAddress, resolvedKey, resolvedSlot, attemptId, abandonAtEpochMillis),
            trigger = "beginning hardware unlock",
        )
        hardwareController.beginReturnNodeCycle(
            nodeAddress = nodeAddress,
            onNodeUnlocked = {
                val checkoutSummary = resolvedKey?.let(::checkoutSummaryFor)
                transitionTo(
                    ReturnSession.AwaitingInsertion(
                        sessionId, nodeAddress, resolvedKey, resolvedSlot, attemptId, abandonAtEpochMillis, checkoutSummary,
                    ),
                    trigger = "node unlocked",
                )
                hardwareController.beginReturnSessionDoorMonitor(
                    doorCloseWarningSeconds = cabinetSettings().doorCloseWarningTimeSeconds,
                    onWarningExpired = {
                        doorCloseWarningActive = true
                        store.logEvent(
                            AuditEventType.KEY_RETURN_DOOR_LEFT_OPEN,
                            actorUserId(),
                            RecordType.KEY,
                            null,
                            "Door-Close Warning Time expired during an open return session; the door is still open.",
                        )
                        Log.d(LOG_TAG, "ReturnFlowDiag: session=$sessionId session Door-Close Warning Time expired (warning only, session continues)")
                    },
                    onSessionDoorClosed = { handleSessionDoorClosed(sessionId) },
                    onFailure = { message -> handleNodeFailure(sessionId, resolvedKey, message) },
                )
                hardwareController.beginReturnSessionWrongSlotSweep(
                    candidateNodeAddresses = {
                        retrievalSlots().mapNotNull { slot ->
                            slot.nodeAddress.takeIf { slot.managedKeyId != null && slot.managedKeyId in takenKeyIds() }
                        }
                    },
                    activeNodeAddress = { (state as? ReturnSession.AwaitingInsertion)?.nodeAddress },
                    resolveKeyFobUid = ::resolveKeyFobUid,
                    onWrongSlotDetected = { wrongNodeAddress, confirmedKey ->
                        Log.d(LOG_TAG, "ReturnFlowDiag: session=$sessionId block gate - node=$wrongNodeAddress confirmedKey=$confirmedKey now blocked")
                        val wasEmpty = wrongSlotBlockedNodes.isEmpty()
                        wrongSlotBlockedNodes = wrongSlotBlockedNodes + wrongNodeAddress
                        if (wasEmpty) hardwareController.resetReturnSessionDoorCloseWarning()
                    },
                    onWrongSlotCleared = { clearedNodeAddress ->
                        Log.d(LOG_TAG, "ReturnFlowDiag: session=$sessionId block gate - node=$clearedNodeAddress cleared")
                        wrongSlotBlockedNodes = wrongSlotBlockedNodes - clearedNodeAddress
                    },
                    onFailure = { message -> handleNodeFailure(sessionId, resolvedKey, message) },
                )
                hardwareController.pollForKeyInsertion(
                    nodeAddress = nodeAddress,
                    abandonAtEpochMillis = abandonAtEpochMillis,
                    isExpectedKey = { uid -> keyCardStore.recordIdFor(uid) == resolvedKey?.id },
                    resolveKeyIdForUid = keyCardStore::recordIdFor,
                    onInserted = { handleInsertionSuccess(sessionId, attemptId, resolvedKey) },
                    onWrongKeyInserted = { resolvedKeyId ->
                        (state as? ReturnSession.AwaitingInsertion)?.let { current ->
                            Log.d(LOG_TAG, "ReturnFlowDiag: session=$sessionId node=$nodeAddress attemptId=$attemptId WRONG KEY inserted, resolvedKeyId=${resolvedKeyId ?: "unresolved"}")
                            transitionTo(
                                current.copy(wrongKeyPresent = true, wrongKeyDisplayName = resolvedKeyId?.let(::resolveKeyDisplayName)),
                                trigger = "wrong key inserted",
                            )
                        }
                    },
                    onWrongKeyRemoved = {
                        (state as? ReturnSession.AwaitingInsertion)?.let { current ->
                            Log.d(LOG_TAG, "ReturnFlowDiag: session=$sessionId node=$nodeAddress attemptId=$attemptId wrong key removed, abandonment window reset")
                            transitionTo(
                                current.copy(wrongKeyPresent = false, wrongKeyDisplayName = null),
                                trigger = "wrong key removed",
                            )
                        }
                    },
                    onLouderBeepThreshold = {
                        // Cyclic take/return/close-door audio pattern already plays "please
                        // insert the key" from unlock with no grace period — this 5s hardware
                        // threshold no longer drives any audio, matching the pre-rewrite
                        // behavior exactly (see TerminalKeyReturnScreen's identical no-op here).
                    },
                    onAbandoned = { handleNodeAbandoned(sessionId, resolvedKey) },
                    onFailure = { message -> handleNodeFailure(sessionId, resolvedKey, message) },
                )
            },
            onFailure = { message -> handleNodeFailure(sessionId, resolvedKey, message) },
        )
    }

    private fun handleInsertionSuccess(sessionId: Long, attemptId: Long, matchedKey: ManagedKey?) {
        val actor = actorUserId()
        matchedKey?.let { key ->
            onKeyReturned(key.id)
            val closedRecord = checkoutStore.close(key.id)
            returnedKeyNames = returnedKeyNames + key.displayName
            closeBackendCheckout(key, closedRecord, actor)
        }
        store.logEvent(AuditEventType.KEY_RETURNED, actor, RecordType.KEY, matchedKey?.id)
        Log.d(LOG_TAG, "ReturnFlowDiag: session=$sessionId attemptId=$attemptId correct key confirmed, SUCCESS")
        handleNodeCycleOutcome(sessionId, "SUCCESS")
    }

    /** Phase 5 backend close-out — byte-for-byte the same call/field mapping as the pre-rewrite
     * `handleReturnFlowOutcome`'s Success branch, fired non-blocking; a failure here never
     * blocks/undoes the physical return, only logs KEY_CHECKOUT_SYNC_FAILED locally. */
    private fun closeBackendCheckout(key: ManagedKey, closedRecord: TerminalCheckoutRecord?, actor: String?) {
        val backendCheckoutId = closedRecord?.backendCheckoutId
        val backendRevision = closedRecord?.backendRevision
        val backendDueAtEpochMillis = closedRecord?.backendDueAtEpochMillis
        if (backendCheckoutId != null && backendRevision != null && backendDueAtEpochMillis != null) {
            val returnedAtEpochMillis = System.currentTimeMillis()
            scope.launch {
                try {
                    apiClient.closeKeyCheckout(
                        backendCheckoutId,
                        UpdateKeyCheckoutRequest(
                            dueAtEpochMillis = backendDueAtEpochMillis,
                            status = KeyCheckoutStatus.RETURNED,
                            returnedAtEpochMillis = returnedAtEpochMillis,
                            isEmergency = closedRecord.backendIsEmergency,
                            emergencyWindowEndsAtEpochMillis = closedRecord.backendEmergencyWindowEndsAtEpochMillis,
                            expectedRevision = backendRevision,
                        ),
                    )
                } catch (error: Exception) {
                    store.logEvent(
                        AuditEventType.KEY_CHECKOUT_SYNC_FAILED,
                        actor,
                        RecordType.KEY,
                        key.id,
                        error.message ?: "Checkout close-out sync failed.",
                    )
                }
            }
        } else {
            store.logEvent(
                AuditEventType.KEY_CHECKOUT_SYNC_FAILED,
                actor,
                RecordType.KEY,
                key.id,
                "No backend checkout id to close out — the create sync likely never succeeded.",
            )
        }
    }

    private fun handleNodeAbandoned(sessionId: Long, matchedKey: ManagedKey?) {
        store.logEvent(
            AuditEventType.KEY_RETURN_ABANDONED,
            actorUserId(),
            RecordType.KEY,
            matchedKey?.id,
            "Alert both the terminal user and Super Admin.",
        )
        showNodeOutcomeMessage(sessionId, "ABANDONED", matchedKey)
    }

    private fun handleNodeFailure(sessionId: Long, matchedKey: ManagedKey?, message: String) {
        store.logEvent(AuditEventType.KEY_RETURN_FAILED, actorUserId(), RecordType.KEY, matchedKey?.id, message)
        showNodeOutcomeMessage(sessionId, "FAILED", matchedKey, message)
    }

    /** Shows [ReturnSession.NodeOutcomeMessage] for [EXIT_AUTO_RETURN_MILLIS] before actually
     * transitioning to Waiting — see that state's own doc for why this exists (a Tier 1 gap,
     * fixed in Tier 3). Guarded by [pendingTimerGeneration] like the other generation-guarded
     * one-shots here — a fresh scan before the 3s elapses supersedes it (the state will have
     * already moved on, so the stale callback is correctly a no-op). */
    private fun showNodeOutcomeMessage(sessionId: Long, outcome: String, matchedKey: ManagedKey?, failureMessage: String? = null) {
        transitionTo(
            ReturnSession.NodeOutcomeMessage(sessionId, outcome, matchedKey, failureMessage),
            trigger = "node cycle outcome=$outcome (showing message)",
        )
        val generation = ++pendingTimerGeneration
        mainHandler.postDelayed(
            {
                if (pendingTimerGeneration == generation) handleNodeCycleOutcome(sessionId, outcome)
            },
            EXIT_AUTO_RETURN_MILLIS,
        )
    }

    /** Every node-cycle terminal outcome returns to Waiting, never straight to Closed — preserved
     * exactly from the pre-rewrite design; the session ends only via [handleSessionDoorClosed]. */
    private fun handleNodeCycleOutcome(sessionId: Long, outcome: String) {
        transitionTo(ReturnSession.Waiting(sessionId), trigger = "node cycle outcome=$outcome")
        if (outcome == "SUCCESS") {
            val generation = ++pendingTimerGeneration
            moreKeyReturnHoldUntilEpochMillis = System.currentTimeMillis() + MORE_KEY_RETURN_HOLD_MILLIS
            mainHandler.postDelayed(
                {
                    if (pendingTimerGeneration == generation) {
                        moreKeyReturnHoldUntilEpochMillis = null
                        doorCloseWarningActive = true
                    }
                },
                MORE_KEY_RETURN_HOLD_MILLIS,
            )
        }
    }

    private fun handleSessionDoorClosed(sessionId: Long) {
        Log.d(LOG_TAG, "ReturnFlowDiag: session=$sessionId return session ended, door closed")
        doorCloseWarningActive = false
        moreKeyReturnHoldUntilEpochMillis = null
        pendingTimerGeneration += 1
        sessionComplete = true
        mainHandler.postDelayed(
            {
                transitionTo(ReturnSession.Closed, trigger = "session complete message elapsed")
                wrongSlotBlockedNodes = emptySet()
                returnedKeyNames = emptyList()
                sessionComplete = false
                onSessionEnded()
            },
            RETURN_SESSION_COMPLETION_MESSAGE_MILLIS,
        )
    }

    private fun scheduleCertificationAbandonment(sessionId: Long, attemptId: Long, abandonAtEpochMillis: Long?) {
        if (abandonAtEpochMillis == null) return
        val remaining = (abandonAtEpochMillis - System.currentTimeMillis()).coerceAtLeast(0L)
        mainHandler.postDelayed(
            {
                val current = state
                if (current is ReturnSession.AwaitingCertification && current.attemptId == attemptId) {
                    store.logEvent(
                        AuditEventType.KEY_RETURN_ABANDONED,
                        actorUserId(),
                        RecordType.KEY,
                        current.matchedKey?.id,
                        "Key Return Certification did not complete before the 20s ceiling. Alert both the terminal user and Super Admin.",
                    )
                    handleNodeCycleOutcome(sessionId, "ABANDONED")
                }
            },
            remaining,
        )
    }

    /** Mirrors the pre-rewrite `resolveNodeActiveState`'s resolution rule exactly: a real
     * card-UID match is authoritative; the "only key currently taken" heuristic (no deadline —
     * never a timed flow) only ever fires for the hardware-free manual key-card tap. */
    private fun resolveTarget(matchedKey: ManagedKey?, matchedSlot: KeySlot?): Pair<ManagedKey?, KeySlot?> {
        if (matchedKey != null && matchedSlot != null) return matchedKey to matchedSlot
        val key = retrievalKeys().filter { it.id in takenKeyIds() }.singleOrNull()
        val slot = key?.let { returningKey -> retrievalSlots().firstOrNull { it.managedKeyId == returningKey.id } }
        return key to slot
    }

    private fun resolveKeyFobUid(rawUid: String): Boolean {
        val matchedUserId = personnelCardStore.recordIdFor(rawUid)
        val matchedKeyId = keyCardStore.recordIdFor(rawUid)
        return CardUidResolver.resolve(matchedUserId, matchedKeyId) is CardUidMatch.Key
    }

    private fun resolveKeyDisplayName(keyId: String): String? =
        retrievalKeys().firstOrNull { it.id == keyId }?.displayName

    private fun checkoutSummaryFor(key: ManagedKey): String? {
        val record = checkoutStore.find(key.id) ?: return null
        val takerName = record.userId?.let(personnelDisplayName) ?: "an unknown user"
        val elapsedMinutes = ((System.currentTimeMillis() - record.takenAtEpochMillis) / 60_000L).coerceAtLeast(0)
        val elapsedText = when {
            elapsedMinutes < 1L -> "just now"
            elapsedMinutes < 60L -> "${elapsedMinutes}m ago"
            else -> "${elapsedMinutes / 60}h ${elapsedMinutes % 60}m ago"
        }
        return "Checked out by $takerName · $elapsedText"
    }

    /** Hard-resets this controller's own state (bypassing the normal completion-message flow)
     * and then runs [hardwareAction] — the real disconnect/close/stop-monitoring call. Every
     * `TerminalAdminApp` call site that used to call `hardwareController.disconnect()`/`.close()`/
     * `.stopMonitoring()` directly now calls this instead, passing that same call through
     * [hardwareAction] — makes this controller the sole thing that ever mutates
     * [CabinetHardwareController]'s return-session guard, in practice, not just because
     * `TerminalAdminApp`'s render-dispatch ordering happens to make those 3 call sites
     * unreachable while a session is genuinely open (verified true, separately, but this no
     * longer *relies* on that verification holding forever). */
    fun forceClose(hardwareAction: () -> Unit) {
        val wasOpen = state !is ReturnSession.Closed
        pendingTimerGeneration += 1
        if (wasOpen) {
            Log.d(LOG_TAG, "ReturnFlowDiag: session=${sessionIdOf(state) ?: "none"} forceClose - session force-closed from outside the normal flow")
        }
        state = ReturnSession.Closed
        wrongSlotBlockedNodes = emptySet()
        doorCloseWarningActive = false
        moreKeyReturnHoldUntilEpochMillis = null
        returnedKeyNames = emptyList()
        sessionComplete = false
        hardwareAction()
    }

    private fun transitionTo(next: ReturnSession, trigger: String) {
        val from = state
        state = next
        Log.d(
            LOG_TAG,
            "ReturnFlowDiag: session=${sessionIdOf(next) ?: sessionIdOf(from) ?: "none"} " +
                "node=${nodeAddressOf(next) ?: nodeAddressOf(from)} " +
                "${stateName(from)} -> ${stateName(next)} ($trigger) readerArmed=$sessionReaderArmed",
        )
    }

    private companion object {
        const val LOG_TAG = "ReturnSessionController"
        const val NO_NODE_AUTO_COMPLETE_MILLIS = 2_500L
        const val MORE_KEY_RETURN_HOLD_MILLIS = 5_000L
        const val RETURN_SESSION_COMPLETION_MESSAGE_MILLIS = 1_500L
        const val EXIT_AUTO_RETURN_MILLIS = 3_000L
    }
}

private fun sessionIdOf(session: ReturnSession): Long? = when (session) {
    is ReturnSession.Closed -> null
    is ReturnSession.AwaitingCertification -> session.sessionId
    is ReturnSession.Unlocking -> session.sessionId
    is ReturnSession.AwaitingInsertion -> session.sessionId
    is ReturnSession.Waiting -> session.sessionId
    is ReturnSession.NodeOutcomeMessage -> session.sessionId
}

private fun nodeAddressOf(session: ReturnSession): Int? = when (session) {
    is ReturnSession.Unlocking -> session.nodeAddress
    is ReturnSession.AwaitingInsertion -> session.nodeAddress
    else -> null
}

private fun stateName(session: ReturnSession): String = when (session) {
    is ReturnSession.Closed -> "Closed"
    is ReturnSession.AwaitingCertification -> "AwaitingCertification"
    is ReturnSession.Unlocking -> "Unlocking"
    is ReturnSession.AwaitingInsertion -> "AwaitingInsertion"
    is ReturnSession.Waiting -> "Waiting"
    is ReturnSession.NodeOutcomeMessage -> "NodeOutcomeMessage"
}
