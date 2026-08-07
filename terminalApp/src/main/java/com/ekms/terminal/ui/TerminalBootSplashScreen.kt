package com.ekms.terminal.ui

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
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
 * Sizing model ported from web's login-page splash (`web/src/styles.css`'s `.login-splash*`
 * rules — CSS `min(Xpx, Yvw)`, translated to `minOf(X.dp, maxWidth * Y)` via [BoxWithConstraints]
 * below). Stays proportionally correct on this project's confirmed-portrait 600x1024dp panel
 * (see CLAUDE_TERMINAL.md's boot-splash Known Issues) the same way the old fraction-only model
 * did, but now capped so it doesn't keep growing on a hypothetically wider panel either, matching
 * web's own cap.
 */
private const val GLOW_DIAMETER_FRACTION = 0.70f // web: min(420px, 70vw)
private val GLOW_DIAMETER_MAX = 420.dp
private const val RING_DIAMETER_FRACTION = 0.52f // web: min(280px, 52vw)
private val RING_DIAMETER_MAX = 280.dp
private const val LOGO_WIDTH_FRACTION = 0.72f // web: min(280px, 72vw)
private val LOGO_WIDTH_MAX = 280.dp

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
 * forward ([onContinue]) once ALL THREE hold: every check is [HintSeverity.OK], at least
 * [MIN_SPLASH_DURATION_MILLIS] (~3s) has elapsed since this screen first appeared, AND
 * [nodeSelfTestComplete] (the boot-time key-node visual self-test, driven entirely by
 * `TerminalAdminApp`/`CabinetHardwareController.runBootKeyNodeSelfTest` — this screen only reads
 * the flag, it owns no hardware-command logic of its own) — so a fast-passing cabinet never
 * flashes the splash for one frame, and a slow-connecting one (or a cabinet with many key nodes
 * still cycling through the self-test) never cuts the animation short; if any of the three takes
 * longer than the others, the loop simply keeps running until all catch up, same as before. On
 * any [HintSeverity.FAIL], the loop stops and [BootWarningCard] replaces the animation instead of
 * navigating — the node self-test keeps running regardless (it never blocks on one bad node, see
 * its own doc), it just can't finish gating [onContinue] while a FAIL is also blocking it.
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
    /** Boot-time key-node self-test gate (see `CabinetHardwareController.runBootKeyNodeSelfTest`'s
     * doc and `TerminalAdminApp`'s own trigger `LaunchedEffect`) — an additional, independent
     * condition on [onContinue] alongside [allHealthy]/[minDurationElapsed] below, not a
     * replacement for either. Owned entirely by the caller; this screen only reads it. */
    nodeSelfTestComplete: Boolean,
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

    LaunchedEffect(allHealthy, minDurationElapsed, nodeSelfTestComplete) {
        if (allHealthy && minDurationElapsed && nodeSelfTestComplete) onContinue()
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
 * v2 design — ported from web's login-page splash (`web/src/styles.css`'s `.login-splash*`
 * rules; see CLAUDE_TERMINAL.md's Completed entry for the full extracted reference: exact
 * gradient stops, sizes, and cubic-bezier curves this pass carried over) rather than this app's
 * own original v1 spring-bounce + breathing-pulse treatment. Two deliberate, documented
 * departures from a literal 1:1 port — this is a *port*, not a shared component, since this
 * project has no cross-app Compose UI module:
 * 1. Colors read from this app's own [LocalEkmsColors]/[MaterialTheme] tokens (already the same
 *    Cavotec-blue brand direction as web) rather than hardcoding web's literal hex values —
 *    avoids two independently-driftable sources of "the same" brand color.
 * 2. Web's CSS *ellipse*-shaped background gradients are approximated here as *circular*
 *    `Brush.radialGradient`s (Compose has no ellipse-gradient primitive without a custom
 *    `drawWithCache`/Canvas path — judged not worth the complexity for a boot splash), and the
 *    ambient background gradient's off-center vertical position (CSS `at 50% 42%`) is not
 *    reproduced (default-centered instead) for the same reason.
 *
 * Sequence (all times from first appearance): glow pulse loops immediately and continuously
 * (2.2s cycle, never stops while this screen is shown); ring fades+scales in once over 1.8s;
 * logo fades+scales+slides+blurs in once over 1.1s; tagline fades+slides in once over 0.9s,
 * starting 0.45s after the others. No background color change from v1 beyond what's described
 * here — the surrounding Scaffold still supplies the base theme background (same as every other
 * full-screen composable in this app, e.g. [TerminalPairingScreen]); this adds a splash-scoped
 * ambient gradient *on top* of that, not a replacement of it, and only for this composable — the
 * sibling [BootWarningCard] (failure path) is untouched and keeps the plain background it always
 * had, so there's no risk of the two states visually clashing.
 *
 * The logo entrance's CSS `filter: blur(4px→0)` step only runs on API 31+ (`Modifier.blur` has
 * no software fallback below that on this Compose version) — omitted below 31, the same "accept
 * a graceful fallback rather than crash or hack" approach this project already took for Haze's
 * own blur effect (see CLAUDE_MOBILE.md's Phase M-E entry).
 */
@Composable
private fun BootSplashAnimation() {
    val colors = LocalEkmsColors.current
    val primary = colors.primary
    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appeared = true }

    // Logo entrance — web: `login-splash-logo 1.1s cubic-bezier(0.22,1,0.36,1) both`.
    val logoEasing = remember { CubicBezierEasing(0.22f, 1f, 0.36f, 1f) }
    val logoEntrance by animateFloatAsState(
        targetValue = if (appeared) 1f else 0f,
        animationSpec = tween(1_100, easing = logoEasing),
        label = "bootLogoEntrance",
    )

    // Ring — web: `login-splash-ring 1.8s ease-out both` (plays once, does not loop).
    val easeOut = remember { CubicBezierEasing(0f, 0f, 0.58f, 1f) }
    val ringEntrance by animateFloatAsState(
        targetValue = if (appeared) 1f else 0f,
        animationSpec = tween(1_800, easing = easeOut),
        label = "bootRingEntrance",
    )

    // Glow pulse — web: `login-splash-pulse 2.2s ease-in-out infinite` (0%/100% scale 0.92
    // alpha 0.7, 50% scale 1.06 alpha 1.0). A symmetric ease-in-out curve applied to each 1.1s
    // leg of a Reverse-mode infiniteRepeatable reproduces the same keyframe shape as one
    // continuous 2.2s CSS animation, since ease-in-out already mirrors around its own midpoint.
    val easeInOut = remember { CubicBezierEasing(0.42f, 0f, 0.58f, 1f) }
    val glowTransition = rememberInfiniteTransition(label = "bootGlowPulse")
    val glowPulse by glowTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1_100, easing = easeInOut), RepeatMode.Reverse),
        label = "bootGlowPulse",
    )

    // Tagline — web: `login-splash-tag 0.9s ease 0.45s both` (450ms delay before it starts).
    val ease = remember { CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f) }
    val tagEntrance by animateFloatAsState(
        targetValue = if (appeared) 1f else 0f,
        animationSpec = tween(900, delayMillis = 450, easing = ease),
        label = "bootTagEntrance",
    )

    val glowScale = 0.92f + 0.14f * glowPulse
    val glowAlpha = 0.7f + 0.3f * glowPulse
    val ringScale = 0.7f + 0.3f * ringEntrance
    val logoScale = 0.92f + 0.08f * logoEntrance

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            // Ambient splash-scoped background gradient — web: radial-gradient(ellipse 70% 50%
            // at 50% 42%, rgba(primary,0.18), transparent 62%) layered over the base surface.
            // Approximated as centered/circular (see class doc, departure #2).
            .background(
                Brush.radialGradient(0f to primary.copy(alpha = 0.18f), 0.62f to Color.Transparent),
            ),
        contentAlignment = Alignment.Center,
    ) {
        val glowDiameter = minOf(GLOW_DIAMETER_MAX, maxWidth * GLOW_DIAMETER_FRACTION)
        val ringDiameter = minOf(RING_DIAMETER_MAX, maxWidth * RING_DIAMETER_FRACTION)
        val logoWidth = minOf(LOGO_WIDTH_MAX, maxWidth * LOGO_WIDTH_FRACTION)

        // Glow — web: radial-gradient(circle, rgba(primary,0.22|0.16), transparent 68%); the
        // dark-mode CSS override swaps to a lower 0.16 alpha, since it's the same theme-resolved
        // [primary] token used either way (departure #1), not a second hardcoded color.
        Box(
            modifier = Modifier
                .size(glowDiameter)
                .scale(glowScale)
                .alpha(glowAlpha)
                .background(
                    brush = Brush.radialGradient(
                        0f to primary.copy(alpha = if (colors.isDark) 0.16f else 0.22f),
                        0.68f to Color.Transparent,
                    ),
                    shape = CircleShape,
                ),
        )

        // Ring — web: 1px solid rgba(primary, 0.35), border-radius 999px.
        Box(
            modifier = Modifier
                .size(ringDiameter)
                .scale(ringScale)
                .alpha(ringEntrance)
                .border(1.dp, primary.copy(alpha = 0.35f), CircleShape),
        )

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Image(
                painter = painterResource(R.drawable.cavotec_logo),
                contentDescription = null,
                modifier = Modifier
                    .width(logoWidth)
                    .aspectRatio(CAVOTEC_LOGO_ASPECT_RATIO)
                    .offset(y = 12.dp * (1f - logoEntrance))
                    .scale(logoScale)
                    .alpha(logoEntrance)
                    .then(
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            Modifier.blur(((1f - logoEntrance) * 4f).dp)
                        } else {
                            Modifier
                        },
                    ),
            )
            Text(
                text = "Key Management System".uppercase(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.14f.em,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .offset(y = 8.dp * (1f - tagEntrance))
                    .alpha(tagEntrance),
            )
            // Readability pass: 28dp -> 34dp (x1.2).
            CircularProgressIndicator(
                modifier = Modifier.size(34.dp).alpha(logoEntrance),
                strokeWidth = 3.dp,
                color = primary,
                trackColor = MaterialTheme.colorScheme.primaryContainer,
            )
        }
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
