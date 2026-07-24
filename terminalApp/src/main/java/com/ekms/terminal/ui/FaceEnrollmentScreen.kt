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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.ekms.terminal.data.TerminalUser
import com.ekms.terminal.hardware.face.FaceCameraController
import com.ekms.terminal.hardware.face.FaceDetectionOverlayView
import com.ekms.terminal.hardware.face.FaceEnrollmentPhase
import com.ekms.terminal.hardware.face.FaceProfileStore
import kotlinx.coroutines.delay

private const val FRAME_WIDTH = 640
private const val FRAME_HEIGHT = 360
private const val DETECTION_INTERVAL_MILLIS = 350L

/**
 * Super Admin-only guided face enrollment (reached via [openAdmin]'s existing gate, same pattern
 * [CardEnrollmentScreen]/`FingerprintEnrollmentScreen` already use). Scope: ENROLLMENT only — no
 * verification/login matching is wired here.
 *
 * **RGB-only active liveness for v1** (blink + head-turn challenge) — the user's explicit,
 * confirmed decision. This is a real, not hypothetical, weaker anti-spoof tier than the vendor
 * manual's RGB+IR spec; see [FaceCameraController]'s class doc and CLAUDE.md's face-enrollment
 * tradeoff note for the reasoning and the planned future upgrade.
 *
 * Camera preview opens automatically once permission is granted so the operator can frame
 * themselves before starting; the liveness challenge and 5-sample capture only run once
 * "Enroll face" is tapped. Only an encrypted numeric template is ever stored — no photo, image,
 * or video frame is retained anywhere ([FaceProfileStore] holds ciphertext only).
 */
@Composable
fun FaceEnrollmentScreen(
    padding: PaddingValues,
    users: List<TerminalUser>,
    notice: String?,
    faceProfileStore: FaceProfileStore,
    onBack: () -> Unit,
    onEnrollmentSaved: (userId: String, profile: FaceProfileStore.FaceProfile) -> Unit,
    onRevoke: (userId: String) -> Unit,
) {
    val context = LocalContext.current
    var selectedUserId by remember(users) { mutableStateOf(users.firstOrNull()?.id.orEmpty()) }
    val selectedUser = users.firstOrNull { it.id == selectedUserId }
    val existing = selectedUser?.let { faceProfileStore.load(it.id) }

    var phase by remember { mutableStateOf<FaceEnrollmentPhase>(FaceEnrollmentPhase.Idle) }

    val cameraManager = remember { context.getSystemService(Context.CAMERA_SERVICE) as CameraManager }
    val overlayView = remember { FaceDetectionOverlayView(context) }
    val textureView = remember { TextureView(context) }

    val controller = remember {
        FaceCameraController(
            context = context,
            faceProfileStore = faceProfileStore,
            onPhaseChanged = { next -> phase = next },
            onFacesDetected = { width, height, faces -> overlayView.updateFaces(width, height, faces) },
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

    // Reports a successful save to the backend exactly once per Succeeded phase instance.
    LaunchedEffect(phase, selectedUser?.id) {
        val currentPhase = phase
        val user = selectedUser
        if (currentPhase is FaceEnrollmentPhase.Succeeded && user != null) {
            onEnrollmentSaved(user.id, currentPhase.profile)
        }
    }

    DisposableEffect(controller) {
        controller.startCameraThread()
        onDispose { controller.close() }
    }

    LaunchedEffect(textureView, hasCameraPermission) {
        if (!hasCameraPermission) return@LaunchedEffect
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

    // Frame-pump loop: only actually grabs/submits a bitmap while liveness or enrollment
    // capture is running — idle preview costs no per-frame CPU beyond the camera's own preview.
    LaunchedEffect(controller) {
        while (true) {
            delay(DETECTION_INTERVAL_MILLIS)
            val processable = phase is FaceEnrollmentPhase.Liveness || phase is FaceEnrollmentPhase.Enrolling
            if (processable && textureView.isAvailable) {
                val bitmap = textureView.getBitmap(FRAME_WIDTH, FRAME_HEIGHT)
                if (bitmap != null) controller.submitFrame(bitmap)
            }
        }
    }

    fun startEnrollment() {
        val user = selectedUser ?: return
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
            return
        }
        controller.startEnrollment(user.id)
    }

    fun revokeSelected() {
        val user = selectedUser ?: return
        if (existing == null) return
        onRevoke(user.id)
    }

    val busy = phase is FaceEnrollmentPhase.LoadingModels ||
        phase is FaceEnrollmentPhase.Liveness ||
        phase is FaceEnrollmentPhase.Enrolling

    TerminalPage(padding) {
        BackButton(onBack = { controller.cancel(); onBack() }, enabled = !busy)
        HeaderCard(
            title = "Face enrollment",
            description = "Select a person, then complete the liveness check (blink + a random " +
                "head turn) before the camera captures five samples. Only an encrypted numeric " +
                "template is stored — no photo or video is ever saved.",
        )
        notice?.let { message -> SuperAdminNoticeCard(message) }

        Text("Personnel record", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        if (users.isEmpty()) {
            Text("Add personnel before enrolling a face.")
        } else {
            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { selectedUserId = nextFaceUserId(selectedUserId, users) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                ) {
                    Text(
                        (selectedUser?.let { it.displayName + " · " + it.role.label } ?: "Select personnel") +
                            " · change",
                    )
                }
            }
            Text(
                text = if (existing != null) "Currently enrolled." else "No face currently enrolled.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (hasCameraPermission) {
            Box(modifier = Modifier.fillMaxWidth().height(220.dp)) {
                AndroidView(factory = { textureView }, modifier = Modifier.fillMaxWidth().height(220.dp))
                AndroidView(factory = { overlayView }, modifier = Modifier.fillMaxWidth().height(220.dp))
            }
        } else {
            Text(
                text = "Camera permission is required for face enrollment.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        Text(text = phaseStatusText(phase), style = MaterialTheme.typography.bodyMedium)

        if (busy) {
            OutlinedButton(onClick = { controller.cancel() }, modifier = Modifier.fillMaxWidth()) {
                Text("Cancel")
            }
        } else {
            Button(
                onClick = ::startEnrollment,
                modifier = Modifier.fillMaxWidth(),
                enabled = selectedUser != null,
            ) {
                Text(if (existing != null) "Re-enroll face" else "Enroll face")
            }
            OutlinedButton(
                onClick = ::revokeSelected,
                modifier = Modifier.fillMaxWidth(),
                enabled = selectedUser != null && existing != null,
            ) {
                Text("Revoke this record's face")
            }
        }
    }
}

private fun phaseStatusText(phase: FaceEnrollmentPhase): String = when (phase) {
    FaceEnrollmentPhase.Idle -> "Ready."
    FaceEnrollmentPhase.LoadingModels -> "Loading face models…"
    FaceEnrollmentPhase.PreviewActive -> "Camera ready."
    is FaceEnrollmentPhase.Liveness -> phase.message
    is FaceEnrollmentPhase.Enrolling -> phase.message
    is FaceEnrollmentPhase.Succeeded -> "Face enrolled successfully."
    is FaceEnrollmentPhase.Failed -> phase.message
}

private fun nextFaceUserId(currentUserId: String, users: List<TerminalUser>): String {
    if (users.isEmpty()) return ""
    val index = users.indexOfFirst { it.id == currentUserId }
    return users[(index + 1 + users.size) % users.size].id
}
