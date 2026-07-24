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
 * three callback groups:
 *
 * 1. [onBeginReturnFlow]: Blue Light On -> Eject Door -> confirm door
 *    open. A failure here ends the flow with the door never opened.
 * 2. [onPollInsertion]: polls bolt presence. Two independent clocks — 5 s
 *    from door-open only raises beep volume; [abandonAtEpochMillis] is an
 *    absolute deadline the caller computed at the *original card swipe*
 *    (not from door-open, and not paused for however long an optional
 *    Key Return Certification login took) — the hard no-insert ceiling.
 * 3. [onWaitForDoorClose]: polls the door until closed. The Door-Close
 *    Warning Time countdown (distinct Admin Menu setting from Take
 *    Warning Time) only triggers a "please close the door" voice line at
 *    expiry; closing the door always completes the flow, however late.
 *
 * [key]/[slot] may be null — the pre-existing "only key currently taken"
 * heuristic fallback for the login screen's UID-less manual key-card tap
 * (a hardware-free testing convenience, see [resolveReturningKey]) — in
 * which case this screen keeps its original simple fixed-delay behavior
 * with no timers, no hardware, and nothing logged, since it was never a
 * real return to begin with. [abandonAtEpochMillis] is only meaningful
 * (and only read) when [slot] is non-null.
 *
 * The continuous beep runs from door-open confirmation through door-close
 * confirmation on every path except a door-open hardware fault, which
 * never starts it. [onEvent] fires once per terminal or notable outcome
 * (success / failed-return / abandoned-return / door-left-open) — these
 * are three genuinely different failure modes (never inserted / inserted
 * but door left open / successful close) and are logged distinctly, never
 * collapsed into one case.
 */
@Composable
fun TerminalKeyReturnScreen(
    padding: PaddingValues,
    key: ManagedKey?,
    slot: KeySlot?,
    abandonAtEpochMillis: Long?,
    doorCloseWarningTimeSeconds: Int,
    videoRecordingEnabled: Boolean,
    onBeginReturnFlow: (nodeAddress: Int, onDoorOpenConfirmed: () -> Unit, onFailure: (String) -> Unit) -> Unit,
    onPollInsertion: (
        nodeAddress: Int,
        abandonAtEpochMillis: Long,
        onInserted: () -> Unit,
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
    onEvent: (ReturnFlowOutcome) -> Unit,
    onCompleted: () -> Unit,
) {
    val context = LocalContext.current
    val videoRecorder = remember { VideoRecordingController() }
    val audio = remember { AudioFeedbackController(context) }
    var stage by remember { mutableStateOf<ReturnStage>(ReturnStage.OpeningDoor) }
    var beeping by remember { mutableStateOf(false) }
    var beepLoud by remember { mutableStateOf(false) }

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
            onCompleted()
            return@LaunchedEffect
        }
        onBeginReturnFlow(
            nodeAddress,
            {
                beeping = true
                stage = ReturnStage.WaitingForInsertion
                onPollInsertion(
                    nodeAddress,
                    deadline,
                    {
                        beepLoud = false
                        stage = ReturnStage.WaitingForDoorClose(warningExpired = false)
                        onWaitForDoorClose(
                            nodeAddress,
                            doorCloseWarningTimeSeconds,
                            {
                                audio.playVoiceLine(VoiceLine.PLEASE_CLOSE_THE_DOOR)
                                stage = ReturnStage.WaitingForDoorClose(warningExpired = true)
                                onEvent(ReturnFlowOutcome.DoorLeftOpen(key, slot))
                            },
                            {
                                beeping = false
                                onEvent(ReturnFlowOutcome.Success(key, slot))
                                onCompleted()
                            },
                            { message ->
                                beeping = false
                                stage = ReturnStage.Failed(message)
                                onEvent(ReturnFlowOutcome.Failed(key, slot, message))
                            },
                        )
                    },
                    {
                        beepLoud = true
                        audio.playVoiceLine(VoiceLine.PLEASE_INSERT_THE_KEY)
                    },
                    {
                        beeping = false
                        stage = ReturnStage.Abandoned
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

    LaunchedEffect(beeping, beepLoud) {
        while (beeping) {
            audio.beep(loud = beepLoud)
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
            ReturnStage.OpeningDoor, ReturnStage.WaitingForInsertion -> StatusTone.ATTENTION
            is ReturnStage.WaitingForDoorClose -> StatusTone.ATTENTION
            is ReturnStage.Failed, ReturnStage.Abandoned -> StatusTone.ALARM
        }
        SoftWaitPanel(
            tone = tone,
            title = when (val currentStage = stage) {
                ReturnStage.OpeningDoor -> "Opening door…"
                ReturnStage.WaitingForInsertion -> "Insert the key"
                is ReturnStage.WaitingForDoorClose -> "Close the door"
                is ReturnStage.Failed -> "Key return problem"
                ReturnStage.Abandoned -> "Key return cancelled"
            },
            message = when (val currentStage = stage) {
                ReturnStage.OpeningDoor, ReturnStage.WaitingForInsertion ->
                    if (key != null) "Insert ${key.displayName} now." else "Insert the key now."
                is ReturnStage.WaitingForDoorClose ->
                    if (currentStage.warningExpired) "Please close the door." else "Close the door to finish."
                is ReturnStage.Failed -> currentStage.message
                ReturnStage.Abandoned -> "No key was inserted in time. The slot has been secured."
            },
            showProgress = when (val current = stage) {
                ReturnStage.OpeningDoor -> true
                is ReturnStage.WaitingForDoorClose -> !current.warningExpired
                else -> false
            },
            assistText = when (val current = stage) {
                ReturnStage.WaitingForInsertion -> "Door open"
                is ReturnStage.WaitingForDoorClose -> if (current.warningExpired) "Please close the door" else null
                else -> null
            },
            assistAttention = true,
            modifier = Modifier.widthIn(max = 640.dp),
        )
    }
}

private const val NO_NODE_AUTO_COMPLETE_MILLIS = 2_500L
private const val BEEP_INTERVAL_MILLIS = 1_000L
private const val EXIT_AUTO_RETURN_MILLIS = 3_000L

private sealed interface ReturnStage {
    data object OpeningDoor : ReturnStage
    data object WaitingForInsertion : ReturnStage
    data class WaitingForDoorClose(val warningExpired: Boolean) : ReturnStage
    data class Failed(val message: String) : ReturnStage
    data object Abandoned : ReturnStage
}

/**
 * Terminal or notable outcomes the caller logs via `TerminalAdminStore.logEvent`.
 * [Abandoned] is the deliberate asymmetry with the Key Take Flow's abandonment:
 * Return's abandonment additionally implies a two-party alert (the terminal user
 * and Super Admin), not just a log entry — see the caller for how that's recorded.
 */
sealed interface ReturnFlowOutcome {
    data class Success(val key: ManagedKey?, val slot: KeySlot) : ReturnFlowOutcome
    data class Failed(val key: ManagedKey?, val slot: KeySlot, val message: String) : ReturnFlowOutcome
    data class Abandoned(val key: ManagedKey?, val slot: KeySlot) : ReturnFlowOutcome
    data class DoorLeftOpen(val key: ManagedKey?, val slot: KeySlot) : ReturnFlowOutcome
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
