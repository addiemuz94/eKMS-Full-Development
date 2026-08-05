package com.ekms.mobile

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.FragmentActivity
import com.ekms.mobile.push.PushNotifications
import com.ekms.mobile.ui.SuperAdminCompanionApp

/**
 * `FragmentActivity` (not plain `ComponentActivity`) — required for biometric login (see
 * CLAUDE_MOBILE.md): `androidx.biometric.BiometricPrompt`'s real constructor only accepts a
 * `FragmentActivity`/`Fragment` (confirmed via `javap` against the actual AAR, not assumed — it
 * hosts an invisible fragment internally to survive configuration changes during a prompt, which
 * needs a `FragmentManager` that plain `ComponentActivity` doesn't have). `FragmentActivity`
 * itself extends `ComponentActivity`, so every API this class already used (`setContent`,
 * `registerForActivityResult`, etc.) stays available unchanged — this is a strict superset, not
 * a different activity family.
 */
class MainActivity : FragmentActivity() {

    private var pendingOpenTab by mutableStateOf<String?>(null)

    // Android 13+ requires this be requested at runtime, or notifications never display —
    // there's no meaningful UX difference between granted/denied here, so no-op either way.
    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Must be called before super.onCreate() per the platform API's own contract — this is
        // only the brief OS-level icon-reveal step (API 31+; a no-op shell below that per
        // core-splashscreen's own graceful-degradation design). The sustained branded splash is
        // the separate custom Compose screen SuperAdminCompanionApp shows next (see
        // CLAUDE_MOBILE.md) — deliberately not using setKeepOnScreenCondition here to extend
        // this native step, since the task is two sequential stages, not one prolonged one.
        installSplashScreen()
        super.onCreate(savedInstanceState)
        applyImmersiveMode()
        PushNotifications.ensureChannel(applicationContext)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        pendingOpenTab = intent?.getStringExtra(EXTRA_OPEN_TAB)
        setContent {
            SuperAdminCompanionApp(
                openTabRequest = pendingOpenTab,
                onOpenTabConsumed = { pendingOpenTab = null },
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingOpenTab = intent.getStringExtra(EXTRA_OPEN_TAB)
    }

    // Load-bearing, not defensive: Android clears the hidden-system-bars state whenever this
    // window loses and regains focus, not just on full activity recreation — and unlike
    // terminalApp's kiosk device, mobileApp's whole premise is that the user constantly leaves
    // it (other apps, phone calls, a system picker/Settings round trip, home button) and comes
    // back. onCreate alone would leave the bars visible again after the very first app switch.
    override fun onResume() {
        super.onResume()
        applyImmersiveMode()
    }

    /**
     * Mirrors terminalApp's `applyImmersiveMode()` (`WindowInsetsControllerCompat`,
     * `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`, platform edge-swipe-reveal-then-auto-hide used
     * as-is, no custom timer). Reasoned about explicitly here, not copied silently, because
     * mobileApp is a genuinely different device context from terminalApp's locked-down kiosk:
     * this runs on the Super Admin's own phone alongside every other app, so hiding the status
     * bar has a real cost terminalApp doesn't — no glanceable clock/battery/signal, and no
     * one-tap pull-down to the notification shade. Weighed against that: (1)
     * `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE` is not a hard lockout — a single edge swipe
     * reveals the bars again on demand, it just isn't the default state; (2) heads-up
     * notification banners (an incoming call, a checkout-deadline push) are drawn by the
     * system as an overlay independent of system-bar visibility, so a hidden status bar does
     * not suppress or delay them — confirmed by how Android's heads-up notification mechanism
     * works, not assumed; (3) this app is used for short, deliberate security actions
     * (approve/reject/revoke, checking terminal status) rather than sustained background
     * monitoring where losing the clock/battery at a glance would matter continuously. Net
     * judgment: implementing as instructed, since the cost is real but recoverable
     * (one swipe away) and nothing time-sensitive is actually suppressed — but this is a
     * closer call than terminalApp's kiosk case, and worth revisiting from an actual on-device
     * feel rather than this reasoning alone (see CLAUDE_MOBILE.md's Known issues note).
     */
    private fun applyImmersiveMode() {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    companion object {
        const val EXTRA_OPEN_TAB = "open_tab"
        const val TAB_ALERTS = "alerts"
    }
}
