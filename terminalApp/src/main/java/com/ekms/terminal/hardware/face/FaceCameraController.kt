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
 * Scope: enrollment, plus login matching (Phase 3) via [startLogin]/[cancelLogin] — the tester's
 * own [FaceVerificationSession] was never ported (deliberately, matching Part B's fingerprint
 * "enrollment only" scope at the time); this is a new implementation built directly against this
 * codebase's [FaceProfileStore]/[FaceTemplateEnrollmentSession], reusing the same camera/model/
 * liveness/embedding-extraction pipeline enrollment already uses rather than a second one.
 */
class FaceCameraController(
    context: Context,
    private val faceProfileStore: FaceProfileStore,
    private val onPhaseChanged: (FaceEnrollmentPhase) -> Unit,
    private val onFacesDetected: (frameWidth: Int, frameHeight: Int, faces: List<FaceDetectionOverlayView.DetectedFace>) -> Unit,
    private val onLoginPhaseChanged: (FaceLoginPhase) -> Unit = {},
) {
    companion object {
        const val RGB_CAMERA_ID = "1"
        private const val MIN_FACE_CONFIDENCE = 0.90f
        private const val MIN_FACE_SIZE_PX = 120f
        private const val EDGE_MARGIN_PX = 8f
        private const val YUNET_FACE_VALUE_COUNT = 15
        private const val LIVENESS_PASS_VALID_MILLIS = 60_000L

        /** Fewer than enrollment's 5-sample average, favoring login speed over the extra
         * robustness a larger average buys: 2 gives some smoothing against a single bad frame
         * (motion blur, a momentarily imperfect angle) while adding only about one more
         * frame-pump interval (~350ms) of latency than a single sample would. */
        const val LOGIN_REQUIRED_SAMPLES = 2

        /**
         * **TEMPORARY, UNCALIBRATED VALUE — requires real calibration with real enrolled users
         * during the hardware test phase, same as this file's RGB-only-liveness tradeoff.**
         * SFace-style embeddings are compared via cosine similarity; same-person pairs are
         * commonly reported around 0.35-0.4+ on standard face-verification benchmarks (the
         * threshold OpenCV's own SFace sample/documentation suggests as a starting point for
         * `FaceRecognizerSF.match(..., FaceRecognizerSF_FR_COSINE)`), with different-person pairs
         * well below that. 0.40 is picked as a deliberately conservative starting point — fewer
         * false accepts at the cost of more false rejects — appropriate for a security-relevant
         * login gate, not derived from this device's actual camera/lighting/sensor behavior,
         * which can only be established by testing against this project's real enrolled users on
         * real hardware. Do not treat this as a finished, validated value.
         */
        const val FACE_MATCH_SIMILARITY_THRESHOLD = 0.40f
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
    private var loginSession: FaceTemplateEnrollmentSession? = null

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

    @Volatile
    var loginPhase: FaceLoginPhase = FaceLoginPhase.Idle
        private set(value) {
            field = value
            mainHandler.post { onLoginPhaseChanged(value) }
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
        cancelLogin()
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

    /**
     * Begins face login: same liveness challenge instance and same model/embedding pipeline as
     * enrollment, but captures [LOGIN_REQUIRED_SAMPLES] samples (fewer than enrollment's 5, for
     * login speed) into their own [loginSession] rather than an enrollment one, and — once
     * captured — compares the resulting template against every locally-enrolled profile instead
     * of saving it. Mutually exclusive with [startEnrollment]/[phase]; this screen and
     * `FaceEnrollmentScreen` are never shown at the same time, so the two flows never actually
     * run concurrently against the shared camera/executor.
     */
    fun startLogin() {
        loginSession = FaceTemplateEnrollmentSession(requiredSamples = LOGIN_REQUIRED_SAMPLES)
        lastLivenessPassedAtMillis = null
        loginPhase = FaceLoginPhase.LoadingModels
        executor.execute {
            try {
                if (faceEngine == null) faceEngine = OpenCvFaceEngine.create(appContext)
                if (landmarker == null) landmarker = MediaPipeFaceLandmarkerEngine.create(appContext)
                val update = livenessChallenge.start(System.currentTimeMillis())
                loginPhase = FaceLoginPhase.Liveness(update.message, update.direction, update.progress)
            } catch (error: Exception) {
                loginPhase = FaceLoginPhase.Failed("Could not load face models: ${error.detail()}")
            }
        }
    }

    fun cancelLogin() {
        loginSession?.reset()
        loginSession = null
        livenessChallenge.cancel()
        lastLivenessPassedAtMillis = null
        if (loginPhase !is FaceLoginPhase.Failed) {
            loginPhase = FaceLoginPhase.Idle
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
        val currentLoginPhase = loginPhase
        val processable = currentPhase is FaceEnrollmentPhase.Liveness || currentPhase is FaceEnrollmentPhase.Enrolling ||
            currentLoginPhase is FaceLoginPhase.Liveness || currentLoginPhase is FaceLoginPhase.Capturing
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
                when (loginPhase) {
                    is FaceLoginPhase.Liveness -> handleLoginLivenessFrame(bitmap)
                    is FaceLoginPhase.Capturing -> handleLoginCaptureFrame(engine, bgr, candidates)
                    else -> {}
                }
            } catch (error: Exception) {
                if (phase is FaceEnrollmentPhase.Liveness || phase is FaceEnrollmentPhase.Enrolling) {
                    phase = FaceEnrollmentPhase.Failed("Face processing failed: ${error.detail()}")
                }
                if (loginPhase is FaceLoginPhase.Liveness || loginPhase is FaceLoginPhase.Capturing) {
                    loginPhase = FaceLoginPhase.Failed("Face processing failed: ${error.detail()}")
                }
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

    /**
     * Runs on [executor]. Thin login-side twin of [handleLivenessFrame] — same
     * [livenessChallenge] instance and the same state-machine logic, just publishing to
     * [loginPhase] instead of [phase] since the two flows have different published phase types.
     */
    private fun handleLoginLivenessFrame(bitmap: Bitmap) {
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
                loginPhase = FaceLoginPhase.Capturing(
                    capturedSamples = 0,
                    requiredSamples = LOGIN_REQUIRED_SAMPLES,
                    message = "Liveness passed. Hold one clear face still.",
                )
            }

            ActiveHeadTurnLivenessChallenge.State.FAILED -> {
                loginPhase = FaceLoginPhase.Failed(update.message)
            }

            else -> {
                loginPhase = FaceLoginPhase.Liveness(update.message, update.direction, update.progress)
            }
        }
    }

    /** Runs on [executor]. `bgr` is owned by [submitFrame]'s caller and released there — do not
     * release it here. Mirrors [handleEnrollmentFrame]'s capture logic exactly, but once complete
     * compares the built template against every enrolled profile instead of saving it. */
    private fun handleLoginCaptureFrame(engine: OpenCvFaceEngine, bgr: Mat, candidates: List<FaceCandidate>) {
        val session = loginSession ?: return

        val passedAt = lastLivenessPassedAtMillis
        if (passedAt == null || System.currentTimeMillis() - passedAt > LIVENESS_PASS_VALID_MILLIS) {
            loginPhase = FaceLoginPhase.Failed("Liveness result expired. Start again.")
            return
        }

        if (candidates.size != 1) {
            val issue = if (candidates.isEmpty()) "No face detected." else "Multiple faces detected."
            loginPhase = FaceLoginPhase.Capturing(session.progress().capturedSamples, session.progress().requiredSamples, "$issue Keep exactly one face in view.")
            return
        }

        val candidate = candidates.first()
        if (!isFaceSuitable(candidate)) {
            loginPhase = FaceLoginPhase.Capturing(session.progress().capturedSamples, session.progress().requiredSamples, "Move closer and keep your full face centred in view.")
            return
        }

        val embedding = embeddingExtractor.extract(engine.recognizer, bgr, candidate.yuNetFace)
        val progress = session.addSample(embedding)

        if (progress.isComplete) {
            val liveTemplate = session.buildTemplate()
            loginSession = null
            val match = findBestFaceMatch(liveTemplate)
            loginPhase = if (match != null) {
                FaceLoginPhase.Matched(match.userId, match.profile, match.similarity)
            } else {
                FaceLoginPhase.NoMatch
            }
        } else {
            loginPhase = FaceLoginPhase.Capturing(progress.capturedSamples, progress.requiredSamples, "Sample ${progress.capturedSamples}/${progress.requiredSamples} captured. Hold still for the next sample…")
        }
    }

    private data class FaceMatch(val userId: String, val profile: FaceProfileStore.FaceProfile, val similarity: Float)

    /**
     * Compares [liveEmbedding] against every locally-enrolled profile on this terminal
     * ([FaceProfileStore] is local-only by policy, so this is already correctly scoped to just
     * this device — no backend fetch). The best match above [FACE_MATCH_SIMILARITY_THRESHOLD]
     * wins; otherwise no match, never a "closest guess."
     */
    private fun findBestFaceMatch(liveEmbedding: FloatArray): FaceMatch? {
        var best: FaceMatch? = null
        for (profileId in faceProfileStore.listProfileIds()) {
            val profile = faceProfileStore.load(profileId) ?: continue
            val similarity = cosineSimilarity(liveEmbedding, profile.embedding)
            if (best == null || similarity > best.similarity) {
                best = FaceMatch(profileId, profile, similarity)
            }
        }
        return best?.takeIf { it.similarity >= FACE_MATCH_SIMILARITY_THRESHOLD }
    }

    /** Both [FaceTemplateEnrollmentSession.buildTemplate] outputs (live and stored) are already
     * L2-normalized, so this reduces to a plain dot product in practice — computed as full
     * cosine similarity anyway rather than assuming that invariant holds forever. */
    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size || a.isEmpty()) return -1f
        var dot = 0f
        var normA = 0f
        var normB = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        val denominator = kotlin.math.sqrt(normA) * kotlin.math.sqrt(normB)
        return if (denominator > 0f) dot / denominator else -1f
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

sealed interface FaceLoginPhase {
    data object Idle : FaceLoginPhase
    data object LoadingModels : FaceLoginPhase
    /**
     * [direction]/[progress] added (UI/trigger-timing pass, additive) so
     * [com.ekms.terminal.ui.TerminalFaceLoginScreen] can render a real guide indicator instead
     * of only [message]'s text — forwarded straight from
     * [ActiveHeadTurnLivenessChallenge.Update], see that class for what they mean. Deliberately
     * not mirrored onto [FaceEnrollmentPhase.Liveness] — out of scope this pass, only the login
     * screen was asked for.
     */
    data class Liveness(
        val message: String,
        val direction: ActiveHeadTurnLivenessChallenge.HeadTurnDirection,
        val progress: Float = 0f,
    ) : FaceLoginPhase
    data class Capturing(val capturedSamples: Int, val requiredSamples: Int, val message: String) : FaceLoginPhase
    data class Matched(val userId: String, val profile: FaceProfileStore.FaceProfile, val similarity: Float) : FaceLoginPhase
    /** A clean "no match" result (best similarity below the threshold, or no profiles enrolled at
     * all) — never fall through to another login method silently; the caller must show this
     * explicitly and let the user retry or pick a different method themselves. */
    data object NoMatch : FaceLoginPhase
    data class Failed(val message: String) : FaceLoginPhase
}
