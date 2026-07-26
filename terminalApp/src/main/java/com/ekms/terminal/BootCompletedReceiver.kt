package com.ekms.terminal

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Auto-launches [MainActivity] once Android finishes booting, so the F7G18P kiosk terminal
 * comes back up with no manual tap needed after a power cycle. Whether this terminal is paired
 * is decided by [com.ekms.terminal.ui.TerminalAdminApp]'s existing pairing gate at first
 * composition, not here — this receiver's only job is getting the app open.
 */
class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val launchIntent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(launchIntent)
    }
}
