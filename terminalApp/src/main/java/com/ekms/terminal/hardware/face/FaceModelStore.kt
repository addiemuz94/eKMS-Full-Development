package com.ekms.terminal.hardware.face

import android.content.Context
import java.io.File
import java.io.IOException

/**
 * Ported from `../eKMSHardwareTester`'s `face/FaceModelStore.kt` unchanged (asset-copy logic
 * has no dependency on the liveness decision — see CLAUDE.md's face-enrollment tradeoff note).
 * OpenCV's `FaceDetectorYN`/`FaceRecognizerSF` need a real filesystem path, not an asset stream,
 * so both `.onnx` models are copied out of `assets/models/` into app-private storage once.
 */
object FaceModelStore {
    private const val ASSET_FOLDER = "models"
    private const val YUNET_FILE = "face_detection_yunet_2023mar.onnx"
    private const val SFACE_FILE = "face_recognition_sface_2021dec.onnx"

    data class ModelPaths(
        val yuNetPath: String,
        val sFacePath: String,
    )

    fun prepare(context: Context): ModelPaths {
        val modelDirectory = File(context.noBackupFilesDir, "face-models")
        if (!modelDirectory.exists() && !modelDirectory.mkdirs()) {
            throw IOException("Unable to create face-model directory: ${modelDirectory.absolutePath}")
        }

        val yuNetFile = copyAssetIfNeeded(context, YUNET_FILE, modelDirectory)
        val sFaceFile = copyAssetIfNeeded(context, SFACE_FILE, modelDirectory)

        return ModelPaths(yuNetPath = yuNetFile.absolutePath, sFacePath = sFaceFile.absolutePath)
    }

    private fun copyAssetIfNeeded(context: Context, assetFileName: String, destinationDirectory: File): File {
        val destinationFile = File(destinationDirectory, assetFileName)
        if (destinationFile.exists() && destinationFile.length() > 0L) {
            return destinationFile
        }

        context.assets.open("$ASSET_FOLDER/$assetFileName").use { input ->
            destinationFile.outputStream().use { output -> input.copyTo(output) }
        }

        if (!destinationFile.exists() || destinationFile.length() == 0L) {
            throw IOException("Face model copy failed: $assetFileName")
        }
        return destinationFile
    }
}
