package com.ekms.mobile.push

import android.content.Context
import android.util.Log
import com.ekms.mobile.data.MobileApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Registers an FCM device token with the backend when available.
 * Without Firebase/`google-services.json`, this no-ops (Alerts polling remains the fallback).
 * Set prefs key `fcm_token_override` for staging tests, or wire FirebaseMessaging when ops adds
 * the real google-services.json (see CLAUDE_MOBILE.md).
 */
object PushTokenSync {
    private const val TAG = "PushTokenSync"
    private const val PREFS = "ekms_mobile_push"
    private const val KEY_OVERRIDE = "fcm_token_override"
    private const val KEY_LAST = "fcm_token_last"

    fun syncAfterLogin(context: Context, apiClient: MobileApiClient) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val token = prefs.getString(KEY_OVERRIDE, null)
                    ?: prefs.getString(KEY_LAST, null)
                    ?: tryReadFirebaseToken(context)
                if (token.isNullOrBlank()) {
                    Log.i(TAG, "No FCM token yet — push skipped (poll Alerts instead)")
                    return@launch
                }
                prefs.edit().putString(KEY_LAST, token).apply()
                apiClient.registerPushToken(token)
                Log.i(TAG, "Push token registered with backend")
            } catch (e: Exception) {
                Log.w(TAG, "Push token sync failed: ${e.message}")
            }
        }
    }

    fun storeToken(context: Context, token: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST, token)
            .apply()
    }

    private fun tryReadFirebaseToken(context: Context): String? {
        return try {
            val firebaseApp = Class.forName("com.google.firebase.FirebaseApp")
            val getInstance = firebaseApp.getMethod("getInstance")
            getInstance.invoke(null) // ensure initialized
            val messaging = Class.forName("com.google.firebase.messaging.FirebaseMessaging")
            val getMessaging = messaging.getMethod("getInstance")
            val instance = getMessaging.invoke(null)
            val getToken = messaging.getMethod("getToken")
            val task = getToken.invoke(instance)
            // Avoid blocking Tasks API hard — reflection Tasks.await if present
            val tasks = Class.forName("com.google.android.gms.tasks.Tasks")
            val await = tasks.getMethod("await", Class.forName("com.google.android.gms.tasks.Task"))
            await.invoke(null, task) as? String
        } catch (_: Throwable) {
            null
        }
    }
}
