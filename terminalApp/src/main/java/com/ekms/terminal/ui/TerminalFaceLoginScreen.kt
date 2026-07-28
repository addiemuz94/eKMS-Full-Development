package com.ekms.terminal.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraManager
import android.view.Surface
import android.view.TextureView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.ekms.terminal.hardware.face.FaceCameraController
import com.ekms.terminal.hardware.face.FaceDetectionOverlayView
import com.ekms.terminal.hardware.face.FaceLoginPhase
import com.ekms.terminal.hardware.face.FaceProfileStore
import kotlinx.coroutines.delay

private const val FRAME_WIDTH = 640
private const val FRAME_HEIGHT = 360
private const val DETECTION_INTERVAL_MILLIS = 350L

/**
 * Face login — new work (Phase 3). Reuses the exact camera/model/liveness/embedding-extraction
 * pipeline [FaceEnrollmentScreen] uses via the same [FaceCameraController] instance shape (this
 * screen owns its own controller instance, same as enrollment owns its own — they are never
 * shown at once, so there's no sharing/lifecycle conflict), just driving [FaceCameraController.startLogin]/
 * [FaceCameraController.loginPhase]/[FaceCameraController.cancelLogin] instead of the enrollment
 * equivalents. Requires the same [com.ekms.terminal.hardware.face.ActiveHeadTurnLivenessChallenge]
 * liveness pass enrollment already requires before accepting a match attempt — a login that
 * skipped liveness would be trivially spoofable with a photo.
 *
 * A Vendor is never a legitimate match candidate here since Phase 1 already hard-excludes Vendor
 * from face *enrollment* — there is nothing to add on the login side, a Vendor's face was simply
 * never saved to [FaceProfileStore] in the first place.
 */
@Composable
fun TerminalFaceLoginScreen(
    padding: PaddingValues,
    faceProfileStore: FaceProfileStore,
    onBack: () -> Unit,
    onMatched: (userId: String, profile: FaceProfileStore.FaceProfile, similarity: Float) -> Unit,
) {
    val context = LocalContext.current

    var loginPhase by remember { mutableStateOf<FaceLoginPhase>(FaceLoginPhase.Idle) }

    val cameraManager = remember { context.getSystemService(Context.CAMERA_SERVICE) as CameraManager }
    val overlayView = remember { FaceDetectionOverlayView(context) }
    val textureView = remember { TextureView(context) }

    val controller = remember {
        FaceCameraController(
            context = context,
            faceProfileStore = faceProfileStore,
            onPhaseChanged = {},
            onFacesDetected = { width, height, faces -> overlayView.updateFaces(width, height, faces) },
            onLoginPhaseChanged = { next -> loginPhase = next },
        )
    }

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> hasCameraPermission = granted }

    LaunchedEffect(loginPhase) {
        val currentPhase = loginPhase
        if (currentPhase is FaceLoginPhase.Matched) {
            onMatched(currentPhase.userId, currentPhase.profile, currentPhase.similarity)
        }
    }

    DisposableEffect(controller) {
        controller.startCameraThread()
        onDispose { controller.close() }
    }

    LaunchedEffect(textureView, hasCameraPermission) {
        if (!hasCameraPermission) return@LaunchedEffect
        // Same TextureView-availability race fix as FaceEnrollmentScreen — see that file's doc
        // for why both the up-front check and the listener are both needed.
        val existingSurfaceTexture = textureView.surfaceTexture
        if (textureView.isAvailable && existingSurfaceTexture != null) {
            existingSurfaceTexture.setDefaultBufferSize(FRAME_WIDTH, FRAME_HEIGHT)
            controller.attachSurface(cameraManager, Surface(existingSurfaceTexture))
        }
        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                surface.setDefaultBufferSize(FRAME_WIDTH, FRAME_HEIGHT)
                controller.attachSurface(cameraManager, Surface(surface))
            }

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                controller.detachSurface()
                return true
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
        }
    }

    LaunchedEffect(controller) {
        while (true) {
            delay(DETECTION_INTERVAL_MILLIS)
            val processable = loginPhase is FaceLoginPhase.Liveness || loginPhase is FaceLoginPhase.Capturing
            if (processable && textureView.isAvailable) {
                val bitmap = textureView.getBitmap(FRAME_WIDTH, FRAME_HEIGHT)
                if (bitmap != null) controller.submitFrame(bitmap)
            }
        }
    }

    fun startLogin() {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
            return
        }
        controller.startLogin()
    }

    val busy = loginPhase is FaceLoginPhase.LoadingModels ||
        loginPhase is FaceLoginPhase.Liveness ||
        loginPhase is FaceLoginPhase.Capturing

    TerminalPage(padding) {
        BackButton(onBack = { controller.cancelLogin(); onBack() }, enabled = !busy)
        HeaderCard(
            title = "Facial recognition",
            description = "Complete the liveness check (a random head turn), then hold still " +
                "for the camera to confirm your face.",
        )

        if (hasCameraPermission) {
            Box(modifier = Modifier.fillMaxWidth().height(220.dp)) {
                AndroidView(factory = { textureView }, modifier = Modifier.fillMaxWidth().height(220.dp))
                AndroidView(factory = { overlayView }, modifier = Modifier.fillMaxWidth().height(220.dp))
            }
        } else {
            Text(
                text = "Camera permission is required for face login.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        Text(text = loginPhaseStatusText(loginPhase), style = MaterialTheme.typography.bodyMedium)

        if (busy) {
            OutlinedButton(onClick = { controller.cancelLogin() }, modifier = Modifier.fillMaxWidth()) {
                Text("Cancel")
            }
        } else {
            OutlinedButton(
                onClick = ::startLogin,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    when (loginPhase) {
                        FaceLoginPhase.NoMatch -> "Try again"
                        is FaceLoginPhase.Failed -> "Try again"
                        else -> "Start facial recognition"
                    },
                )
            }
        }
    }
}

private fun loginPhaseStatusText(phase: FaceLoginPhase): String = when (phase) {
    FaceLoginPhase.Idle -> "Ready when you are."
    FaceLoginPhase.LoadingModels -> "Loading face models…"
    is FaceLoginPhase.Liveness -> phase.message
    is FaceLoginPhase.Capturing -> phase.message
    is FaceLoginPhase.Matched -> "Face recognized."
    FaceLoginPhase.NoMatch -> "Face not recognized. Try again or choose a different method."
    is FaceLoginPhase.Failed -> phase.message
}
