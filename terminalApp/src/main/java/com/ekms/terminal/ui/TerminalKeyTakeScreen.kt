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
 * Key Take Flow — CLAUDE.md "Terminal App UX Baseline (Production —
 * baseline + defined enhancements)" §1, superseding the manual's bare
 * "door opens, insert key, done" retrieval description for the TAKE side
 * specifically. A dedicated full-screen takeover, the same pattern
 * Section 3's return flow already uses, driven entirely by
 * [CabinetHardwareController] through three callback groups:
 *
 * 1. [onBeginTake]: Blue Light On -> Unlock -> Eject Door -> confirm door
 *    open. A failure here ends the flow with no key ever released.
 * 2. [onPollRemoval]: polls bolt removal. Two independent timers from
 *    door-open, not from each other — 5 s only raises beep volume, 20 s
 *    is the hard abandonment ceiling (already re-locks/lights-off before
 *    calling back).
 * 3. [onWaitForDoorClose]: polls the door until closed. The Take Warning
 *    Time countdown only triggers a "please close the door" voice line at
 *    expiry — closing the door always completes the flow, however late.
 *
 * The continuous beep runs from door-open confirmation through door-close
 * confirmation on every path except a step-1 hardware fault, which never
 * starts it. [onKeyRemoved] fires once, right at confirmed bolt removal —
 * before the door-close wait even starts — so the caller can mark the key
 * unavailable immediately rather than only once this whole screen exits.
 * [onEvent] fires once per terminal or notable outcome (success /
 * failed-take / abandoned-take / door-left-open) so the caller can log it;
 * a door-left-open event does not end the flow by itself, since the
 * screen keeps waiting for the door regardless.
 *
 * Phase 9E (visual/theme only — no timer/state-transition/hardware-call code touched, given
 * this screen's two known unresolved hardware bugs, beep-continuity and Take Warning Time/
 * door-left-open timing): the same [TerminalKeyTakeScreen] instance is reused verbatim for both
 * the single-key flow (`SuperAdminRoute.KEY_RETRIEVAL`) and the per-node view inside the Key
 * Menu multi-key queue (`SuperAdminRoute.KEY_MENU`, see `TerminalAdminApp.kt`) — there is no
 * separate "queued" wrapper UI to reskin, they're the same composable. Door-left-open
 * (`WaitingForDoorClose(warningExpired = true)`) now passes `strongAttention = true` to
 * [SoftWaitPanel] (new param, defaults `false`) for a `colors.warning`-bordered card — the same
 * warning tone [HintSeverity]/[SoftAssistChip] already use, not a new color — since before this
 * fix that state rendered with the exact same [StatusTone.ATTENTION] look as an ordinary
 * "still waiting, nothing wrong yet" wait. No numeric countdown/timer display exists anywhere
 * in this screen to reskin — confirmed by reading the code, not assumed — so none was added.
 * No manual/fallback demo control exists here either (that's `TerminalKeyReturnScreen`'s
 * `resolveReturningKey` null-key tap convenience, a Return Flow concept — out of scope here and
 * untouched). **Flagged, not fixed**: [SoftWaitPanel]'s card (`elevation = 0.dp`, no border by
 * default) has the same "may blend into the light-mode background" issue Phase 9A found and
 * fixed in `SoftScanTile` — but `SoftWaitPanel` is also used by `TerminalKeyReturnScreen`
 * (Return Flow, explicitly off-limits this pass), so fixing its default look here would bleed
 * into Return Flow's rendering. Left as-is; the new `strongAttention` param only affects the one
 * state that opts in.
 */
@Composable
fun TerminalKeyTakeScreen(
    padding: PaddingValues,
    key: ManagedKey,
    slot: KeySlot,
    takeWarningTimeSeconds: Int,
    videoRecordingEnabled: Boolean,
    onBeginTake: (nodeAddress: Int, onDoorOpenConfirmed: () -> Unit, onFailure: (String) -> Unit) -> Unit,
    onPollRemoval: (
        nodeAddress: Int,
        onRemoved: () -> Unit,
        onLouderBeepThreshold: () -> Unit,
        onAbandoned: () -> Unit,
        onFailure: (String) -> Unit,
    ) -> Unit,
    onWaitForDoorClose: (
        nodeAddress: Int,
        warningSeconds: Int,
        onWarningExpired: () -> Unit,
        onDoorClosed: () -> Unit,
        onFailure: (String) -> Unit,
    ) -> Unit,
    onKeyRemoved: () -> Unit,
    onEvent: (TakeFlowOutcome) -> Unit,
    onCompleted: () -> Unit,
) {
    val context = LocalContext.current
    val videoRecorder = remember { VideoRecordingController() }
    val audio = remember { AudioFeedbackController(context) }
    // Bug fix (Jul 2026, found via ad hoc hardware testing on the multi-key queue): these three
    // were remembered with no keys, so at Key Menu's call site — where this same composable
    // instance stays mounted across every queued node in turn, only ever re-parameterized with
    // a new key/slot, never actually unmounted between nodes — they kept whatever value the
    // PREVIOUS node last left them at. Only LaunchedEffect(key, slot) below was keyed, so its
    // coroutine correctly restarted for the new node, but the on-screen stage did not: the
    // screen kept showing the just-completed node's own "Close the door to finish" prompt
    // (WaitingForDoorClose) until the new node's onDoorOpenConfirmed happened to overwrite it,
    // reading exactly like the next queued key was blocked on closing the previous one's door —
    // even though the queue's own advance (onKeyRemoved, see TerminalAdminApp.kt) was already
    // correct. The single-key KEY_RETRIEVAL path never showed this: it unmounts this composable
    // back to the grid and remounts fresh for each take, so it already got new state for free.
    // Keying these three the same way LaunchedEffect already is makes every queued node start
    // from a genuinely clean OpeningDoor/not-beeping state, matching what the single-key path
    // already had.
    var stage by remember(key, slot) { mutableStateOf<TakeStage>(TakeStage.OpeningDoor) }
    var beeping by remember(key, slot) { mutableStateOf(false) }
    var beepLoud by remember(key, slot) { mutableStateOf(false) }

    DisposableEffect(videoRecordingEnabled) {
        if (videoRecordingEnabled) videoRecorder.start("key_take")
        onDispose { videoRecorder.stop() }
    }

    DisposableEffect(audio) {
        onDispose { audio.release() }
    }

    LaunchedEffect(key, slot) {
        val nodeAddress = slot.nodeAddress
        onBeginTake(
            nodeAddress,
            {
                beeping = true
                stage = TakeStage.WaitingForRemoval
                onPollRemoval(
                    nodeAddress,
                    {
                        beepLoud = false
                        onKeyRemoved()
                        stage = TakeStage.WaitingForDoorClose(warningExpired = false)
                        onWaitForDoorClose(
                            nodeAddress,
                            takeWarningTimeSeconds,
                            {
                                audio.playVoiceLine(VoiceLine.PLEASE_CLOSE_THE_DOOR)
                                stage = TakeStage.WaitingForDoorClose(warningExpired = true)
                                onEvent(TakeFlowOutcome.DoorLeftOpen(key, slot))
                            },
                            {
                                beeping = false
                                onEvent(TakeFlowOutcome.Success(key, slot))
                                onCompleted()
                            },
                            { message ->
                                beeping = false
                                stage = TakeStage.Failed(message)
                                onEvent(TakeFlowOutcome.Failed(key, slot, message))
                            },
                        )
                    },
                    {
                        beepLoud = true
                        audio.playVoiceLine(VoiceLine.PLEASE_TAKE_THE_KEY)
                    },
                    {
                        beeping = false
                        stage = TakeStage.Abandoned
                        onEvent(TakeFlowOutcome.Abandoned(key, slot))
                    },
                    { message ->
                        beeping = false
                        stage = TakeStage.Failed(message)
                        onEvent(TakeFlowOutcome.Failed(key, slot, message))
                    },
                )
            },
            { message ->
                stage = TakeStage.Failed(message)
                onEvent(TakeFlowOutcome.Failed(key, slot, message))
            },
        )
    }

    // Keyed on `beeping` alone — `beepLoud` must NOT restart this loop. Changing beepLoud
    // used to be a LaunchedEffect key, so the 5s-escalation callback (which flips beepLoud
    // and fires the voice line in the same breath) cancelled and relaunched this coroutine
    // right at that exact moment. rememberUpdatedState lets each iteration read the live
    // value without that cancel/relaunch.
    val currentBeepLoud by rememberUpdatedState(beepLoud)
    LaunchedEffect(beeping) {
        while (beeping) {
            audio.beep(loud = currentBeepLoud)
            delay(BEEP_INTERVAL_MILLIS)
        }
    }

    // Abandonment and failure both hand control back to the grid after
    // showing what happened, same "no extra step" principle the return
    // screen's failure auto-return already uses.
    LaunchedEffect(stage) {
        val currentStage = stage
        if (currentStage is TakeStage.Failed || currentStage is TakeStage.Abandoned) {
            delay(EXIT_AUTO_RETURN_MILLIS)
            onCompleted()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        contentAlignment = Alignment.Center,
    ) {
        // Status-ring tone follows the stage 1:1 — Failed/Abandoned are the
        // Danger (alarm) tone, everything else mid-flow is Warning
        // (attention/door-open), matching the design spec's color meaning
        // rather than a bespoke per-screen color choice.
        val tone = when (stage) {
            TakeStage.OpeningDoor, TakeStage.WaitingForRemoval -> StatusTone.ATTENTION
            is TakeStage.WaitingForDoorClose -> StatusTone.ATTENTION
            is TakeStage.Failed, TakeStage.Abandoned -> StatusTone.ALARM
        }
        SoftWaitPanel(
            tone = tone,
            title = when (val currentStage = stage) {
                TakeStage.OpeningDoor -> "Opening door…"
                TakeStage.WaitingForRemoval -> "Remove the key"
                is TakeStage.WaitingForDoorClose -> "Close the door"
                is TakeStage.Failed -> "Key take problem"
                TakeStage.Abandoned -> "Key take cancelled"
            },
            message = when (val currentStage = stage) {
                TakeStage.OpeningDoor -> "Unlocking node ${slot.nodeAddress} · ${key.displayName}"
                TakeStage.WaitingForRemoval -> "Take ${key.displayName} now."
                is TakeStage.WaitingForDoorClose ->
                    if (currentStage.warningExpired) "Please close the door." else "Close the door to finish."
                is TakeStage.Failed -> currentStage.message
                TakeStage.Abandoned -> "The key was not taken in time. The slot has been re-secured."
            },
            showProgress = when (val current = stage) {
                TakeStage.OpeningDoor -> true
                is TakeStage.WaitingForDoorClose -> !current.warningExpired
                else -> false
            },
            assistText = when (val current = stage) {
                TakeStage.WaitingForRemoval -> "Door open"
                is TakeStage.WaitingForDoorClose -> if (current.warningExpired) "Please close the door" else null
                else -> null
            },
            assistAttention = true,
            // Phase 9E: door-left-open (Take Warning Time already expired, door still open) is
            // the one state that should read as more urgent than an ordinary ATTENTION wait —
            // purely a presentational read of the same `warningExpired` value the untouched
            // state machine already computes above, not a new timer or condition.
            strongAttention = (stage as? TakeStage.WaitingForDoorClose)?.warningExpired == true,
            modifier = Modifier.widthIn(max = 640.dp),
        )
    }
}

private const val BEEP_INTERVAL_MILLIS = 1_000L
private const val EXIT_AUTO_RETURN_MILLIS = 3_000L

private sealed interface TakeStage {
    data object OpeningDoor : TakeStage
    data object WaitingForRemoval : TakeStage
    data class WaitingForDoorClose(val warningExpired: Boolean) : TakeStage
    data class Failed(val message: String) : TakeStage
    data object Abandoned : TakeStage
}

/** Terminal/notable outcomes the caller logs via `TerminalAdminStore.logEvent`; see the class doc for firing order. */
sealed interface TakeFlowOutcome {
    data class Success(val key: ManagedKey, val slot: KeySlot) : TakeFlowOutcome
    data class Failed(val key: ManagedKey, val slot: KeySlot, val message: String) : TakeFlowOutcome
    data class Abandoned(val key: ManagedKey, val slot: KeySlot) : TakeFlowOutcome
    data class DoorLeftOpen(val key: ManagedKey, val slot: KeySlot) : TakeFlowOutcome
}
