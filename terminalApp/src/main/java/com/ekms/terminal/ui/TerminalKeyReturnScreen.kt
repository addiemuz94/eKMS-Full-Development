package com.ekms.terminal.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.Icon
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
 * three callback groups:
 *
 * 1. [onBeginReturnFlow]: Blue Light On -> Eject Door -> confirm door
 *    open. A failure here ends the flow with the door never opened.
 * 2. [onPollInsertion]: polls bolt presence. Two independent clocks — 5 s
 *    from door-open only raises beep volume; [abandonAtEpochMillis] is an
 *    absolute deadline the caller computed at the *original card swipe*
 *    (not from door-open, and not paused for however long an optional
 *    Key Return Certification login took) — the hard no-insert ceiling.
 *    Return Flow rework: also sweeps [wrongSlotCandidateNodeAddresses] for
 *    an unexpected insertion — see [onWrongSlotDetected]/[onWrongSlotCleared]. Follow-up
 *    revision: the sweep identifies the fob via [resolveKeyFobUid] (the caller's
 *    `CardUidResolver` lookup) rather than inferring "wrong key" from bare presence alone;
 *    `onWrongSlotDetected`'s `confirmedKey` flag says whether a genuinely enrolled key fob
 *    was identified or only unresolved presence (still flagged — see this screen's doc for why).
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
 * never starts it; it also forces full volume for as long as a wrong-slot
 * warning is active, on top of the normal 5s-from-door-open escalation.
 * [onEvent] fires once per terminal or notable outcome (success /
 * failed-return / abandoned-return / door-left-open) — these
 * are three genuinely different failure modes (never inserted / inserted
 * but door left open / successful close) and are logged distinctly, never
 * collapsed into one case.
 *
 * Phase 9F (visual/theme only — a stricter pass than Take Flow's, given the attemptId-keyed
 * abandonment-race fix and wrong-slot sweep here are both still only "believed correct by code
 * review, not hardware-reverified"; no `LaunchedEffect` key, `attemptId` comparison, polling
 * logic, `CardUidResolver` call, or timing constant was touched — see `git diff` for proof, not
 * just this claim):
 * - The wrong-slot card now has a `2.dp` `colorScheme.error`-bordered, `4.dp`-elevated treatment
 *   (same escalation recipe as Take Flow's `strongAttention`, reusing the already-established
 *   alarm/danger tone this card's `errorContainer` fill already used — not a new tone tier) plus
 *   a distinguishing icon: [Icons.Filled.VpnKey] for a confirmed wrong key, [Icons.Filled.HelpOutline]
 *   for unresolved presence — wording already distinguished the two ("Wrong key" vs "Unrecognized
 *   item"); the icon is additive, same underlying alarm tone and text for both, per the
 *   established "equally anomalous" design intent (`connectionHints`' doc, `CardUidResolver`'s
 *   permanent NFC UID rule).
 * - **Flagged, not implemented**: the task asked for a "5s-escalation state" reskin analogous to
 *   Take Flow's door-left-open, but no such visual state exists here to reskin — `beepLoud`
 *   (the 5s threshold flag) only ever drives `audio.beep(loud = ...)`; it never changes `stage`,
 *   `title`, `message`, or `assistText` today, unlike Take Flow's `warningExpired`, which was
 *   already a dedicated, pre-existing stage field with its own render branches before this pass
 *   touched anything. Making the 5s mark visible would mean adding a brand-new render-time
 *   condition where none exists — a real, if small, behavior addition, not a reskin of something
 *   already there. Left untouched and flagged here per the hard constraint's "stop and flag it."
 * - **No dedicated Success render state exists** — matching Key Take Flow's identical shape,
 *   a successful return calls `onCompleted()` immediately with no intermediate success screen
 *   to reskin.
 * - `checkoutSummary`'s card ("Checked out by X · Ym ago", built in `TerminalAdminApp.kt` from
 *   `TerminalCheckoutStore`) gets the same light `1.dp` `outlineVariant` border used elsewhere in
 *   this sweep for visibility in light mode — it was a bare, borderless `SoftCard` before.
 * - `resolveReturningKey` itself has no presentation to reskin — it's a pure `ManagedKey?`
 *   lookup, no UI. Its actual on-screen control (the "Simulate key-card return" button) lives in
 *   `TerminalNfcCardLoginScreen.kt`, a different, unnamed screen — left untouched this pass
 *   (out of this task's named scope), though it already renders via already-theme-clean
 *   `SoftWaitPanel`/`SoftTextButton` and so incidentally still benefits from this same pass's
 *   `SoftWaitPanel` default-visibility fix (see that composable's own doc).
 * - `SessionIdle` (the continuous-session idle UI) and the Done button live in a separate file,
 *   `ReturnSessionScreen.kt` — not part of this file's `ReturnStage`, but close enough to this
 *   same feature (and explicitly named in the task's checklist) that it's treated as in-scope
 *   too; see that file's own Phase 9F note.
 */
@Composable
fun TerminalKeyReturnScreen(
    padding: PaddingValues,
    key: ManagedKey?,
    slot: KeySlot?,
    abandonAtEpochMillis: Long?,
    wrongSlotCandidateNodeAddresses: List<Int>,
    checkoutSummary: String?,
    doorCloseWarningTimeSeconds: Int,
    videoRecordingEnabled: Boolean,
    onBeginReturnFlow: (nodeAddress: Int, onDoorOpenConfirmed: () -> Unit, onFailure: (String) -> Unit) -> Unit,
    onPollInsertion: (
        nodeAddress: Int,
        abandonAtEpochMillis: Long,
        wrongSlotCandidateNodeAddresses: List<Int>,
        resolveKeyFobUid: (rawUid: String) -> Boolean,
        onInserted: () -> Unit,
        onWrongSlotDetected: (wrongNodeAddress: Int, confirmedKey: Boolean) -> Unit,
        onWrongSlotCleared: () -> Unit,
        onLouderBeepThreshold: () -> Unit,
        onAbandoned: () -> Unit,
        onFailure: (String) -> Unit,
    ) -> Unit,
    resolveKeyFobUid: (rawUid: String) -> Boolean,
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
    var wrongSlotNodeAddress by remember { mutableStateOf<Int?>(null) }
    var wrongSlotConfirmedKey by remember { mutableStateOf(false) }

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
                    wrongSlotCandidateNodeAddresses,
                    resolveKeyFobUid,
                    {
                        beepLoud = false
                        wrongSlotNodeAddress = null
                        wrongSlotConfirmedKey = false
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
                    { wrongNodeAddress, confirmedKey ->
                        wrongSlotNodeAddress = wrongNodeAddress
                        wrongSlotConfirmedKey = confirmedKey
                    },
                    {
                        wrongSlotNodeAddress = null
                        wrongSlotConfirmedKey = false
                    },
                    {
                        beepLoud = true
                        audio.playVoiceLine(VoiceLine.PLEASE_INSERT_THE_KEY)
                    },
                    {
                        beeping = false
                        wrongSlotNodeAddress = null
                        wrongSlotConfirmedKey = false
                        stage = ReturnStage.Abandoned
                        onEvent(ReturnFlowOutcome.Abandoned(key, slot))
                    },
                    { message ->
                        beeping = false
                        wrongSlotNodeAddress = null
                        wrongSlotConfirmedKey = false
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

    // Keyed on `beeping` alone — neither `beepLoud` nor `wrongSlotNodeAddress` may restart
    // this loop. Same bug as the Take Flow screen: changing beepLoud used to be a
    // LaunchedEffect key, so the 5s-escalation callback (which flips beepLoud and fires the
    // voice line together) cancelled and relaunched this coroutine right at that moment.
    // rememberUpdatedState lets each iteration read the live values without that
    // cancel/relaunch; wrong-slot detection still forces full-volume beep on top of the
    // normal 5s-from-door-open escalation, restoring to whatever beepLoud already was once
    // the wrong-slot clears.
    val currentBeepLoud by rememberUpdatedState(beepLoud)
    val currentWrongSlotNodeAddress by rememberUpdatedState(wrongSlotNodeAddress)
    LaunchedEffect(beeping) {
        while (beeping) {
            audio.beep(loud = currentBeepLoud || currentWrongSlotNodeAddress != null)
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

            wrongSlotNodeAddress?.let { wrongNodeAddress ->
                // Phase 9F: the most urgent card on the screen, matching the hardware's own
                // red-light/full-volume-beep intensity — a `2.dp` `colorScheme.error` border +
                // `4.dp` elevation, the same escalation weight Take Flow's `strongAttention`
                // uses, reusing the alarm/danger tone this card's `errorContainer` fill already
                // used (not a new tone tier). The icon is the only new visual distinguishing
                // "Wrong key" from "Unrecognized item" — wording already did; both keep the
                // identical alarm tone and card treatment, per the established "equally
                // anomalous" design intent.
                SoftCard(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentPadding = 16.dp,
                    elevation = 4.dp,
                    border = BorderStroke(2.dp, MaterialTheme.colorScheme.error),
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(
                            imageVector = if (wrongSlotConfirmedKey) Icons.Filled.VpnKey else Icons.AutoMirrored.Filled.HelpOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Text(
                            text = if (wrongSlotConfirmedKey) {
                                "Wrong key — node $wrongNodeAddress"
                            } else {
                                "Unrecognized item — node $wrongNodeAddress"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                    Text(
                        text = if (wrongSlotConfirmedKey) {
                            "That is not the key this scan asked for. Remove it and insert the correct key."
                        } else {
                            "Something is in that slot that isn't the expected key. Remove it and insert the correct key."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            // Status-ring tone follows the stage 1:1 — Failed/Abandoned are the
            // Danger (alarm) tone, everything else mid-flow is Warning
            // (attention/door-open), matching the design spec's color meaning
            // rather than a bespoke per-screen color choice. An active wrong-slot
            // warning also escalates an otherwise-normal waiting stage to alarm.
            val tone = when {
                wrongSlotNodeAddress != null -> StatusTone.ALARM
                stage is ReturnStage.Failed || stage is ReturnStage.Abandoned -> StatusTone.ALARM
                else -> StatusTone.ATTENTION
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
