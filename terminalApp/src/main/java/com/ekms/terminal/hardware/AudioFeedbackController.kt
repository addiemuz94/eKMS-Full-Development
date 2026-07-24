package com.ekms.terminal.hardware

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
import android.util.Log
import com.ekms.terminal.R

/**
 * Real audio playback for the Key Take Flow's and Key Return Flow's
 * feedback — continuous beep and one-shot voice lines — behind
 * CLAUDE.md's "Terminal App UX Baseline (Production)" §1/§2.
 *
 * The F7G18P has confirmed speaker hardware (8Ω/10W amp, PH2.0-4P SPK
 * connector) that plays back through standard Android audio APIs with no
 * special driver/EnjoySDK call — so this uses [SoundPool] for the beep
 * (one clip, [beep]'s `loud` parameter only changes the playback volume,
 * never swaps files — the 1-second repeat interval is owned by the
 * caller, same as the previous stub) and [MediaPlayer] for the one-shot
 * voice lines (short recorded/placeholder clips, too long to be a good
 * fit for SoundPool's decode-fully-into-memory model).
 *
 * Neither playback path requests audio focus: this is a dedicated kiosk
 * terminal with no other app ever competing for the speaker, so skipping
 * focus requests is deliberate — it guarantees the beep loop and a voice
 * line never duck or pause each other, which is required, since the
 * Key Take/Return Flow screens fire both concurrently (e.g. the 5s
 * louder-beep threshold and its voice line share one callback). SoundPool
 * and MediaPlayer are independent playback engines that the system mixer
 * combines on its own, so this "just works" without explicit
 * coordination between the two.
 */
class AudioFeedbackController(context: Context) {
    private val appContext = context.applicationContext

    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    private val soundPool = SoundPool.Builder()
        .setMaxStreams(MAX_CONCURRENT_BEEPS)
        .setAudioAttributes(audioAttributes)
        .build()

    private val beepSoundId = soundPool.load(appContext, R.raw.beep, /* priority = */ 1)
    private var beepLoaded = false

    private var voiceLinePlayer: MediaPlayer? = null

    init {
        soundPool.setOnLoadCompleteListener { _, sampleId, status ->
            if (sampleId == beepSoundId) {
                beepLoaded = status == 0
                if (status != 0) {
                    Log.w(LOG_TAG, "Beep sample failed to load (status=$status) — beep() will be a no-op.")
                }
            }
        }
    }

    /**
     * Plays the beep clip once at [loud]'s volume. The caller (the Key
     * Take/Return Flow screens) already owns the 1-second repeat interval
     * via a `while (beeping) { beep(...); delay(1_000) }` loop — this
     * method is intentionally a single one-shot play per call, not an
     * internal loop, so it stays in lockstep with that timer.
     */
    fun beep(loud: Boolean) {
        if (!beepLoaded) return
        val volume = if (loud) LOUD_VOLUME else NORMAL_VOLUME
        soundPool.play(beepSoundId, volume, volume, /* priority = */ 1, /* loop = */ 0, /* rate = */ 1f)
    }

    /**
     * Plays a voice line once. A new call interrupts and replaces any
     * still-playing voice line from a *previous* call (voice lines never
     * need to overlap each other — only the concurrently-running beep
     * loop, which uses the separate SoundPool engine above and is
     * unaffected by this).
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
        releaseVoiceLinePlayer()
        val player = MediaPlayer()
        try {
            player.setAudioAttributes(audioAttributes)
            appContext.resources.openRawResourceFd(resId).use { afd ->
                player.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            }
            player.setOnPreparedListener { prepared -> prepared.start() }
            player.setOnCompletionListener { completed ->
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
     * Releases both playback engines. Call when the owning screen leaves
     * composition (e.g. from a `DisposableEffect`'s `onDispose`) so a
     * take/return flow that's abandoned mid-beep doesn't leak a SoundPool
     * or a still-playing MediaPlayer past the screen's lifetime.
     */
    fun release() {
        soundPool.release()
        releaseVoiceLinePlayer()
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
        const val MAX_CONCURRENT_BEEPS = 2
        const val NORMAL_VOLUME = 0.6f
        const val LOUD_VOLUME = 1.0f
    }
}

enum class VoiceLine {
    PLEASE_TAKE_THE_KEY,
    PLEASE_INSERT_THE_KEY,
    PLEASE_CLOSE_THE_DOOR,
}
