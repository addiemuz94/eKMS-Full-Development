package com.ekms.mobile.push

import android.util.Log
import com.ekms.mobile.data.MobileApiClient
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Receives checkout-deadline / key-access push notifications. Registered in AndroidManifest.xml
 * with the standard `com.google.firebase.MESSAGING_EVENT` intent filter.
 */
class EkmsFirebaseMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        PushTokenSync.storeToken(applicationContext, token)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                MobileApiClient(applicationContext).registerPushToken(fcmToken = token)
                Log.i(TAG, "Rotated push token registered with backend")
            } catch (e: Exception) {
                Log.w(TAG, "onNewToken registration failed: ${e.message}")
            }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val title = message.notification?.title ?: message.data["title"] ?: "eKMS Alert"
        val body = message.notification?.body ?: message.data["body"] ?: ""
        PushNotifications.show(applicationContext, title, body)
    }

    companion object {
        private const val TAG = "EkmsFcmService"
    }
}
