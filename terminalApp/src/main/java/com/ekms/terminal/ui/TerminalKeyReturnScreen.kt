package com.ekms.terminal.ui

import android.util.Log
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ekms.shared.domain.KeySlot
import com.ekms.shared.domain.ManagedKey
import com.ekms.terminal.hardware.AudioFeedbackController
import com.ekms.terminal.hardware.VideoRecordingController
import com.ekms.terminal.hardware.VoiceLine
import com.ekms.terminal.ui.theme.StatusTone
import kotlinx.coroutines.delay

/**
 * Smart Key Cabinet User Manual V2.1, Section 3 — Key return — upgraded by
 * the Key Return Flow production enhancement (CLAUDE.md "Terminal App UX
 * Baseline (Production)" §2), mirroring the Key Take Flow's timer
 * structure but direction-reversed, with its own distinct settings and
 * abandonment behavior. Driven by [CabinetHardwareController] through
 * two callback groups:
 *
 * 1. [onBeginNodeCycle]: Blue Light On -> Engage electromagnet -> idempotent door check/eject.
 *    A failure here ends this node's cycle with the door never opened (a fresh session) or,
 *    for a continuing session, leaves the already-open door and the rest of the session alone.
 * 2. [onPollInsertion]: polls *this node's own* bolt presence only. Two independent clocks — 5 s
 *    from unlock only raises beep volume; [abandonAtEpochMillis] is an
 *    absolute deadline the caller computed at the *original card swipe*
 *    (not from unlock, and not paused for however long an optional
 *    Key Return Certification login took) — the hard no-insert ceiling for *this node's own
 *    cycle only* (Return Flow session rebuild, Jul 2026 — an abandonment here no longer ends
 *    the whole session; the door stays open and the session keeps listening for the next scan).
 *
 * **Multi-key Return hard-block rework: cross-node wrong-slot detection is no longer this
 * screen's concern at all.** It used to sweep other nodes from inside this same poll cycle;
 * that sweep is now session-scoped (`CabinetHardwareController.beginReturnSessionWrongSlotSweep`,
 * owned by `TerminalAdminApp`) — it runs independently of whichever node (if any) this screen
 * is currently showing, including during `ReturnSessionScreen`'s idle state between cycles, and
 * surfaces as a hard block (no new scan can start a node cycle) rendered on `ReturnSessionScreen`
 * itself, not here. This screen's own cycle — unlock, poll *its own* insertion, abandon — runs
 * completely unaffected by a wrong-slot condition elsewhere in the session.
 *
 * **Active-node identity gap rework (found via ad hoc hardware testing, a distinct gap from the
 * hard-block above — this one is in `onPollInsertion`'s own success check, not the sweep):**
 * the active node's own insertion check now verifies identity (0x17, not bare 0x16) via
 * [resolveKeyIdForUid] before ever locking. A mismatch — [onWrongKeyInserted]'s payload is the
 * wrong key's resolved id, or `null` if unresolved (no card, or an unenrolled UID) — never locks
 * the electromagnet and, per explicit product decision, **suspends this node's own 20s
 * abandonment ceiling entirely** for as long as the wrong key remains (not merely pausing it):
 * [onWrongKeyRemoved] fires when it's taken back out, at which point a genuinely fresh 20s
 * window starts, not whatever remained of the original one. This screen resolves the wrong key's
 * *display name* itself via [resolveKeyDisplayName] (the hardware layer only ever hands up a
 * resolved id, never a raw UID or a domain object) for the alarm card's message.
 *
 * **Return Flow session rebuild (Jul 2026, full scrap-and-rebuild — supersedes the old
 * per-key door-close-wait entirely, not layered on top of it):** there is no longer a
 * per-node "wait for the door to close" step at all — insertion confirmed IS this node's
 * cycle ending (electromagnet locked, light off, checkout closed out), immediately followed
 * by [onNodeCycleComplete] so the session returns to its own listening state
 * (`ReturnSessionScreen`, not this screen) for the next scan. The door itself, and the
 * session-level Door-Close Warning Time countdown that plays the "please close the door"
 * voice line, are both owned by `TerminalAdminApp`/`CabinetHardwareController` now — this
 * screen has no door-close awareness of its own any more.
 *
 * [key]/[slot] may be null — the pre-existing "only key currently taken"
 * heuristic fallback for the login screen's UID-less manual key-card tap
 * (a hardware-free testing convenience, see [resolveReturningKey]) — in
 * which case this screen keeps its original simple fixed-delay behavior
 * with no timers, no hardware, and nothing logged, since it was never a
 * real return to begin with. [abandonAtEpochMillis] is only meaningful
 * (and only read) when [slot] is non-null.
 *
 * The continuous beep runs from unlock confirmation through this node's own
 * insertion-or-abandonment on every path except an unlock hardware fault, which
 * never starts it. [onEvent] fires once per terminal or notable outcome for *this node's own
 * cycle* (success / failed / abandoned) — three genuinely different outcomes, logged distinctly,
 * never collapsed into one case. Door-left-open is no longer one of them — it's now a
 * session-level warning owned by the caller, not a per-node outcome of this screen.
 *
 * Phase 9F (visual/theme only): `checkoutSummary`'s card ("Checked out by X · Ym ago", built in
 * `TerminalAdminApp.kt` from `TerminalCheckoutStore`) gets a light `1.dp` `outlineVariant` border
 * used elsewhere in that pass for visibility in light mode — it was a bare, borderless `SoftCard`
 * before. `resolveReturningKey` itself has no presentation to reskin — it's a pure `ManagedKey?`
 * lookup, no UI. Its actual on-screen control (the "Simulate key-card return" button) lives in
 * `TerminalNfcCardLoginScreen.kt`, a different, unnamed screen. `ReturnSessionScreen.kt` (the
 * "waiting for the next scan or door-close" view, and now also the wrong-slot hard-block view) is
 * a separate file, not part of this file's `ReturnStage` — see that file's own doc.
 */
@Composable
fun TerminalKeyReturnScreen(
    padding: PaddingValues,
    key: ManagedKey?,
    slot: KeySlot?,
    abandonAtEpochMillis: Long?,
    attemptId: Long,
    checkoutSummary: String?,
    videoRecordingEnabled: Boolean,
    onBeginNodeCycle: (nodeAddress: Int, onNodeUnlocked: () -> Unit, onFailure: (String) -> Unit) -> Unit,
    onPollInsertion: (
        nodeAddress: Int,
        abandonAtEpochMillis: Long,
        isExpectedKey: (rawUid: String) -> Boolean,
        resolveKeyIdForUid: (rawUid: String) -> String?,
        onInserted: () -> Unit,
        onWrongKeyInserted: (resolvedKeyId: String?) -> Unit,
        onWrongKeyRemoved: () -> Unit,
        onLouderBeepThreshold: () -> Unit,
        onAbandoned: () -> Unit,
        onFailure: (String) -> Unit,
    ) -> Unit,
    /** Raw UID -> enrolled key id, e.g. `keyCardStore::recordIdFor`. Used both to build
     * [onPollInsertion]'s `isExpectedKey` (compared against [key]'s own id) and passed straight
     * through as its `resolveKeyIdForUid` (for reporting *which* wrong key was read). */
    resolveKeyIdForUid: (rawUid: String) -> String?,
    /** Resolved key id -> display name, for the wrong-key alarm card's message only. */
    resolveKeyDisplayName: (keyId: String) -> String?,
    onEvent: (ReturnFlowOutcome) -> Unit,
    onNodeCycleComplete: (outcome: String) -> Unit,
) {
    val context = LocalContext.current
    val videoRecorder = remember { VideoRecordingController() }
    val audio = remember { AudioFeedbackController(context) }
    var stage by remember { mutableStateOf<ReturnStage>(ReturnStage.OpeningDoor) }
    var beeping by remember { mutableStateOf(false) }
    var beepLoud by remember { mutableStateOf(false) }
    var wrongKeyPresent by remember { mutableStateOf(false) }
    var wrongKeyName by remember { mutableStateOf<String?>(null) }

    DisposableEffect(videoRecordingEnabled) {
        if (videoRecordingEnabled) videoRecorder.start("key_return")
        onDispose { videoRecorder.stop() }
    }

    DisposableEffect(audio) {
        onDispose { audio.release() }
    }

    LaunchedEffect(key, slot) {
        val nodeAddress = slot?.nodeAddress
        val deadline = abandonAtEpochMillis
        if (nodeAddress == null || deadline == null) {
            // Hardware-free testing convenience only — see the class doc.
            delay(NO_NODE_AUTO_COMPLETE_MILLIS)
            onNodeCycleComplete("NO_NODE_TEST")
            return@LaunchedEffect
        }
        onBeginNodeCycle(
            nodeAddress,
            {
                beeping = true
                stage = ReturnStage.WaitingForInsertion
                onPollInsertion(
                    nodeAddress,
                    deadline,
                    { uid -> resolveKeyIdForUid(uid) == key?.id },
                    resolveKeyIdForUid,
                    {
                        // Return Flow session rebuild (Jul 2026): insertion confirmed IS this
                        // node's cycle ending now — no more per-node door-close wait. The
                        // electromagnet/light are already released by this point (moved into
                        // CabinetHardwareController.pollForKeyInsertion's own insertion branch).
                        beeping = false
                        beepLoud = false
                        Log.d("ReturnFlowDiag", "TerminalKeyReturnScreen: inserted, Success -> onEvent then onNodeCycleComplete")
                        onEvent(ReturnFlowOutcome.Success(key, slot))
                        onNodeCycleComplete("SUCCESS")
                    },
                    { resolvedKeyId ->
                        wrongKeyPresent = true
                        wrongKeyName = resolvedKeyId?.let(resolveKeyDisplayName)
                        Log.d("ReturnFlowDiag", "TerminalKeyReturnScreen: node=$nodeAddress attemptId=$attemptId WRONG KEY inserted, resolvedKeyId=${resolvedKeyId ?: "unresolved"}")
                    },
                    {
                        wrongKeyPresent = false
                        wrongKeyName = null
                        Log.d("ReturnFlowDiag", "TerminalKeyReturnScreen: node=$nodeAddress attemptId=$attemptId wrong key removed, abandonment window reset")
                    },
                    {
                        beepLoud = true
                        audio.playVoiceLine(VoiceLine.PLEASE_INSERT_THE_KEY)
                    },
                    {
                        beeping = false
                        stage = ReturnStage.Abandoned
                        Log.d("ReturnFlowDiag", "TerminalKeyReturnScreen: node=$nodeAddress attemptId=$attemptId NODE ABANDONED (20s ceiling)")
                        onEvent(ReturnFlowOutcome.Abandoned(key, slot))
                    },
                    { message ->
                        beeping = false
                        stage = ReturnStage.Failed(message)
                        onEvent(ReturnFlowOutcome.Failed(key, slot, message))
                    },
                )
            },
            { message ->
                stage = ReturnStage.Failed(message)
                onEvent(ReturnFlowOutcome.Failed(key, slot, message))
            },
        )
    }

    // Keyed on `beeping` alone — neither `beepLoud` nor `wrongKeyPresent` may restart this loop.
    // Same bug as the Take Flow screen: changing beepLoud used to be a LaunchedEffect key, so the
    // 5s-escalation callback (which flips beepLoud and fires the voice line together) cancelled
    // and relaunched this coroutine right at that moment. rememberUpdatedState lets each
    // iteration read the live values without that cancel/relaunch; a wrong-key alarm forces full
    // volume on top of the normal 5s-from-unlock escalation, same shape the old cross-node
    // wrong-slot alarm used before Part B moved that concern to session scope.
    val currentBeepLoud by rememberUpdatedState(beepLoud)
    val currentWrongKeyPresent by rememberUpdatedState(wrongKeyPresent)
    LaunchedEffect(beeping) {
        while (beeping) {
            audio.beep(loud = currentBeepLoud || currentWrongKeyPresent)
            delay(BEEP_INTERVAL_MILLIS)
        }
    }

    // Abandonment and failure both hand control back to idle after showing
    // what happened, same "no extra step" principle the Key Take Flow
    // screen already uses.
    LaunchedEffect(stage) {
        val currentStage = stage
        if (currentStage is ReturnStage.Failed || currentStage is ReturnStage.Abandoned) {
            delay(EXIT_AUTO_RETURN_MILLIS)
            onNodeCycleComplete(if (currentStage is ReturnStage.Abandoned) "ABANDONED" else "FAILED")
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.widthIn(max = 640.dp),
        ) {
            if (checkoutSummary != null && stage !is ReturnStage.Failed && stage !is ReturnStage.Abandoned) {
                // Phase 9F: a light border for visibility in light mode — was a bare, borderless
                // SoftCard before (same class of fix as SoftWaitPanel's default, applied here to
                // this screen's own secondary card).
                SoftCard(
                    contentPadding = 14.dp,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Text(
                        text = checkoutSummary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            if (wrongKeyPresent) {
                // Same alarm-tone recipe the old cross-node wrong-slot card used (and
                // ReturnSessionScreen's new hard-block card still uses) — 2.dp colorScheme.error
                // border, 4.dp elevation, errorContainer fill. This is a different gap (this
                // node's own identity check, not the cross-node sweep), but the visual language
                // for "a wrong key needs to be removed" should read the same everywhere it fires.
                SoftCard(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentPadding = 16.dp,
                    elevation = 4.dp,
                    border = BorderStroke(2.dp, MaterialTheme.colorScheme.error),
                ) {
                    Text(
                        text = "Wrong key inserted",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = "This is ${wrongKeyName ?: "an unrecognized key"} — remove it and insert " +
                            "${key?.displayName ?: "the correct key"}.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            // Status-ring tone follows the stage 1:1 — Failed/Abandoned are the
            // Danger (alarm) tone, everything else mid-flow is Warning
            // (attention/door-open), matching the design spec's color meaning. An active
            // wrong-key alarm also escalates an otherwise-normal waiting stage to alarm.
            val tone = when {
                wrongKeyPresent -> StatusTone.ALARM
                stage is ReturnStage.Failed || stage is ReturnStage.Abandoned -> StatusTone.ALARM
                else -> StatusTone.ATTENTION
            }
            SoftWaitPanel(
                tone = tone,
                title = when (stage) {
                    ReturnStage.OpeningDoor -> "Opening door…"
                    ReturnStage.WaitingForInsertion -> "Insert the key"
                    is ReturnStage.Failed -> "Key return problem"
                    ReturnStage.Abandoned -> "Key return cancelled"
                },
                message = when (val currentStage = stage) {
                    ReturnStage.OpeningDoor, ReturnStage.WaitingForInsertion ->
                        if (key != null) "Insert ${key.displayName} now." else "Insert the key now."
                    is ReturnStage.Failed -> currentStage.message
                    ReturnStage.Abandoned -> "No key was inserted in time. The slot has been secured."
                },
                showProgress = stage == ReturnStage.OpeningDoor,
                assistText = if (stage == ReturnStage.WaitingForInsertion) "Node unlocked" else null,
                assistAttention = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private const val NO_NODE_AUTO_COMPLETE_MILLIS = 2_500L
private const val BEEP_INTERVAL_MILLIS = 1_000L
private const val EXIT_AUTO_RETURN_MILLIS = 3_000L

private sealed interface ReturnStage {
    data object OpeningDoor : ReturnStage
    data object WaitingForInsertion : ReturnStage
    data class Failed(val message: String) : ReturnStage
    data object Abandoned : ReturnStage
}

/**
 * Terminal or notable outcomes of *this node's own cycle* that the caller logs via
 * `TerminalAdminStore.logEvent`. [Abandoned] is the deliberate asymmetry with the Key Take
 * Flow's abandonment: Return's abandonment additionally implies a two-party alert (the
 * terminal user and Super Admin), not just a log entry — see the caller for how that's
 * recorded. Door-left-open is deliberately not one of these (Return Flow session rebuild, Jul
 * 2026) — it's now a session-level warning owned by `TerminalAdminApp`/
 * `CabinetHardwareController.beginReturnSessionDoorMonitor`, not a per-node outcome of this
 * screen, since the door itself is no longer scoped to one node's cycle.
 */
sealed interface ReturnFlowOutcome {
    data class Success(val key: ManagedKey?, val slot: KeySlot) : ReturnFlowOutcome
    data class Failed(val key: ManagedKey?, val slot: KeySlot, val message: String) : ReturnFlowOutcome
    data class Abandoned(val key: ManagedKey?, val slot: KeySlot) : ReturnFlowOutcome
}

/**
 * Fallback for identifying the returning key when no real card-UID match is
 * available — i.e. only the login screen's manual key-card tap (a hardware-
 * free testing convenience, see [TerminalLoginScreen]'s `onKeyCardSwiped`),
 * which carries no UID at all. A genuine public-reader swipe is resolved by
 * UID via `EncryptedUidEnrollmentStore`/`CardUidResolver` in
 * `TerminalAdminApp` and passed in directly, bypassing this heuristic
 * entirely. This resolves "the only key currently marked taken": a real
 * match when exactly one key is out, not a fabricated success.
 */
internal fun resolveReturningKey(takenKeyIds: Set<String>, keys: List<ManagedKey>): ManagedKey? =
    keys.filter { it.id in takenKeyIds }.singleOrNull()
