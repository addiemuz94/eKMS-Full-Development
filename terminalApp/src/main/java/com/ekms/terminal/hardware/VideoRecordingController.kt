package com.ekms.terminal.hardware

/**
 * Stand-in for the background video-recording hardware bridge behind the
 * Admin Menu's "Return key video" and "Key retrieval video" toggles.
 *
 * There is no camera integration yet: no CameraX/Camera2 dependency, no
 * CAMERA permission in the manifest, no capture pipeline. start()/stop()
 * are intentionally no-ops so the *wiring* (whether a recording session
 * would start, and when) is real and testable, without fabricating a fake
 * recording. Per the manual, recording must stay invisible to the operator
 * — callers must not add any icon, banner, or other on-screen
 * acknowledgment when using this controller.
 */
class VideoRecordingController {
    private var recording = false

    fun start(session: String) {
        if (recording) return
        recording = true
        // TODO: real capture pipeline (CameraX VideoCapture) goes here once
        // the camera hardware bridge and CAMERA permission are approved.
    }

    fun stop() {
        recording = false
        // TODO: finalize/discard the real capture session here.
    }
}
