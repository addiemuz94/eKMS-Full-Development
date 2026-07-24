package com.ekms.terminal.hardware.face

import android.content.Context
import android.graphics.Bitmap
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Surface
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.Size as CvSize
import org.opencv.imgproc.Imgproc
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns RGB camera preview (Camera2, camera ID `"1"`, confirmed against `../eKMSHardwareTester`'s
 * `CameraDiagnosticActivity`), the OpenCV/MediaPipe model lifecycle, the active-liveness
 * challenge, and the 5-sample enrollment capture loop — parallel in spirit to
 * [com.ekms.terminal.hardware.FingerprintHardwareController]'s ownership of the R503 serial
 * port, but necessarily shaped differently: Camera2 needs a live [Surface] from the hosting
 * screen's `TextureView` rather than owning its own transport end-to-end, so this controller
 * exposes [attachSurface]/[detachSurface] instead of a single `connect()`.
 *
 * **RGB-only active liveness for v1** (single-gesture random head-turn challenge) — the user's
 * explicit, confirmed decision after reviewing the RGB-vs-RGB+IR tradeoff (see CLAUDE.md's
 * face-enrollment note). This is a real, not hypothetical, weaker anti-spoof tier than the vendor
 * manual's RGB+IR spec (section 4.8.3/4.8.4, not available in this repo) — planned as a future
 * upgrade, not presented as equivalent to it. Originally a blink+head-turn sequence; the blink
 * gate was removed after live hardware testing showed it unreliable against the frame-pump's
 * polling interval — see [ActiveHeadTurnLivenessChallenge]'s class doc for the full reasoning.
 *
 * Scope: enrollment only. No verification/login matching is wired here — [FaceVerificationSession]
 * from the tester was deliberately not ported, same "enrollment only" scope Part B's fingerprint
 * work already established.
 */
class FaceCameraController(
    context: Context,
    private val faceProfileStore: FaceProfileStore,
    private val onPhaseChanged: (FaceEnrollmentPhase) -> Unit,
    private val onFacesDetected: (frameWidth: Int, frameHeight: Int, faces: List<FaceDetectionOverlayView.DetectedFace>) -> Unit,
) {
    companion object {
        const val RGB_CAMERA_ID = "1"
        private const val MIN_FACE_CONFIDENCE = 0.90f
        private const val MIN_FACE_SIZE_PX = 120f
        private const val EDGE_MARGIN_PX = 8f
        private const val YUNET_FACE_VALUE_COUNT = 15
        private const val LIVENESS_PASS_VALID_MILLIS = 60_000L
    }

    private data class FaceCandidate(
        val overlayFace: FaceDetectionOverlayView.DetectedFace,
        val yuNetFace: FloatArray,
    )

    private val appContext = context.applicationContext
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val embeddingExtractor = SFaceEmbeddingExtractor()
    private val livenessChallenge = ActiveHeadTurnLivenessChallenge()

    private var faceEngine: OpenCvFaceEngine? = null
    private var landmarker: MediaPipeFaceLandmarkerEngine? = null
    private var enrollmentSession: FaceTemplateEnrollmentSession? = null

    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null

    @Volatile private var profileId: String? = null
    @Volatile private var lastLivenessPassedAtMillis: Long? = null

    /** Skips a frame rather than queueing it if processing hasn't caught up — matches the
     * tester's own `detectionBusy` guard, since the single-thread [executor] would otherwise
     * build an unbounded backlog under sustained slow inference. */
    private val frameBusy = AtomicBoolean(false)

    @Volatile
    var phase: FaceEnrollmentPhase = FaceEnrollmentPhase.Idle
        private set(value) {
            field = value
            mainHandler.post { onPhaseChanged(value) }
        }

    /** Starts a camera background thread. Call once when the hosting screen appears. */
    fun startCameraThread() {
        if (cameraThread != null) return
        val thread = HandlerThread("eKMS-FaceCamera").also { it.start() }
        cameraThread = thread
        cameraHandler = Handler(thread.looper)
    }

    fun stopCameraThread() {
        detachSurface()
        val thread = cameraThread ?: return
        thread.quitSafely()
        runCatching { thread.join() }
        cameraThread = null
        cameraHandler = null
    }

    /** Opens the RGB camera against a [Surface] built from the hosting `TextureView`'s texture. */
    fun attachSurface(cameraManager: CameraManager, surface: Surface) {
        val handler = cameraHandler ?: return
        if (cameraDevice != null) return
        try {
            @Suppress("MissingPermission")
            cameraManager.openCamera(
                RGB_CAMERA_ID,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        cameraDevice = camera
                        createPreviewSession(camera, surface, handler)
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        camera.close()
                        if (cameraDevice === camera) cameraDevice = null
                        phase = FaceEnrollmentPhase.Failed("RGB camera disconnected.")
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        camera.close()
                        if (cameraDevice === camera) cameraDevice = null
                        phase = FaceEnrollmentPhase.Failed("RGB camera error: $error")
                    }
                },
                handler,
            )
        } catch (error: Exception) {
            phase = FaceEnrollmentPhase.Failed("Could not open RGB camera: ${error.detail()}")
        }
    }

    fun detachSurface() {
        cancel()
        runCatching { captureSession?.stopRepeating() }
        captureSession?.close()
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
    }

    private fun createPreviewSession(camera: CameraDevice, surface: Surface, handler: Handler) {
        try {
            val requestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            requestBuilder.addTarget(surface)
            requestBuilder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)

            camera.createCaptureSession(
                listOf(surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        try {
                            session.setRepeatingRequest(requestBuilder.build(), null, handler)
                            phase = FaceEnrollmentPhase.PreviewActive
                        } catch (error: Exception) {
                            phase = FaceEnrollmentPhase.Failed("RGB preview failed: ${error.detail()}")
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        session.close()
                        phase = FaceEnrollmentPhase.Failed("RGB preview session configuration failed.")
                    }
                },
                handler,
            )
        } catch (error: Exception) {
            phase = FaceEnrollmentPhase.Failed("RGB preview could not start: ${error.detail()}")
        }
    }

    /** Begins the liveness challenge for [userId]; enrollment capture starts automatically once it passes. */
    fun startEnrollment(userId: String) {
        profileId = userId
        enrollmentSession = FaceTemplateEnrollmentSession()
        lastLivenessPassedAtMillis = null
        phase = FaceEnrollmentPhase.LoadingModels
        executor.execute {
            try {
                if (faceEngine == null) faceEngine = OpenCvFaceEngine.create(appContext)
                if (landmarker == null) landmarker = MediaPipeFaceLandmarkerEngine.create(appContext)
                val update = livenessChallenge.start(System.currentTimeMillis())
                phase = FaceEnrollmentPhase.Liveness(update.message)
            } catch (error: Exception) {
                phase = FaceEnrollmentPhase.Failed("Could not load face models: ${error.detail()}")
            }
        }
    }

    fun cancel() {
        profileId = null
        enrollmentSession?.reset()
        enrollmentSession = null
        livenessChallenge.cancel()
        lastLivenessPassedAtMillis = null
        if (phase !is FaceEnrollmentPhase.Failed) {
            phase = FaceEnrollmentPhase.Idle
        }
    }

    /** Full teardown for when the owning screen leaves composition. */
    fun close() {
        detachSurface()
        stopCameraThread()
        executor.execute {
            landmarker?.close()
            landmarker = null
            faceEngine = null
        }
        executor.shutdown()
    }

    /**
     * Called by the hosting screen's own frame-pump loop with a bitmap grabbed from the
     * `TextureView` (must be captured on the main thread; processing happens here, off it).
     * Ownership of [bitmap] transfers to this call — it is always recycled before returning.
     */
    fun submitFrame(bitmap: Bitmap) {
        val engine = faceEngine
        val currentPhase = phase
        val processable = currentPhase is FaceEnrollmentPhase.Liveness || currentPhase is FaceEnrollmentPhase.Enrolling
        if (engine == null || !processable || !frameBusy.compareAndSet(false, true)) {
            bitmap.recycle()
            return
        }

        executor.execute {
            var rgba: Mat? = null
            var bgr: Mat? = null
            var faceMat: Mat? = null
            try {
                rgba = Mat()
                bgr = Mat()
                faceMat = Mat()
                Utils.bitmapToMat(bitmap, rgba)
                Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)

                engine.detector.setInputSize(CvSize(bgr.cols().toDouble(), bgr.rows().toDouble()))
                engine.detector.detect(bgr, faceMat)
                val candidates = parseFaceCandidates(faceMat)
                val frameWidth = bgr.cols()
                val frameHeight = bgr.rows()

                mainHandler.post { onFacesDetected(frameWidth, frameHeight, candidates.map { it.overlayFace }) }

                when (phase) {
                    is FaceEnrollmentPhase.Liveness -> handleLivenessFrame(bitmap)
                    is FaceEnrollmentPhase.Enrolling -> handleEnrollmentFrame(engine, bgr, candidates)
                    else -> {}
                }
            } catch (error: Exception) {
                phase = FaceEnrollmentPhase.Failed("Face processing failed: ${error.detail()}")
            } finally {
                faceMat?.release()
                bgr?.release()
                rgba?.release()
                bitmap.recycle()
                frameBusy.set(false)
            }
        }
    }

    /**
     * Runs on [executor], called from within [submitFrame]'s try block — `bitmap` is still
     * valid at this point (recycled only in [submitFrame]'s `finally`, after this returns).
     */
    private fun handleLivenessFrame(bitmap: Bitmap) {
        val engine = landmarker ?: return
        val summary = engine.inspect(bitmap)
        val nowMillis = System.currentTimeMillis()

        val update = if (summary.hasExactlyOneFace && summary.headTurnScore != null) {
            livenessChallenge.consume(summary.headTurnScore, nowMillis)
        } else {
            livenessChallenge.consume(null, nowMillis)
        }

        when (update.state) {
            ActiveHeadTurnLivenessChallenge.State.PASSED -> {
                lastLivenessPassedAtMillis = nowMillis
                phase = FaceEnrollmentPhase.Enrolling(capturedSamples = 0, requiredSamples = FaceTemplateEnrollmentSession.DEFAULT_REQUIRED_SAMPLES, message = "Liveness passed. Hold one clear face still.")
            }

            ActiveHeadTurnLivenessChallenge.State.FAILED -> {
                phase = FaceEnrollmentPhase.Failed(update.message)
            }

            else -> {
                phase = FaceEnrollmentPhase.Liveness(update.message)
            }
        }
    }

    /** Runs on [executor]. `bgr` is owned by [submitFrame]'s caller and released there — do not release it here. */
    private fun handleEnrollmentFrame(engine: OpenCvFaceEngine, bgr: Mat, candidates: List<FaceCandidate>) {
        val session = enrollmentSession ?: return
        val userId = profileId ?: return

        val passedAt = lastLivenessPassedAtMillis
        if (passedAt == null || System.currentTimeMillis() - passedAt > LIVENESS_PASS_VALID_MILLIS) {
            phase = FaceEnrollmentPhase.Failed("Liveness result expired. Start again.")
            return
        }

        if (candidates.size != 1) {
            val issue = if (candidates.isEmpty()) "No face detected." else "Multiple faces detected."
            phase = FaceEnrollmentPhase.Enrolling(session.progress().capturedSamples, session.progress().requiredSamples, "$issue Keep exactly one face in view.")
            return
        }

        val candidate = candidates.first()
        if (!isFaceSuitable(candidate)) {
            phase = FaceEnrollmentPhase.Enrolling(session.progress().capturedSamples, session.progress().requiredSamples, "Move closer and keep your full face centred in view.")
            return
        }

        val embedding = embeddingExtractor.extract(engine.recognizer, bgr, candidate.yuNetFace)
        val progress = session.addSample(embedding)

        if (progress.isComplete) {
            val profile = faceProfileStore.save(profileId = userId, embedding = session.buildTemplate(), sampleCount = progress.capturedSamples)
            enrollmentSession = null
            this.profileId = null
            phase = FaceEnrollmentPhase.Succeeded(profile)
        } else {
            phase = FaceEnrollmentPhase.Enrolling(progress.capturedSamples, progress.requiredSamples, "Sample ${progress.capturedSamples}/${progress.requiredSamples} captured. Hold still for the next sample…")
        }
    }

    private fun isFaceSuitable(candidate: FaceCandidate): Boolean {
        val face = candidate.overlayFace
        val width = face.right - face.left
        val height = face.bottom - face.top
        val isLargeEnough = width >= MIN_FACE_SIZE_PX && height >= MIN_FACE_SIZE_PX
        return face.confidence >= MIN_FACE_CONFIDENCE && isLargeEnough
    }

    private fun parseFaceCandidates(faceMat: Mat): List<FaceCandidate> {
        val candidates = mutableListOf<FaceCandidate>()
        for (rowIndex in 0 until faceMat.rows()) {
            val values = FloatArray(faceMat.cols())
            faceMat.get(rowIndex, 0, values)
            if (values.size < YUNET_FACE_VALUE_COUNT) continue

            val confidence = values[14]
            if (confidence < 0.85f) continue

            val left = values[0]
            val top = values[1]
            val right = left + values[2]
            val bottom = top + values[3]

            candidates.add(
                FaceCandidate(
                    FaceDetectionOverlayView.DetectedFace(left, top, right, bottom, confidence),
                    values.copyOf(YUNET_FACE_VALUE_COUNT),
                ),
            )
        }
        return candidates
    }

    private fun Exception.detail(): String = message ?: javaClass.simpleName
}

sealed interface FaceEnrollmentPhase {
    data object Idle : FaceEnrollmentPhase
    data object LoadingModels : FaceEnrollmentPhase
    data object PreviewActive : FaceEnrollmentPhase
    data class Liveness(val message: String) : FaceEnrollmentPhase
    data class Enrolling(val capturedSamples: Int, val requiredSamples: Int, val message: String) : FaceEnrollmentPhase
    data class Succeeded(val profile: FaceProfileStore.FaceProfile) : FaceEnrollmentPhase
    data class Failed(val message: String) : FaceEnrollmentPhase
}
