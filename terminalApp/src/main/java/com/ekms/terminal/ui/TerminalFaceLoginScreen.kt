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
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.ekms.terminal.hardware.face.ActiveHeadTurnLivenessChallenge
import com.ekms.terminal.hardware.face.FaceCameraController
import com.ekms.terminal.hardware.face.FaceDetectionOverlayView
import com.ekms.terminal.hardware.face.FaceLoginPhase
import com.ekms.terminal.hardware.face.FaceProfileStore
import com.ekms.terminal.ui.theme.LocalEkmsColors
import kotlinx.coroutines.delay

private const val FRAME_WIDTH = 640
private const val FRAME_HEIGHT = 360
private const val DETECTION_INTERVAL_MILLIS = 350L

/**
 * Camera preview height as a fraction of the device's total screen height, computed from
 * [LocalConfiguration] rather than a fixed dp guess or `Modifier.fillMaxHeight(fraction)` —
 * the latter doesn't work here since this screen's content sits inside [TerminalPage]'s
 * `LazyColumn` (unbounded height for scrolling), so there is no bounded parent height for
 * `fillMaxHeight` to take a fraction of. `screenHeightDp` stays correct on this project's
 * confirmed-portrait 600x1024dp panel (see CLAUDE_TERMINAL.md's layout-audit notes) the same way
 * the boot splash logo's `fillMaxWidth(fraction)` does, without hitting that constraint problem.
 * 0.4f (~410dp on the real device, up from the old fixed 220dp) is an easy-to-retune placeholder,
 * not a measured/final value — leaves room below for the header/guide/status/button without
 * forcing a scroll on first render.
 */
private const val PREVIEW_HEIGHT_FRACTION = 0.4f

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
 *
 * **Auto-start (UI/trigger-timing pass)**: capture begins automatically on screen entry rather
 * than waiting for a manual tap — see the `LaunchedEffect(hasCameraPermission)` below for the
 * keying rationale. The button lower on the screen is now a *retry* affordance only (relabeled
 * "Try again" after `NoMatch`/`Failed`), not the sole way to start.
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

    // Declared before any LaunchedEffect that calls it — local `fun`s resolve by source order,
    // not execution order (a real Kotlin gotcha this codebase has hit before, see CLAUDE.md's
    // Phase 3 postLoginRoute note).
    fun startLogin() {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
            return
        }
        controller.startLogin()
    }

    // Auto-start: begins face login automatically once the screen composes, replacing the old
    // manual "Start facial recognition" first tap. Keyed on hasCameraPermission ALONE —
    // deliberately, not on loginPhase or anything else this effect's body touches — because
    // that's the one piece of state this effect actually needs to react to (a permission-grant
    // callback flipping it false->true). Runs once on screen entry when permission is already
    // granted (the common case after the first-ever grant), calling startLogin() immediately;
    // if not yet granted, it requests permission once, then this same effect fires exactly once
    // more when the callback flips the value, starting login at that point — never re-fires on a
    // value that hasn't actually changed. Same "key on the minimal state that should actually
    // restart, not everything the body reads" lesson as the beep-continuity LaunchedEffect
    // re-keying fix (see CLAUDE_TERMINAL.md) and the CardEnrollmentScreen PublicCardReaderController
    // fix in Completed history.
    LaunchedEffect(hasCameraPermission) {
        startLogin()
    }

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

    val busy = loginPhase is FaceLoginPhase.LoadingModels ||
        loginPhase is FaceLoginPhase.Liveness ||
        loginPhase is FaceLoginPhase.Capturing

    val screenHeightDp = LocalConfiguration.current.screenHeightDp.dp
    val previewHeight = screenHeightDp * PREVIEW_HEIGHT_FRACTION

    TerminalPage(padding) {
        BackButton(onBack = { controller.cancelLogin(); onBack() }, enabled = !busy)
        HeaderCard(
            title = "Facial recognition",
            description = "Complete the liveness check (a random head turn), then hold still " +
                "for the camera to confirm your face.",
        )

        if (hasCameraPermission) {
            Box(modifier = Modifier.fillMaxWidth().height(previewHeight)) {
                AndroidView(factory = { textureView }, modifier = Modifier.fillMaxWidth().height(previewHeight))
                AndroidView(factory = { overlayView }, modifier = Modifier.fillMaxWidth().height(previewHeight))
            }
        } else {
            Text(
                text = "Camera permission is required for face login.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        val liveLiveness = loginPhase as? FaceLoginPhase.Liveness
        if (liveLiveness != null) {
            HeadTurnGuide(direction = liveLiveness.direction, progress = liveLiveness.progress)
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

/**
 * Head-turn guide UI (v1, flagged as an iterable placeholder — exact visual polish isn't the
 * point this pass, a working/readable signal is): a direction arrow + horizontal progress bar,
 * bound to [ActiveHeadTurnLivenessChallenge.Update.direction]/`.progress` forwarded through
 * [FaceLoginPhase.Liveness]. Color animates from the theme's warning tone toward success green
 * as [progress] approaches 1f (the pass threshold) — [progress] itself is display-only and has
 * no effect on the actual pass/fail decision, which still lives entirely in
 * [ActiveHeadTurnLivenessChallenge.consume].
 */
@Composable
private fun HeadTurnGuide(
    direction: ActiveHeadTurnLivenessChallenge.HeadTurnDirection,
    progress: Float,
) {
    val colors = LocalEkmsColors.current
    val clampedProgress = progress.coerceIn(0f, 1f)
    val animatedProgress by animateFloatAsState(targetValue = clampedProgress, label = "headTurnProgress")
    val animatedColor by animateColorAsState(
        targetValue = lerp(colors.warning, colors.success, clampedProgress),
        label = "headTurnProgressColor",
    )

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = if (direction == ActiveHeadTurnLivenessChallenge.HeadTurnDirection.LEFT) {
                Icons.AutoMirrored.Filled.KeyboardArrowLeft
            } else {
                Icons.AutoMirrored.Filled.KeyboardArrowRight
            },
            contentDescription = null,
            tint = animatedColor,
            modifier = Modifier.size(56.dp),
        )
        Text(
            text = if (direction == ActiveHeadTurnLivenessChallenge.HeadTurnDirection.LEFT) {
                "Turn your head left"
            } else {
                "Turn your head right"
            },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        LinearProgressIndicator(
            progress = { animatedProgress },
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp)
                .clip(RoundedCornerShape(6.dp)),
            color = animatedColor,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        )
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
