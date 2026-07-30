package com.ekms.terminal

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.ekms.terminal.ui.TerminalAdminApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyImmersiveMode()
        setContent { TerminalAdminApp() }
    }

    // Android clears the hidden-system-bars state whenever this window loses and regains
    // focus — not just full activity recreation. The boot splash's "Open Network Settings"
    // button (see TerminalBootSplashScreen) is a real, already-shipped path that does exactly
    // that (launches Settings, user returns here), so onResume is not optional here; onCreate
    // alone would leave the bars permanently visible after that one round trip.
    override fun onResume() {
        super.onResume()
        applyImmersiveMode()
    }

    /**
     * Platform-checked before adding this: with `com.ekms.terminal/.MainActivity` in the
     * foreground on the real F7G18P (`adb shell dumpsys window`), both the status bar and
     * navigation bar reported `visible=true` — the device's EnjoyKit kiosk config
     * (`com.hzmct.enjoyConfig`, confirmed installed) is not suppressing system bars for this
     * app, so this is not a redundant/conflicting mechanism layered on top of one that already
     * works. No custom show/hide timer logic here deliberately — `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`
     * is the platform's own edge-swipe-reveal-then-auto-hide behavior, used as-is.
     */
    private fun applyImmersiveMode() {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }
}
