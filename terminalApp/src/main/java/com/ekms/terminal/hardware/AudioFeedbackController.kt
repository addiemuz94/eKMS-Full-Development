package com.ekms.terminal.hardware

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import com.ekms.terminal.R

/**
 * Real audio playback for the Key Take Flow's and Key Return Flow's
 * feedback — continuous beep and one-shot voice lines — behind
 * CLAUDE.md's "Terminal App UX Baseline (Production)" §1/§2.
 *
 * The F7G18P has confirmed speaker hardware (8Ω/10W amp, PH2.0-4P SPK
 * connector) that plays back through standard Android audio APIs with no
 * special driver/EnjoySDK call. Both the beep and the voice lines are
 * played the same way — one-shot [MediaPlayer] instances, same pattern for
 * both — [beep]'s `loud` parameter only changes playback volume via
 * [MediaPlayer.setVolume], never swaps files, and the 1-second repeat
 * interval is owned by the caller via a `while (beeping) { beep(...);
 * delay(1_000) }` loop, same as before. Beep previously used [SoundPool]
 * (chosen for its zero-latency pre-decoded playback, a better fit for a
 * tight repeat loop); switched to `MediaPlayer` to match the voice lines'
 * playback path exactly — note this reintroduces a real per-call decode/
 * prepare latency on every repeat that `SoundPool`'s pre-decoded model
 * didn't have, since a fresh `MediaPlayer` now prepares from scratch each
 * time rather than replaying an already-decoded buffer.
 *
 * Neither playback path requests audio focus: this is a dedicated kiosk
 * terminal with no other app ever competing for the speaker, so skipping
 * focus requests is deliberate — it guarantees the beep loop and a voice
 * line never duck or pause each other, which is required, since the
 * Key Take/Return Flow screens fire both concurrently (e.g. the 5s
 * louder-beep threshold and its voice line share one callback).
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

    private var beepPlayer: MediaPlayer? = null
    private var voiceLinePlayer: MediaPlayer? = null

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
     * Plays a voice line once. A new call interrupts and replaces any
     * still-playing voice line from a *previous* call (voice lines never
     * need to overlap each other — only the concurrently-running beep
     * loop, which is a fully separate [MediaPlayer] instance ([beepPlayer])
     * and unaffected by this).
     *
     * Deliberately built as `MediaPlayer()` + [MediaPlayer.setAudioAttributes]
     * + `setDataSource` + `prepareAsync()`, NOT the `MediaPlayer.create(...)`
     * convenience method. `create()` opens the data source and prepares the
     * player internally *before* handing back an instance to call
     * `setAudioAttributes` on — on this device's vendor audio pipeline
     * (custom `awplayer`/CedarX stack, not stock AOSP) that ordering was
     * confirmed live to silently route to an inaudible output: playback
     * completed cleanly (start → EOS → stop, matching sample rate/channel
     * count in the decoder logs) with no audible sound. Setting attributes
     * before the data source/prepare call, the officially documented-safe
     * order, is what actually gets honored by that pipeline.
     */
    fun playVoiceLine(line: VoiceLine) {
        val resId = when (line) {
            VoiceLine.PLEASE_TAKE_THE_KEY -> R.raw.please_take_the_key
            VoiceLine.PLEASE_INSERT_THE_KEY -> R.raw.please_insert_the_key
            VoiceLine.PLEASE_CLOSE_THE_DOOR -> R.raw.please_close_the_door
        }
        Log.d(LOG_TAG, "playVoiceLine($line) requested")
        releaseVoiceLinePlayer()
        val player = MediaPlayer()
        try {
            player.setAudioAttributes(voiceLineAudioAttributes)
            appContext.resources.openRawResourceFd(resId).use { afd ->
                player.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            }
            player.setOnPreparedListener { prepared ->
                Log.d(LOG_TAG, "playVoiceLine($line) prepared -> starting")
                prepared.start()
            }
            player.setOnCompletionListener { completed ->
                Log.d(LOG_TAG, "playVoiceLine($line) completed")
                completed.release()
                if (voiceLinePlayer === completed) voiceLinePlayer = null
            }
            player.setOnErrorListener { failed, what, extra ->
                Log.w(LOG_TAG, "Voice line $line playback error (what=$what, extra=$extra)")
                failed.release()
                if (voiceLinePlayer === failed) voiceLinePlayer = null
                true
            }
            voiceLinePlayer = player
            player.prepareAsync()
        } catch (error: Exception) {
            Log.w(LOG_TAG, "Unable to prepare voice line $line", error)
            player.release()
            if (voiceLinePlayer === player) voiceLinePlayer = null
        }
    }

    /**
     * Releases both players. Call when the owning screen leaves composition
     * (e.g. from a `DisposableEffect`'s `onDispose`) so a take/return flow
     * that's abandoned mid-beep doesn't leak a still-playing `MediaPlayer`
     * past the screen's lifetime.
     */
    fun release() {
        releaseBeepPlayer()
        releaseVoiceLinePlayer()
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
    }
}

enum class VoiceLine {
    PLEASE_TAKE_THE_KEY,
    PLEASE_INSERT_THE_KEY,
    PLEASE_CLOSE_THE_DOOR,
}
