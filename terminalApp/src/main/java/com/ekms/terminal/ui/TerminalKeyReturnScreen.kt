package com.ekms.terminal.ui

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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ekms.shared.domain.ManagedKey
import com.ekms.terminal.hardware.AudioFeedbackController
import com.ekms.terminal.hardware.VideoRecordingController
import com.ekms.terminal.hardware.VoiceLine
import com.ekms.terminal.ui.returnflow.ReturnSession
import com.ekms.terminal.ui.theme.StatusTone

/**
 * Smart Key Cabinet User Manual V2.1, Section 3 — Key return — upgraded by the Key Return Flow
 * production enhancement (CLAUDE.md "Terminal App UX Baseline (Production)" §2).
 *
 * **Return Flow rewrite, Tier 3: this is now a pure renderer of [ReturnSessionController]'s own
 * state** ([state], one of [ReturnSession.Unlocking]/[ReturnSession.AwaitingInsertion]/
 * [ReturnSession.NodeOutcomeMessage] — the caller only shows this screen for those three; any
 * other value renders nothing meaningful). Before this rewrite, this screen owned its own
 * `LaunchedEffect(key, slot)` that directly drove [com.ekms.terminal.hardware.CabinetHardwareController]'s
 * hardware calls, plus local `stage`/`wrongKeyPresent` state duplicating what the controller now
 * tracks centrally. All of that moved into `ReturnSessionController` — this screen has zero
 * callbacks and zero interactive elements (unchanged from before this rewrite: this was already
 * true, confirmed via the original file's own audit), it only reads state and plays audio in
 * response.
 *
 * [ReturnSession.AwaitingInsertion.wrongKeyPresent]/`wrongKeyDisplayName` are fields on that one
 * state now, not separate local `var`s — the "active node identity gap" fix (0x17 verification
 * before locking, per-node 20s ceiling suspended while a wrong key sits in the slot) is unchanged
 * in the hardware layer, only where its result is tracked moved into the controller.
 *
 * [ReturnSession.NodeOutcomeMessage] (the 3s "Key return problem"/"Key return cancelled" message
 * after a Failed/Abandoned outcome) is a **correction to this rewrite's own Tier 1 pass** — see
 * that state's own doc in `ReturnSessionController.kt` for why it exists; the pre-rewrite
 * `ReturnStage.Failed`/`Abandoned` this replaces showed the identical message for the identical
 * 3 seconds.
 */
@Composable
fun TerminalKeyReturnScreen(
    padding: PaddingValues,
    state: ReturnSession,
    videoRecordingEnabled: Boolean,
    /** Return Flow rewrite, Tier 2: shared app-wide instance, hoisted once in `TerminalAdminApp`
     * and passed down here — this screen no longer `remember`s its own (see
     * `AudioFeedbackController`'s class doc for why that was the actual voice-line-overlap bug). */
    audio: AudioFeedbackController,
) {
    val videoRecorder = remember { VideoRecordingController() }

    DisposableEffect(videoRecordingEnabled) {
        if (videoRecordingEnabled) videoRecorder.start("key_return")
        onDispose { videoRecorder.stop() }
    }

    // Cyclic take/return/close-door audio pattern: this screen only ever drives Phase 1
    // (insert the key) — Phase 2 (session-level door-close) is owned by TerminalAdminApp's
    // shared `audio` instance now, driven off ReturnSessionController.doorCloseWarningActive, so
    // there is no WaitingForDoorClose-equivalent stage here to derive a second phase from.
    val insertion = state as? ReturnSession.AwaitingInsertion
    val audioPhase = if (insertion != null) ReturnAudioPhase.INSERT_THE_KEY else ReturnAudioPhase.NONE
    // Belt-and-suspenders alongside LaunchedEffect(audioPhase)'s own cancel-on-rekey, same
    // reasoning as the Take Flow screen's twin of this. The wrong-key alarm's forced-loud beep is
    // untouched and layers on top via `loud`, read fresh per beep so it can turn on/off mid-cycle
    // without restarting it.
    val currentAudioPhase by rememberUpdatedState(audioPhase)
    val currentWrongKeyPresent by rememberUpdatedState(insertion?.wrongKeyPresent == true)
    LaunchedEffect(audioPhase) {
        if (audioPhase == ReturnAudioPhase.INSERT_THE_KEY) {
            audio.playCyclicUntil(
                voiceLine = VoiceLine.PLEASE_INSERT_THE_KEY,
                loud = { currentWrongKeyPresent },
                until = { currentAudioPhase != ReturnAudioPhase.INSERT_THE_KEY },
            )
        }
    }

    val matchedKey: ManagedKey? = when (state) {
        is ReturnSession.Unlocking -> state.matchedKey
        is ReturnSession.AwaitingInsertion -> state.matchedKey
        is ReturnSession.NodeOutcomeMessage -> state.matchedKey
        else -> null
    }
    val checkoutSummary = insertion?.checkoutSummary
    val outcomeMessage = state as? ReturnSession.NodeOutcomeMessage

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
            if (checkoutSummary != null && outcomeMessage == null) {
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

            if (insertion?.wrongKeyPresent == true) {
                // Same alarm-tone recipe the old cross-node wrong-slot card used (and
                // ReturnSessionScreen's hard-block card still uses) — 2.dp colorScheme.error
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
                        text = "This is ${insertion.wrongKeyDisplayName ?: "an unrecognized key"} — remove it and insert " +
                            "${matchedKey?.displayName ?: "the correct key"}.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            // Status-ring tone follows the state 1:1 — NodeOutcomeMessage is the Danger (alarm)
            // tone, everything else mid-flow is Warning (attention/door-open), matching the
            // design spec's color meaning. An active wrong-key alarm also escalates an
            // otherwise-normal waiting state to alarm.
            val tone = when {
                insertion?.wrongKeyPresent == true -> StatusTone.ALARM
                outcomeMessage != null -> StatusTone.ALARM
                else -> StatusTone.ATTENTION
            }
            SoftWaitPanel(
                tone = tone,
                title = when (state) {
                    is ReturnSession.Unlocking -> "Opening door…"
                    is ReturnSession.AwaitingInsertion -> "Insert the key"
                    is ReturnSession.NodeOutcomeMessage ->
                        if (state.outcome == "ABANDONED") "Key return cancelled" else "Key return problem"
                    else -> ""
                },
                message = when (state) {
                    is ReturnSession.Unlocking, is ReturnSession.AwaitingInsertion ->
                        if (matchedKey != null) "Insert ${matchedKey.displayName} now." else "Insert the key now."
                    is ReturnSession.NodeOutcomeMessage ->
                        if (state.outcome == "ABANDONED") {
                            "No key was inserted in time. The slot has been secured."
                        } else {
                            state.failureMessage ?: "Key return failed."
                        }
                    else -> ""
                },
                showProgress = state is ReturnSession.Unlocking,
                assistText = if (state is ReturnSession.AwaitingInsertion) "Node unlocked" else null,
                assistAttention = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Which cyclic take/return/close-door audio phase (if any) should be playing right now — this
 * screen only ever drives [INSERT_THE_KEY] (Phase 2/door-close is session-level, owned by
 * `TerminalAdminApp`'s shared `audio` instance), but it's still a dedicated enum rather than
 * reading [ReturnSession] directly so the audio driver's `LaunchedEffect` key stays obviously
 * scoped to "audio-relevant phase" — e.g. a wrong-key field flipping on the same
 * `AwaitingInsertion` instance shouldn't look like a phase change, same reasoning the Take Flow
 * screen's twin of this already established. */
private enum class ReturnAudioPhase { NONE, INSERT_THE_KEY }
