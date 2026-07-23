package com.ekms.terminal.hardware

/**
 * Stand-in for the Key Take Flow's and Key Return Flow's audio feedback —
 * continuous beep and voice lines — behind CLAUDE.md's "Terminal App UX
 * Baseline (Production)" §1/§2.
 *
 * There is no confirmed audio hardware/asset pipeline yet: no ToneGenerator
 * wiring, no recorded voice-line assets bundled. beep()/playVoiceLine() are
 * intentionally no-ops so the *timing* (when a beep or voice line would
 * fire, and at what volume) is real and testable, without fabricating fake
 * audio playback. Matches [VideoRecordingController]'s stub pattern.
 */
class AudioFeedbackController {
    fun beep(loud: Boolean) {
        // TODO: real ToneGenerator/SoundPool playback goes here once the
        // audio hardware/asset pipeline is approved. `loud` selects volume
        // only — the 1-second interval is owned by the caller.
    }

    fun playVoiceLine(line: VoiceLine) {
        // TODO: real recorded-voice-line playback goes here once the
        // audio asset pipeline is approved.
    }
}

enum class VoiceLine {
    PLEASE_TAKE_THE_KEY,
    PLEASE_INSERT_THE_KEY,
    PLEASE_CLOSE_THE_DOOR,
}
