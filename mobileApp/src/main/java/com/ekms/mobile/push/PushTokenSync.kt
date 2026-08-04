package com.ekms.mobile.push

import android.content.Context
import android.util.Log
import com.ekms.mobile.data.MobileApiClient
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Registers an FCM device token with the backend. Reads the real token from
 * [FirebaseMessaging]; if Play Services/Firebase init fails for any reason (no Play Services on
 * the device, no network, misconfigured `google-services.json`), [tryReadFirebaseToken] catches
 * it and returns null — Alerts polling remains the fallback.
 * Set prefs key `fcm_token_override` for staging tests where a real device token isn't handy.
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
                    ?: tryReadFirebaseToken()
                if (token.isNullOrBlank()) {
                    Log.i(TAG, "No FCM token yet — push skipped (poll Alerts instead)")
                    return@launch
                }
                prefs.edit().putString(KEY_LAST, token).apply()
                apiClient.registerPushToken(fcmToken = token)
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

    private suspend fun tryReadFirebaseToken(): String? {
        return try {
            FirebaseMessaging.getInstance().token.await()
        } catch (e: Throwable) {
            // Play Services unavailable, no network, misconfigured google-services.json, etc. —
            // fail safe rather than crash; Alerts polling remains the fallback.
            Log.w(TAG, "FirebaseMessaging token fetch failed: ${e.message}")
            null
        }
    }
}
