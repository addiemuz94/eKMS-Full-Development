package com.ekms.mobile.ui

import android.os.Build
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.ekms.mobile.R
import kotlinx.coroutines.delay

/**
 * mobileApp UX rework — boot splash, shown once per cold launch before `LoginScreen` (or the
 * authenticated shell). Two-stage design, mirroring terminalApp's split between a platform-level
 * step and an app-level one: `MainActivity.onCreate()`'s `installSplashScreen()` handles the
 * brief OS-level icon-reveal (API 31+ only), and this composable is the sustained, fully-branded
 * second stage — no hardware diagnostics, no failure/retry state, since mobileApp is a companion
 * app with no cabinet hardware to check (unlike [TerminalBootSplashScreen], which this
 * deliberately mirrors the *shape* of, not the content of).
 *
 * Visual spec ported from web's login-page splash (`web/src/styles.css`'s `.login-splash*`
 * rules — see CLAUDE_MOBILE.md's Completed entry for the full extracted reference: exact
 * gradient stops, sizes, and cubic-bezier curves this carries over), same as terminalApp's own
 * restyle — duplicated rather than shared, since this project has no cross-app Compose UI
 * module (see [com.ekms.mobile.ui.theme.EkmsMobileTheme]'s own doc for that same precedent).
 * Same two documented departures from a literal 1:1 port as terminalApp's version:
 * 1. Colors read from this app's own `MaterialTheme.colorScheme` tokens (already the same
 *    Cavotec-blue brand direction as web) rather than hardcoding web's literal hex values.
 * 2. Web's CSS *ellipse*-shaped background gradients are approximated as *circular*
 *    `Brush.radialGradient`s (Compose has no ellipse-gradient primitive without a custom
 *    `drawWithCache`/Canvas path).
 *
 * Duration mirrors web's own `SPLASH_MS = 2000` directly (2s) rather than terminalApp's
 * hardware-check-driven 3s floor, since there is no hardware gating here at all — this is
 * branding-only, on a fixed timer, matching the thing it's ported from most closely.
 *
 * The logo entrance's CSS `filter: blur(4px→0)` step only runs on API 31+ (`Modifier.blur` has
 * no software fallback below that on this Compose version) — omitted below 31, same fallback
 * approach as terminalApp's port and this project's own Haze blur effect (see CLAUDE_MOBILE.md's
 * Phase M-E entry).
 */
private const val MOBILE_BOOT_SPLASH_DURATION_MILLIS = 2_000L

private const val GLOW_DIAMETER_FRACTION = 0.70f // web: min(420px, 70vw)
private val GLOW_DIAMETER_MAX = 420.dp
private const val RING_DIAMETER_FRACTION = 0.52f // web: min(280px, 52vw)
private val RING_DIAMETER_MAX = 280.dp
private const val LOGO_WIDTH_FRACTION = 0.72f // web: min(280px, 72vw)
private val LOGO_WIDTH_MAX = 280.dp

/** `cavotec_logo.png`'s real pixel dimensions (839x147) — copied byte-for-byte from
 *  terminalApp/src/main/res/drawable/ (same precedent as EkmsMobileTypography's font files). */
private const val CAVOTEC_LOGO_ASPECT_RATIO = 839f / 147f

@Composable
fun MobileBootSplashScreen(isDarkTheme: Boolean, onContinue: () -> Unit) {
    LaunchedEffect(Unit) {
        delay(MOBILE_BOOT_SPLASH_DURATION_MILLIS)
        onContinue()
    }

    val primary = MaterialTheme.colorScheme.primary
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

    // Glow pulse — web: `login-splash-pulse 2.2s ease-in-out infinite`. See
    // TerminalBootSplashScreen.kt's identical comment for why a Reverse-mode
    // infiniteRepeatable with ease-in-out on each 1.1s leg reproduces the same keyframe shape.
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
            // at 50% 42%, rgba(primary,0.18), transparent 62%) over the base surface.
            // Approximated as centered/circular (see class doc, departure #2).
            .background(MaterialTheme.colorScheme.background)
            .background(
                Brush.radialGradient(0f to primary.copy(alpha = 0.18f), 0.62f to Color.Transparent),
            ),
        contentAlignment = Alignment.Center,
    ) {
        val glowDiameter = minOf(GLOW_DIAMETER_MAX, maxWidth * GLOW_DIAMETER_FRACTION)
        val ringDiameter = minOf(RING_DIAMETER_MAX, maxWidth * RING_DIAMETER_FRACTION)
        val logoWidth = minOf(LOGO_WIDTH_MAX, maxWidth * LOGO_WIDTH_FRACTION)

        // Glow — web: radial-gradient(circle, rgba(primary,0.22|0.16), transparent 68%); the
        // dark-mode CSS override swaps to a lower 0.16 alpha on the same theme-resolved
        // [primary] token, not a second hardcoded color (departure #1).
        Box(
            modifier = Modifier
                .size(glowDiameter)
                .scale(glowScale)
                .alpha(glowAlpha)
                .background(
                    brush = Brush.radialGradient(
                        0f to primary.copy(alpha = if (isDarkTheme) 0.16f else 0.22f),
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
            CircularProgressIndicator(
                modifier = Modifier.size(28.dp).alpha(logoEntrance),
                strokeWidth = 3.dp,
                color = primary,
                trackColor = MaterialTheme.colorScheme.primaryContainer,
            )
        }
    }
}
