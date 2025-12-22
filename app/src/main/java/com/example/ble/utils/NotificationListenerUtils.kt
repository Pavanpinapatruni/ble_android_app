package com.example.ble.utils

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.TextUtils
import android.util.Log

/**
 * Utility class for managing notification listener permissions
 */
object NotificationListenerUtils {
    private const val TAG = "NotificationListenerUtils"
    
    /**
     * Check if our app has notification listener permission
     */
    fun hasNotificationListenerPermission(context: Context): Boolean {
        val packageName = context.packageName
        val flat = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners"
        )
        
        if (!TextUtils.isEmpty(flat)) {
            val names = flat.split(":").toTypedArray()
            for (name in names) {
                val component = android.content.ComponentName.unflattenFromString(name)
                if (component != null && TextUtils.equals(packageName, component.packageName)) {
                    Log.d(TAG, "Notification listener permission granted for $packageName")
                    return true
                }
            }
        }
        
        Log.d(TAG, "Notification listener permission NOT granted for $packageName")
        return false
    }
    
    /**
     * Open the notification listener settings for the user to grant permission
     */
    fun requestNotificationListenerPermission(context: Context) {
        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
            Log.d(TAG, "Opened notification listener settings")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open notification listener settings", e)
        }
    }
    
    /**
     * Get a user-friendly explanation of what notification access is for
     */
    fun getPermissionExplanation(): String {
        return """
            BLE Call Listener needs notification access to identify incoming callers.
            
            This is the standard method used by smartwatches and companion apps.
            
            When someone calls you:
            1. Your phone shows their name/number
            2. Our app reads that notification 
            3. We send the caller name to your device
            
            We only read call-related notifications and respect your privacy.
        """.trimIndent()
    }
}