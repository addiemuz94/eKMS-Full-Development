package com.ekms.terminal.hardware

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.util.Log
import com.ekms.terminal.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Real audio playback for the Key Take Flow's and Key Return Flow's
 * feedback behind CLAUDE.md's "Terminal App UX Baseline (Production)" §1/§2.
 *
 * [beep]/[playVoiceLine] remain the raw one-shot primitives; the cyclic take/return/
 * close-door audio pattern ([playCyclicUntil]) is built on top of them and is what every
 * Take/Return flow screen actually drives its phase audio through now — see
 * [playCyclicUntil]'s own doc for the pattern itself, and CLAUDE_TERMINAL.md for the full
 * before/after (it replaced the old "continuous 1s-interval beep the whole phase through,
 * plus a one-shot voice line at some trigger point" behavior).
 *
 * **Return Flow rewrite, Tier 2 (audio consolidation).** [playVoiceLine] and the old
 * `playVoiceLineSuspending` (a near-duplicate `MediaPlayer` setup, differing only in
 * whether the caller waited for completion) are now one implementation, [playVoiceLineSuspending] —
 * [playVoiceLine] is a thin fire-and-forget `scope.launch` wrapper over it, [playCyclicUntil]
 * calls it directly and awaits it. One code path for the hard-won `setAudioAttributes`-before-
 * `setDataSource`/`prepareAsync` ordering below, not two that could drift apart. **Enforcement is
 * last-caller-wins, no priority tiers**: every call — fire-and-forget or cyclic, Take or Return —
 * releases whatever is currently in [voiceLinePlayer] first (see [playVoiceLineSuspending]), so a
 * new request always interrupts whatever voice line was playing, regardless of which logical
 * phase issued it. This enforcement was previously only correct *within one instance* — see the
 * next paragraph for why that was the actual overlap bug, not the two-methods duality itself.
 *
 * **Also Tier 2: exactly one instance now exists app-wide**, hoisted once in `TerminalAdminApp`
 * and threaded down as a parameter everywhere audio is triggered (both `TerminalKeyTakeScreen`
 * and `TerminalKeyReturnScreen` used to `remember` their own instance, plus `TerminalAdminApp`
 * itself held two more, `returnSessionAudio`/`multiKeyTakeAudio` — four independent instances in
 * total, each with its own private [voiceLinePlayer]). [playVoiceLine]/[playVoiceLineSuspending]
 * already correctly cut each other off *within a single instance* before this pass — the actual
 * voice-line-overlap bug was that a per-node screen's own cyclic "insert the key"/"take the key"
 * playback and a *different* instance's session-level "please close the door" reminder (or the
 * multi-key queue's "please take your next key" one-shot) could each hold a live `MediaPlayer` at
 * the same moment, since nothing coordinated across separate instances. One shared instance means
 * one shared [voiceLinePlayer] field, so last-caller-wins now genuinely holds globally.
 *
 * The F7G18P has confirmed speaker hardware (8Ω/10W amp, PH2.0-4P SPK
 * connector) that plays back through standard Android audio APIs with no
 * special driver/EnjoySDK call. Both the beep and the voice lines are
 * played the same way — one-shot [MediaPlayer] instances, same pattern for
 * both — [beep]'s `loud` parameter only changes playback volume via
 * [MediaPlayer.setVolume], never swaps files. Beep previously used [SoundPool]
 * (chosen for its zero-latency pre-decoded playback, a better fit for a
 * tight repeat loop); switched to `MediaPlayer` to match the voice lines'
 * playback path exactly — note this reintroduces a real per-call decode/
 * prepare latency on every repeat that `SoundPool`'s pre-decoded model
 * didn't have, since a fresh `MediaPlayer` now prepares from scratch each
 * time rather than replaying an already-decoded buffer.
 *
 * Neither playback path requests audio focus: this is a dedicated kiosk
 * terminal with no other app ever competing for the speaker, so skipping
 * focus requests is deliberate — it guarantees a beep and a voice line
 * never duck or pause each other, which matters even within one
 * [playCyclicUntil] cycle (a beep firing right as the next cycle's voice
 * line starts, at the caller's phase boundary) let alone across the two
 * fully separate cyclic players that can run concurrently in a return
 * session (this node's own Phase 1 insert-cycle plus the session-level
 * Phase 2 close-door cycle from a different node's completed cycle).
 *
 * Even with both now on `MediaPlayer`, [beepAudioAttributes] and
 * [voiceLineAudioAttributes] remain deliberately distinct instances, and
 * beep and voice lines remain fully separate `MediaPlayer` instances
 * ([beepPlayer]/[voiceLinePlayer]) — sharing one `AudioAttributes`
 * instance between them was the confirmed cause of the beep dying out a
 * few seconds into a take/return (right around when the first voice line
 * starts) on this device's non-stock `awplayer`/CedarX HAL, consistent
 * with the HAL grouping/preempting streams that share identical
 * usage/content-type. That fix is independent of which engine plays the
 * beep and still applies here.
 *
 * [playClick] (added for app-wide UI tap feedback) originally played a custom WAV via
 * [SoundPool] with its own [AudioAttributes] pair; superseded by
 * [AudioManager.playSoundEffect] with [AudioManager.FX_KEYPRESS_STANDARD] — the platform's own
 * built-in click (the same one Android's on-screen keyboard/UI uses) on `STREAM_SYSTEM`. This
 * sidesteps the custom asset's clipping/duration risk entirely (there is no asset), and is a
 * completely different code path from beep/voice-line's `MediaPlayer`/`AudioAttributes` usage —
 * no app-owned `SoundPool`/`MediaPlayer`/`AudioAttributes` instance is involved at all for a
 * click — which plausibly also sidesteps the `awplayer`/CedarX HAL's stream-grouping bug that's
 * hit beep/voice twice, but that is reasoning, not a hardware-confirmed result; it still needs
 * the same Take/Return Flow interference check as the WAV-based version would have.
 */
class AudioFeedbackController(context: Context) {
    private val appContext = context.applicationContext

    private val beepAudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    // Deliberately distinct from beepAudioAttributes (see class KDoc) — sharing one
    // AudioAttributes instance between the beep and every voice-line MediaPlayer was the
    // confirmed cause of the beep dying out on this device's non-stock awplayer/CedarX HAL
    // when a voice line starts.
    private val voiceLineAudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    // Backs playClick() only — AudioManager.playSoundEffect() needs no AudioAttributes/
    // SoundPool/preload of our own; the platform already owns and preloads FX_KEYPRESS_STANDARD.
    private val audioManager = appContext.getSystemService(AudioManager::class.java)

    private var beepPlayer: MediaPlayer? = null
    private var voiceLinePlayer: MediaPlayer? = null

    /** Backs [playVoiceLine]'s fire-and-forget wrapper over [playVoiceLineSuspending] — a plain
     * (non-suspend) caller can't be assumed to already be inside a coroutine (e.g. the multi-key
     * Take queue's advance callback, invoked directly from a hardware-thread `mainHandler.post`,
     * not from a `LaunchedEffect`), so this class owns its own scope rather than requiring one.
     * Main-dispatcher, matching every other call in this class (all `MediaPlayer` calls/callbacks
     * already run on the main thread) — cancelled in [release]. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * Plays the beep clip once at [loud]'s volume. The caller (the Key
     * Take/Return Flow screens) already owns the 1-second repeat interval
     * via a `while (beeping) { beep(...); delay(1_000) }` loop — this
     * method is intentionally a single one-shot play per call, not an
     * internal loop, so it stays in lockstep with that timer. A new call
     * interrupts and replaces any still-playing beep from a *previous*
     * call, same "cut off, don't overlap" behavior [playVoiceLine] already
     * has — beeps are short and 1 second apart, so overlap isn't expected
     * in normal operation.
     */
    fun beep(loud: Boolean) {
        val volume = if (loud) LOUD_VOLUME else NORMAL_VOLUME
        Log.d(LOG_TAG, "beep(loud=$loud) requested")
        releaseBeepPlayer()
        val player = MediaPlayer()
        try {
            player.setAudioAttributes(beepAudioAttributes)
            appContext.resources.openRawResourceFd(R.raw.beep).use { afd ->
                player.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            }
            player.setVolume(volume, volume)
            player.setOnPreparedListener { prepared ->
                Log.d(LOG_TAG, "beep(loud=$loud) prepared -> starting")
                prepared.start()
            }
            player.setOnCompletionListener { completed ->
                Log.d(LOG_TAG, "beep(loud=$loud) completed")
                completed.release()
                if (beepPlayer === completed) beepPlayer = null
            }
            player.setOnErrorListener { failed, what, extra ->
                Log.w(LOG_TAG, "beep(loud=$loud) playback error (what=$what, extra=$extra)")
                failed.release()
                if (beepPlayer === failed) beepPlayer = null
                true
            }
            beepPlayer = player
            player.prepareAsync()
        } catch (error: Exception) {
            Log.w(LOG_TAG, "Unable to prepare beep(loud=$loud)", error)
            player.release()
            if (beepPlayer === player) beepPlayer = null
        }
    }

    /**
     * Plays a voice line once, fire-and-forget — a thin wrapper launching [playVoiceLineSuspending]
     * without awaiting it, for callers that aren't already inside a coroutine (e.g. the multi-key
     * Take queue's advance callback, or Return's `MORE_KEY_RETURN` hold). A new call interrupts
     * and replaces any still-playing voice line from a *previous* call — see [playVoiceLineSuspending]'s
     * own doc for the enforcement, now a single implementation both this and [playCyclicUntil]
     * share (Tier 2 merge — was two near-duplicate `MediaPlayer` setups, `playVoiceLine` and the
     * old `playVoiceLineSuspending`, differing only in whether the caller awaited
     * completion). Only a concurrently-running [beep] (a fully separate [MediaPlayer] instance,
     * [beepPlayer]) is unaffected by this.
     */
    fun playVoiceLine(line: VoiceLine) {
        scope.launch { playVoiceLineSuspending(line) }
    }

    /**
     * The take/return/close-door cyclic audio pattern: plays [voiceLine] to completion, then
     * [beepCount] beeps [BEEP_INTERVAL_MILLIS] apart (the same cadence the old continuous
     * loop used), then repeats — until [until] returns true. Replaces the old "continuous
     * 1s-interval beep the whole phase through, plus a one-shot voice line at some trigger
     * point" behavior across all 4 Take/Return flow variants (single/multi-key Take,
     * single/multi-key Return) — see CLAUDE_TERMINAL.md for the full before/after.
     *
     * [until] is checked between every unit of work — right after the voice line finishes,
     * and before/after each individual beep — not just at whole-cycle boundaries, so playback
     * stops as soon as possible once the condition is met rather than finishing out a lap.
     * [loud] is invoked fresh before every beep (not captured once at call time) so a live
     * escalation — e.g. the wrong-key/wrong-slot alarm's forced full volume, which stays
     * completely separate from and untouched by this cyclic pattern itself — can change the
     * next beep's volume without needing to cancel/restart the cycle.
     *
     * The other half of "cancellable/restartable" (needed for e.g. the multi-key return
     * session's per-scan Door-Close Warning Time reset) is ordinary structured concurrency:
     * callers run this inside a `LaunchedEffect` keyed on whatever should restart the cycle —
     * cancelling that coroutine (a new key value, or the composable leaving composition) stops
     * this immediately at its current suspension point (mid voice-line playback included, via
     * [playVoiceLineSuspending]'s cancellation handling), and a fresh call starts a
     * brand new cycle from the voice line.
     */
    suspend fun playCyclicUntil(
        voiceLine: VoiceLine,
        beepCount: Int = DEFAULT_CYCLE_BEEP_COUNT,
        loud: () -> Boolean = { false },
        until: () -> Boolean,
    ) {
        while (!until()) {
            playVoiceLineSuspending(voiceLine)
            if (until()) return
            for (i in 0 until beepCount) {
                if (until()) return
                beep(loud = loud())
                if (until()) return
                delay(BEEP_INTERVAL_MILLIS)
            }
        }
    }

    /**
     * The single voice-line playback implementation (Tier 2 merge — replaces the old duplicate
     * `playVoiceLine`/`playVoiceLineAwaitingCompletion` `MediaPlayer` setups). Deliberately built
     * as `MediaPlayer()` + [MediaPlayer.setAudioAttributes] + `setDataSource` + `prepareAsync()`,
     * NOT the `MediaPlayer.create(...)` convenience method: `create()` opens the data source and
     * prepares the player internally *before* handing back an instance to call
     * `setAudioAttributes` on — on this device's vendor audio pipeline (custom `awplayer`/CedarX
     * stack, not stock AOSP) that ordering was confirmed live to silently route to an inaudible
     * output: playback completed cleanly (start → EOS → stop, matching sample rate/channel count
     * in the decoder logs) with no audible sound. Setting attributes before the data
     * source/prepare call, the officially documented-safe order, is what actually gets honored by
     * that pipeline.
     *
     * Suspends until playback actually completes (or errors; either way this resumes, it never
     * hangs the caller) — [playCyclicUntil] calls this directly and awaits it (needs to know
     * exactly when the voice line finishes before starting its beeps); [playVoiceLine] launches it
     * fire-and-forget instead. **Enforcement, shared by every caller now that this is the one
     * implementation and one shared instance exists app-wide (see class doc): releases whatever
     * is currently in [voiceLinePlayer] first — a new request, from anywhere, always interrupts
     * whatever voice line was playing (last-caller-wins, no priority tiers).** Cancellation (the
     * cyclic loop stopping mid-line) stops and releases the player immediately rather than
     * letting it finish out loud after the caller has moved on.
     */
    private suspend fun playVoiceLineSuspending(line: VoiceLine) = suspendCancellableCoroutine<Unit> { cont ->
        val resId = when (line) {
            VoiceLine.PLEASE_TAKE_THE_KEY -> R.raw.please_take_the_key
            VoiceLine.PLEASE_INSERT_THE_KEY -> R.raw.please_insert_the_key
            VoiceLine.PLEASE_CLOSE_THE_DOOR -> R.raw.please_close_the_door
            VoiceLine.MORE_KEY_RETURN -> R.raw.more_key_return
            VoiceLine.PLEASE_TAKE_YOUR_NEXT_KEY -> R.raw.please_take_your_next_key
        }
        Log.d(LOG_TAG, "playVoiceLineSuspending($line) requested")
        releaseVoiceLinePlayer()
        val player = MediaPlayer()
        fun finish() {
            player.release()
            if (voiceLinePlayer === player) voiceLinePlayer = null
            if (cont.isActive) cont.resume(Unit) { _, _, _ -> }
        }
        try {
            player.setAudioAttributes(voiceLineAudioAttributes)
            appContext.resources.openRawResourceFd(resId).use { afd ->
                player.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            }
            player.setOnPreparedListener { prepared ->
                Log.d(LOG_TAG, "playVoiceLineSuspending($line) prepared -> starting")
                prepared.start()
            }
            player.setOnCompletionListener {
                Log.d(LOG_TAG, "playVoiceLineSuspending($line) completed")
                finish()
            }
            player.setOnErrorListener { _, what, extra ->
                Log.w(LOG_TAG, "playVoiceLineSuspending($line) playback error (what=$what, extra=$extra)")
                finish()
                true
            }
            voiceLinePlayer = player
            cont.invokeOnCancellation {
                runCatching { if (player.isPlaying) player.stop() }
                player.release()
                if (voiceLinePlayer === player) voiceLinePlayer = null
            }
            player.prepareAsync()
        } catch (error: Exception) {
            Log.w(LOG_TAG, "Unable to prepare voice line $line (cyclic)", error)
            player.release()
            if (voiceLinePlayer === player) voiceLinePlayer = null
            if (cont.isActive) cont.resume(Unit) { _, _, _ -> }
        }
    }

    /**
     * Plays the platform's built-in click ([AudioManager.FX_KEYPRESS_STANDARD] on
     * `STREAM_SYSTEM` — the same sound Android's own on-screen keyboard/UI uses), fire-and-
     * forget. Safe to call in rapid succession — the platform owns the sample and its own
     * playback pool, unlike [beep]/[playVoiceLine]'s single-instance "cut off and replace"
     * `MediaPlayer` model.
     *
     * No-ops silently (a real Android behavior, not a bug in this method) if the device's
     * `Settings.System.SOUND_EFFECTS_ENABLED` is off — confirmed `1` (enabled) by default on
     * the real F7G18P; not toggled or requested by this app (no `WRITE_SETTINGS`), per instruction
     * not to add that permission speculatively. See CLAUDE_TERMINAL.md Known Issues if this
     * ever needs revisiting.
     */
    fun playClick() {
        audioManager?.playSoundEffect(AudioManager.FX_KEYPRESS_STANDARD)
    }

    /**
     * Releases both players and cancels [scope] (so an in-flight fire-and-forget [playVoiceLine]
     * launch doesn't keep running past this call). Tier 2: this instance is now hoisted once at
     * `TerminalAdminApp`'s top level rather than `remember`-ed per screen, so this fires from that
     * one call site's `DisposableEffect.onDispose` (app-lifetime, not per-screen) — still the same
     * "don't leak a still-playing `MediaPlayer`" contract, just a longer-lived owner.
     * [playClick] owns no releasable resource of its own — `AudioManager` is a shared system
     * service, not something this class allocates.
     */
    fun release() {
        releaseBeepPlayer()
        releaseVoiceLinePlayer()
        scope.cancel()
    }

    private fun releaseBeepPlayer() {
        beepPlayer?.let { player ->
            runCatching { if (player.isPlaying) player.stop() }
            player.release()
        }
        beepPlayer = null
    }

    private fun releaseVoiceLinePlayer() {
        voiceLinePlayer?.let { player ->
            runCatching { if (player.isPlaying) player.stop() }
            player.release()
        }
        voiceLinePlayer = null
    }

    private companion object {
        const val LOG_TAG = "AudioFeedbackController"
        const val NORMAL_VOLUME = 0.6f
        const val LOUD_VOLUME = 1.0f
        // Centralized here (was duplicated as a private constant in both
        // TerminalKeyTakeScreen.kt and TerminalKeyReturnScreen.kt's own continuous-beep
        // loops before the cyclic take/return/close-door audio pattern) — same 1s cadence,
        // now the single source of truth for playCyclicUntil's inter-beep gap.
        const val BEEP_INTERVAL_MILLIS = 1_000L
        const val DEFAULT_CYCLE_BEEP_COUNT = 3
    }
}

enum class VoiceLine {
    PLEASE_TAKE_THE_KEY,
    PLEASE_INSERT_THE_KEY,
    PLEASE_CLOSE_THE_DOOR,
    /** One-shot only (via [AudioFeedbackController.playVoiceLine], never [AudioFeedbackController.playCyclicUntil])
     * — played once on every confirmed Return insertion, ahead of the 5-second scan-or-close-door
     * check. See `TerminalAdminApp.kt`'s `pendingMoreKeyReturnCheckId`. */
    MORE_KEY_RETURN,
    /** One-shot only, same reasoning as [MORE_KEY_RETURN] — played once per key taken in a
     * multi-key Take queue, except the last one. See `TerminalAdminApp.kt`'s
     * `MultiKeyTakeQueue` advance site (`onKeyRemoved`). */
    PLEASE_TAKE_YOUR_NEXT_KEY,
}
