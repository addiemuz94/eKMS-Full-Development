package com.ekms.terminal.hardware.face

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import java.io.Closeable
import kotlin.math.abs

/**
 * Ported unchanged from `../eKMSHardwareTester`'s `face/MediaPipeFaceLandmarkerEngine.kt` —
 * decision-independent: this is needed regardless of which liveness approach is chosen, since
 * it's also what produces the blink/head-turn signals the RGB-only active-challenge approach
 * uses (see CLAUDE.md's face-enrollment tradeoff note). CPU-based; camera images are processed
 * in memory by the caller and never persisted by this class.
 */
class MediaPipeFaceLandmarkerEngine private constructor(
    private val faceLandmarker: FaceLandmarker,
) : Closeable {

    data class FrameSummary(
        val faceCount: Int,
        val landmarkCount: Int,
        val blendshapeCount: Int,
        val leftEyeBlinkScore: Float?,
        val rightEyeBlinkScore: Float?,
        val headTurnScore: Float?,
    ) {
        val hasExactlyOneFace: Boolean get() = faceCount == 1

        /** Higher values represent more-closed eyes. Null means this frame lacked the blendshape value. */
        val combinedEyeBlinkScore: Float?
            get() = when {
                leftEyeBlinkScore == null || rightEyeBlinkScore == null -> null
                else -> (leftEyeBlinkScore + rightEyeBlinkScore) / 2f
            }
    }

    companion object {
        private const val MODEL_ASSET_PATH = "models/face_landmarker.task"
        private const val LEFT_EYE_BLINK = "eyeBlinkLeft"
        private const val RIGHT_EYE_BLINK = "eyeBlinkRight"

        fun create(context: Context): MediaPipeFaceLandmarkerEngine {
            val baseOptions = BaseOptions.builder()
                .setDelegate(Delegate.CPU)
                .setModelAssetPath(MODEL_ASSET_PATH)
                .build()

            val options = FaceLandmarker.FaceLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.IMAGE)
                .setNumFaces(1)
                .setMinFaceDetectionConfidence(0.80f)
                .setMinFacePresenceConfidence(0.80f)
                .setMinTrackingConfidence(0.80f)
                .setOutputFaceBlendshapes(true)
                .setOutputFacialTransformationMatrixes(true)
                .build()

            val faceLandmarker = FaceLandmarker.createFromOptions(context.applicationContext, options)
            return MediaPipeFaceLandmarkerEngine(faceLandmarker)
        }
    }

    /** Runs landmark detection on one bitmap. Call from a background executor, not the UI thread. */
    fun analyze(bitmap: Bitmap): FaceLandmarkerResult {
        val mpImage = BitmapImageBuilder(bitmap).build()
        return faceLandmarker.detect(mpImage)
    }

    fun inspect(bitmap: Bitmap): FrameSummary {
        val result = analyze(bitmap)

        val landmarks = result.faceLandmarks().firstOrNull()
        val faceCount = result.faceLandmarks().size
        val landmarkCount = landmarks?.size ?: 0

        val blendshapes = if (result.faceBlendshapes().isPresent) {
            result.faceBlendshapes().get().firstOrNull().orEmpty()
        } else {
            emptyList()
        }

        val leftEyeBlinkScore = blendshapes.firstOrNull { it.categoryName() == LEFT_EYE_BLINK }?.score()
        val rightEyeBlinkScore = blendshapes.firstOrNull { it.categoryName() == RIGHT_EYE_BLINK }?.score()

        val headTurnScore = landmarks?.let { faceLandmarks ->
            if (faceLandmarks.size <= 263) {
                null
            } else {
                val leftEyeOuterX = faceLandmarks[33].x()
                val rightEyeOuterX = faceLandmarks[263].x()
                val eyeCentreX = (leftEyeOuterX + rightEyeOuterX) / 2f
                val eyeDistance = abs(rightEyeOuterX - leftEyeOuterX)
                if (eyeDistance < 0.01f) null else (faceLandmarks[1].x() - eyeCentreX) / eyeDistance
            }
        }

        return FrameSummary(
            faceCount = faceCount,
            landmarkCount = landmarkCount,
            blendshapeCount = blendshapes.size,
            leftEyeBlinkScore = leftEyeBlinkScore,
            rightEyeBlinkScore = rightEyeBlinkScore,
            headTurnScore = headTurnScore,
        )
    }

    override fun close() {
        faceLandmarker.close()
    }
}
