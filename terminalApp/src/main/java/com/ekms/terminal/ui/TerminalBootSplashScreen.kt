package com.ekms.terminal.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.ekms.terminal.R
import com.ekms.terminal.hardware.CabinetHardwareState
import com.ekms.terminal.hardware.FingerprintHardwareState
import com.ekms.terminal.hardware.NetworkStatus
import com.ekms.terminal.hardware.PublicCardReaderState
import com.ekms.terminal.ui.theme.LocalEkmsColors
import kotlinx.coroutines.delay

/**
 * "~3 seconds" per a follow-up sizing/timing pass (was ~1 second in the original v1 design) —
 * still an easy-to-tune placeholder, not a spec'd value. Flag/change this single constant if it
 * feels too long or too short once seen running on real hardware.
 */
private const val MIN_SPLASH_DURATION_MILLIS = 3_000L

/**
 * Logo width as a fraction of the available screen width (~60-70% target from the sizing pass;
 * `aspectRatio` below derives height from [CAVOTEC_LOGO_ASPECT_RATIO] so the logo never
 * stretches/distorts regardless of what fraction this is). A fraction rather than a fixed dp
 * value deliberately — it should stay proportionally correct on this project's confirmed-portrait
 * 600x1024dp panel (see CLAUDE_TERMINAL.md's boot-splash Known Issues) without needing a new
 * hardcoded number if that ever changes.
 */
private const val LOGO_WIDTH_FRACTION = 0.65f

/** `cavotec_logo.png`'s real pixel dimensions (839x147) — used so [aspectRatio] never distorts it. */
private const val CAVOTEC_LOGO_ASPECT_RATIO = 839f / 147f

/**
 * Animated boot splash, shown at the exact trigger point [StartupDiagnosticsScreen] used to own
 * directly — cold launch / crash-recovery relaunch, the same `rememberSaveable`-gated
 * `showStartupDiagnostics` scope in [TerminalAdminApp] (unchanged; this composable does not
 * touch that gating, only what renders while it's true).
 *
 * Runs the same [startupDiagnosticChecks] silently underneath the animation — the checks
 * themselves are untouched, only how/when their results are surfaced changes here. Navigates
 * forward ([onContinue]) once BOTH: every check is [HintSeverity.OK], AND at least
 * [MIN_SPLASH_DURATION_MILLIS] (~3s) has elapsed since this screen first appeared — so a
 * fast-passing cabinet never flashes the splash for one frame, and a slow-connecting one never
 * cuts the animation short; if checks take longer than the floor, the loop simply keeps running
 * until they finish, same as before. On any [HintSeverity.FAIL], the loop stops and
 * [BootWarningCard] replaces the animation instead of navigating.
 *
 * Displayed mode (splash vs. warning) is derived fresh from the live check results on every
 * recomposition rather than latched into separate state — so a successful [onRetryHardware] (or
 * the auto-recheck-on-resume below) clears the warning card by itself once the underlying
 * hardware state actually changes; no explicit "go back to splash" transition is needed.
 *
 * Non-blocking is preserved deliberately: [StartupDiagnosticsScreen]'s own doc has always said
 * "never blocks sign-in" (e.g. a camera-less device would otherwise show FAIL on every single
 * cold launch forever, with Retry unable to do anything about a permanently absent camera) — see
 * [BootWarningCard]'s subtle "Continue anyway" link.
 */
@Composable
fun TerminalBootSplashScreen(
    padding: PaddingValues,
    hardwareState: CabinetHardwareState,
    fingerprintHardwareState: FingerprintHardwareState,
    cameraAvailable: Boolean,
    publicCardReaderState: PublicCardReaderState,
    networkStatus: NetworkStatus,
    onRetryHardware: () -> Unit,
    onContinue: () -> Unit,
) {
    val checks = startupDiagnosticChecks(
        hardwareState = hardwareState,
        fingerprintHardwareState = fingerprintHardwareState,
        cameraAvailable = cameraAvailable,
        publicCardReaderState = publicCardReaderState,
        networkStatus = networkStatus,
    )
    val anyFailed = checks.any { it.severity == HintSeverity.FAIL }
    val allHealthy = checks.all { it.severity == HintSeverity.OK }
    // Same "last check is network" positional convention StartupDiagnosticsScreen's own
    // `checks.dropLast(1)` already relies on — startupDiagnosticChecks's fixed ordering, not a
    // new rule, so this isn't reintroducing detection logic that lives elsewhere.
    val networkFailed = checks.last().severity == HintSeverity.FAIL

    var minDurationElapsed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(MIN_SPLASH_DURATION_MILLIS)
        minDurationElapsed = true
    }

    LaunchedEffect(allHealthy, minDurationElapsed) {
        if (allHealthy && minDurationElapsed) onContinue()
    }

    // Auto-recheck on resume (e.g. returning from Settings.ACTION_WIFI_SETTINGS) instead of
    // requiring a manual Retry tap. rememberUpdatedState so the observer always calls the
    // latest onRetryHardware without needing to be torn down/recreated on every recomposition.
    val latestRetry by rememberUpdatedState(onRetryHardware)
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) latestRetry()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        contentAlignment = Alignment.Center,
    ) {
        if (anyFailed) {
            BootWarningCard(
                checks = checks,
                networkFailed = networkFailed,
                onRetry = onRetryHardware,
                onContinueAnyway = onContinue,
            )
        } else {
            BootSplashAnimation()
        }
    }
}

/**
 * v1 design, flagged as an iterable placeholder (not a spec locked in stone): logo scales up
 * from ~0.7x with a fade-in on first appearance, then loops a subtle breathing pulse
 * (1.0x-1.05x) for as long as this composable stays on screen. Reuses the same
 * [rememberInfiniteTransition] + scale technique [SoftScanTile]'s `listening` pulse already
 * uses, rather than inventing a new animation approach. No background color is set here — the
 * surrounding Scaffold already supplies the theme's background, same as every other full-screen
 * composable in this app (e.g. [TerminalPairingScreen]).
 *
 * Logo width targets [LOGO_WIDTH_FRACTION] (~65%) of the available width via `fillMaxWidth` +
 * `aspectRatio` (real asset ratio [CAVOTEC_LOGO_ASPECT_RATIO], `839x147` — a wide, short
 * wordmark, not a square/icon-shaped mark) rather than a fixed dp box, so the logo scales
 * correctly across the two different width numbers this project has floating around it: the
 * panel's native landscape pixels (1024 wide) vs. the confirmed-portrait 600dp width the app
 * actually renders content into on the F7G18P (see CLAUDE_TERMINAL.md's audit notes) — a fixed
 * dp guess risked being right for one and wrong for the other.
 */
@Composable
private fun BootSplashAnimation() {
    val colors = LocalEkmsColors.current
    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appeared = true }

    val entranceScale by animateFloatAsState(
        targetValue = if (appeared) 1f else 0.7f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow,
        ),
        label = "bootLogoEntranceScale",
    )
    val entranceAlpha by animateFloatAsState(
        targetValue = if (appeared) 1f else 0f,
        animationSpec = tween(durationMillis = 500),
        label = "bootLogoEntranceAlpha",
    )

    val breathTransition = rememberInfiniteTransition(label = "bootLogoBreath")
    val breathScale by breathTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(1_400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "bootLogoBreathScale",
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Image(
            painter = painterResource(R.drawable.cavotec_logo),
            contentDescription = null,
            modifier = Modifier
                .fillMaxWidth(LOGO_WIDTH_FRACTION)
                .aspectRatio(CAVOTEC_LOGO_ASPECT_RATIO)
                .scale(entranceScale * breathScale)
                .alpha(entranceAlpha),
        )
        CircularProgressIndicator(
            modifier = Modifier.size(28.dp).alpha(entranceAlpha),
            strokeWidth = 3.dp,
            color = colors.primary,
            trackColor = MaterialTheme.colorScheme.primaryContainer,
        )
    }
}

/**
 * Replaces the splash area (not a separate screen/route) once any check reports FAIL. "View
 * details" reveals the existing per-line diagnostic list UI via [ConnectionHintCard] — the exact
 * same reusable row [StartupDiagnosticsScreen]/[HardwareStatusPage] already use, not rebuilt.
 *
 * [onContinueAnyway] is the subtle bypass this project's non-blocking invariant requires (see
 * [TerminalBootSplashScreen]'s doc) — deliberately styled smaller/lower-emphasis than Retry/Open
 * Network Settings so it doesn't compete with the recommended recovery actions, not hidden.
 */
@Composable
private fun BootWarningCard(
    checks: List<ConnectionHint>,
    networkFailed: Boolean,
    onRetry: () -> Unit,
    onContinueAnyway: () -> Unit,
) {
    val context = LocalContext.current
    val failedChecks = checks.filter { it.severity == HintSeverity.FAIL }
    var detailsExpanded by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 480.dp)
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SoftBrandHeader(subtitle = "Startup check")

        SoftCard(contentPadding = 20.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Needs attention: " + failedChecks.joinToString { it.title },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "The terminal can still be signed into, but the hardware listed " +
                        "above should be checked before relying on it.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        SoftTextButton(
            text = if (detailsExpanded) "Hide details" else "View details",
            onClick = { detailsExpanded = !detailsExpanded },
        )
        if (detailsExpanded) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                checks.forEach { check -> ConnectionHintCard(check) }
            }
        }

        SoftPrimaryButton(text = "Retry", onClick = onRetry)

        // Same precedent this project already followed for Wi-Fi bring-up (see
        // NetworkStatusController's own doc / CLAUDE_TERMINAL.md's network bring-up notes):
        // hand off to the system Wi-Fi settings rather than building a custom in-app config UI.
        if (networkFailed) {
            OutlinedButton(
                onClick = { context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Open Network Settings")
            }
        }

        TextButton(onClick = onContinueAnyway, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "Continue anyway",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
