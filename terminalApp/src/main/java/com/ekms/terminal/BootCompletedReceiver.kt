package com.ekms.terminal

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Auto-launches [MainActivity] once Android finishes booting, so the F7G18P kiosk terminal
 * comes back up with no manual tap needed after a power cycle. Whether this terminal is paired
 * is decided by [com.ekms.terminal.ui.TerminalAdminApp]'s existing pairing gate at first
 * composition, not here — this receiver's only job is getting the app open.
 *
 * Manifest-registered (not `Context.registerReceiver`), so the system instantiates this class
 * fresh per broadcast — there is no separate "receiver created/registered" moment to log at
 * runtime beyond [onReceive] itself firing.
 */
class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.i(LOG_TAG, "onReceive() fired, action=${intent.action}")

        if (intent.action != Intent.ACTION_BOOT_COMPLETED) {
            Log.i(LOG_TAG, "Ignoring — action does not match ACTION_BOOT_COMPLETED")
            return
        }

        val launchIntent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        Log.i(LOG_TAG, "Calling startActivity() for ${launchIntent.component}")
        try {
            context.startActivity(launchIntent)
            Log.i(LOG_TAG, "startActivity() returned without throwing")
        } catch (error: Exception) {
            Log.e(LOG_TAG, "startActivity() failed", error)
        }
    }

    private companion object {
        const val LOG_TAG = "BootAutoLaunch"
    }
}
