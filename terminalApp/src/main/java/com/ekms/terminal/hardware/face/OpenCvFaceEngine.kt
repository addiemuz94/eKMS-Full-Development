package com.ekms.terminal.hardware.face

import android.content.Context
import org.opencv.android.OpenCVLoader
import org.opencv.core.Core
import org.opencv.core.Size
import org.opencv.dnn.Dnn
import org.opencv.objdetect.FaceDetectorYN
import org.opencv.objdetect.FaceRecognizerSF

/**
 * Ported unchanged from `../eKMSHardwareTester`'s `face/OpenCvFaceEngine.kt` — YuNet detection +
 * SFace recognition are decision-independent (needed regardless of which liveness approach is
 * chosen; see CLAUDE.md's face-enrollment tradeoff note). `create` is a real, blocking
 * model-load call — invoke it off the UI thread, same as the tester's `FaceDiagnosticActivity`.
 */
class OpenCvFaceEngine private constructor(
    val detector: FaceDetectorYN,
    val recognizer: FaceRecognizerSF,
    val modelPaths: FaceModelStore.ModelPaths,
    val openCvVersion: String,
) {
    companion object {
        fun create(context: Context): OpenCvFaceEngine {
            check(OpenCVLoader.initLocal()) { "OpenCV native library could not be initialized." }

            val modelPaths = FaceModelStore.prepare(context)

            val detector = FaceDetectorYN.create(
                modelPaths.yuNetPath,
                "",
                Size(320.0, 320.0),
                0.85f,
                0.30f,
                5000,
                Dnn.DNN_BACKEND_OPENCV,
                Dnn.DNN_TARGET_CPU,
            )

            val recognizer = FaceRecognizerSF.create(
                modelPaths.sFacePath,
                "",
                Dnn.DNN_BACKEND_OPENCV,
                Dnn.DNN_TARGET_CPU,
            )

            return OpenCvFaceEngine(
                detector = detector,
                recognizer = recognizer,
                modelPaths = modelPaths,
                openCvVersion = Core.getVersionString(),
            )
        }
    }
}
