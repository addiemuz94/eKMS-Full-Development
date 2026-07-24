package com.ekms.terminal.hardware.face

import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.objdetect.FaceRecognizerSF

/**
 * Ported unchanged from `../eKMSHardwareTester`'s `face/SFaceEmbeddingExtractor.kt` —
 * decision-independent (converts one YuNet detection into an SFace embedding regardless of
 * liveness approach). Temporary OpenCV objects are released before returning; the caller
 * retains ownership of the input frame, only the numeric embedding is returned.
 */
class SFaceEmbeddingExtractor {

    fun extract(recognizer: FaceRecognizerSF, bgrFrame: Mat, yuNetFace: FloatArray): FloatArray {
        require(!bgrFrame.empty()) { "Camera frame must not be empty." }
        require(yuNetFace.size >= YUNET_FACE_VALUE_COUNT) {
            "YuNet face data must contain at least $YUNET_FACE_VALUE_COUNT values."
        }

        val faceBox = Mat(1, YUNET_FACE_VALUE_COUNT, CvType.CV_32FC1)
        val alignedFace = Mat()
        val feature = Mat()

        try {
            faceBox.put(0, 0, yuNetFace.copyOf(YUNET_FACE_VALUE_COUNT))
            recognizer.alignCrop(bgrFrame, faceBox, alignedFace)
            recognizer.feature(alignedFace, feature)

            val valueCount = feature.total().toInt() * feature.channels()
            require(valueCount > 0) { "SFace returned an empty embedding." }

            return FloatArray(valueCount).also { embedding -> feature.get(0, 0, embedding) }
        } finally {
            feature.release()
            alignedFace.release()
            faceBox.release()
        }
    }

    private companion object {
        const val YUNET_FACE_VALUE_COUNT = 15
    }
}
