package com.ekms.mobile

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ekms.mobile.push.PushNotifications
import com.ekms.mobile.ui.SuperAdminCompanionApp

class MainActivity : ComponentActivity() {

    private var pendingOpenTab by mutableStateOf<String?>(null)

    // Android 13+ requires this be requested at runtime, or notifications never display —
    // there's no meaningful UX difference between granted/denied here, so no-op either way.
    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

    companion object {
        const val EXTRA_OPEN_TAB = "open_tab"
        const val TAB_ALERTS = "alerts"
    }
}
