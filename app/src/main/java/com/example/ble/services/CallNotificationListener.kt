package com.example.ble.services

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.app.Notification
import android.util.Log
import com.example.ble.models.CallMetadata

private const val TAG = "CallNotifListener"

/**
 * Notification Listener Service to capture caller names from system dialer notifications
 * This is the standard approach used by smartwatches to get caller information
 */
class CallNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.d(TAG, "CallNotificationListener connected and ready to receive notifications")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.w(TAG, "CallNotificationListener disconnected - notifications will not be received")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        try {
            // 1. Filter for "Dialer" apps (Google Dialer, Samsung InCallUI, etc.)
            val packageName = sbn.packageName
            Log.v(TAG, "Notification posted from: $packageName")
            
            if (!isDialerApp(packageName)) {
                Log.v(TAG, "Ignoring non-dialer notification from: $packageName")
                return
            }

            // 2. Check if this is an "Incoming Call" style notification
            val extras = sbn.notification.extras
            val category = sbn.notification.category
            
            Log.d(TAG, "Checking notification from $packageName, category: $category")
            
            if (category == Notification.CATEGORY_CALL || isIncomingCallStyle(sbn)) {
                
                // 3. Extract the text visible on the phone screen
                val title = extras?.getString(Notification.EXTRA_TITLE) // Usually "Incoming call" or the Name
                val text = extras?.getString(Notification.EXTRA_TEXT)   // Usually the Number or Name
                val bigText = extras?.getString(Notification.EXTRA_BIG_TEXT) // Sometimes contains more info
                
                Log.d(TAG, "Captured Dialer Notification from $packageName")
                Log.d(TAG, "Title: '$title'")
                Log.d(TAG, "Text: '$text'")
                Log.d(TAG, "BigText: '$bigText'")
                Log.d(TAG, "Flags: ${sbn.notification.flags}")

                // 4. Resolve caller name from notification fields
                val callerName = resolveCallerName(title, text, bigText)
                
                if (!callerName.isNullOrEmpty() && callerName != "Unknown") {
                    Log.d(TAG, "Found Friendly Name: '$callerName'")
                    
                    // Send this to CallListenerService to update current call metadata
                    CallListenerService.updateCallerNameFromNotification(callerName)
                } else {
                    Log.d(TAG, "Could not extract meaningful caller name from notification")
                }
            } else {
                Log.v(TAG, "Notification from $packageName is not a call notification")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing notification", e)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // Handle call notification removal if needed
        val packageName = sbn.packageName
        if (isDialerApp(packageName)) {
            Log.d(TAG, "Call notification removed from $packageName")
            // Could indicate call ended, but PhoneStateListener handles this better
        }
    }

    /**
     * Check if the package is a known dialer app
     */
    private fun isDialerApp(pkg: String): Boolean {
        val dialerIdentifiers = listOf(
            "dialer",
            "phone", 
            "android.phone",
            "telecom",
            "incallui",
            "call",
            "com.android.dialer",
            "com.google.android.dialer",
            "com.samsung.android.incallui",
            "com.samsung.android.dialer",
            "com.miui.securitycenter", // Xiaomi
            "com.oneplus.dialer",
            "com.htc.android.phone"
        )
        
        val lowerPkg = pkg.lowercase()
        return dialerIdentifiers.any { identifier ->
            lowerPkg.contains(identifier)
        }
    }
    
    /**
     * Check if this notification represents an incoming/active call
     */
    private fun isIncomingCallStyle(sbn: StatusBarNotification): Boolean {
        val notification = sbn.notification
        
        // Check for ongoing call flag (most reliable indicator)
        val isOngoing = notification.flags and Notification.FLAG_ONGOING_EVENT != 0
        
        // Check for call-related actions (Answer, Decline buttons)
        val hasCallActions = notification.actions?.any { action ->
            val actionTitle = action.title?.toString()?.lowercase() ?: ""
            actionTitle.contains("answer") || 
            actionTitle.contains("decline") || 
            actionTitle.contains("reject") ||
            actionTitle.contains("accept") ||
            actionTitle.contains("hang up") ||
            actionTitle.contains("end call")
        } ?: false
        
        // Check notification text for call indicators
        val extras = notification.extras
        val title = extras?.getString(Notification.EXTRA_TITLE)?.lowercase() ?: ""
        val text = extras?.getString(Notification.EXTRA_TEXT)?.lowercase() ?: ""
        val hasCallText = title.contains("call") || 
                         text.contains("call") || 
                         title.contains("calling") || 
                         text.contains("calling")
        
        val result = isOngoing || hasCallActions || hasCallText
        
        Log.v(TAG, "Call style check: ongoing=$isOngoing, actions=$hasCallActions, text=$hasCallText -> $result")
        
        return result
    }
    
    /**
     * Check if this notification is clearly not about a caller name
     */
    private fun isNonCallerNotification(packageName: String, title: String?, text: String?, bigText: String?): Boolean {
        val lowerTitle = title?.lowercase() ?: ""
        val lowerText = text?.lowercase() ?: ""
        val lowerBigText = bigText?.lowercase() ?: ""
        
        // Truecaller-specific noise
        if (packageName.contains("truecaller")) {
            val nonCallerPatterns = listOf(
                "spam protection",
                "block calls",
                "identify numbers",
                "premium",
                "subscription",
                "upgrade",
                "allow truecaller"
            )
            
            if (nonCallerPatterns.any { pattern ->
                lowerTitle.contains(pattern) || lowerText.contains(pattern) || lowerBigText.contains(pattern)
            }) {
                Log.d(TAG, "Filtered out Truecaller non-caller notification: '$title'")
                return true
            }
        }
        
        // General app promotion/settings notifications
        val generalNonCallerPatterns = listOf(
            "disabled", "enable", "allow", "permission", "settings",
            "tap to", "click to", "configure", "setup"
        )
        
        if (generalNonCallerPatterns.any { pattern ->
            lowerTitle.contains(pattern) || lowerText.contains(pattern)
        }) {
            Log.d(TAG, "Filtered out general non-caller notification: '$title'")
            return true
        }
        
        return false
    }
    
    /**
     * Extract the caller name from notification fields
     */
    private fun resolveCallerName(title: String?, text: String?, bigText: String?): String? {
        Log.v(TAG, "Resolving caller name from title='$title', text='$text', bigText='$bigText'")
        
        if (title.isNullOrEmpty() && text.isNullOrEmpty() && bigText.isNullOrEmpty()) {
            return null
        }
        
        // List of common system text patterns to ignore
        val systemTexts = listOf(
            "incoming call",
            "incoming",
            "calling",
            "call",
            "answer",
            "decline", 
            "reject",
            "hang up",
            "end call",
            "touch to return to call",
            "ongoing call",
            "active call",
            "tap to return",
            "spam protection",
            "block calls",
            "unknown number",
            "private number",
            "blocked"
        )
        
        // Helper function to check if text is meaningful (not a system message)
        fun isMeaningfulText(text: String?): Boolean {
            if (text.isNullOrBlank()) return false
            val lower = text.lowercase().trim()
            
            // Ignore if it's just a system message
            if (systemTexts.any { lower == it || lower.contains(it) }) return false
            
            // Ignore if it's just a phone number pattern (for privacy)
            if (lower.matches(Regex("^[+\\d\\s\\-\\(\\)]+$"))) return false
            
            // Must have at least 2 characters and contain letters
            return lower.length >= 2 && lower.any { it.isLetter() }
        }
        
        // Priority order: bigText -> title -> text
        val candidates = listOf(bigText, title, text)
        
        for (candidate in candidates) {
            if (isMeaningfulText(candidate)) {
                val cleaned = candidate!!.trim()
                Log.d(TAG, "Selected caller name: '$cleaned'")
                return cleaned
            }
        }
        
        // Fallback: return the first non-empty field even if it looks like a system message
        val fallback = candidates.firstOrNull { !it.isNullOrBlank() }?.trim()
        Log.v(TAG, "Using fallback caller name: '$fallback'")
        return fallback
    }
}