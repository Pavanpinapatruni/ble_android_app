package com.example.ble.services

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.example.ble.models.MediaMetadata

private const val TAG = "MediaListenerService"

class MediaListenerService : NotificationListenerService() {
    
    companion object {
        private var instance: MediaListenerService? = null
        private var mediaUpdateListener: ((MediaMetadata) -> Unit)? = null
        
        fun setMediaUpdateListener(listener: (MediaMetadata) -> Unit) {
            mediaUpdateListener = listener
        }
        
        fun removeMediaUpdateListener() {
            mediaUpdateListener = null
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.d(TAG, "MediaListenerService created")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        Log.d(TAG, "MediaListenerService destroyed")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        sbn?.let { extractMediaInfo(it) }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        sbn?.let { 
            if (isMediaNotification(it)) {
                // Media stopped, send empty metadata
                val emptyMetadata = MediaMetadata(isPlaying = false)
                mediaUpdateListener?.invoke(emptyMetadata)
                Log.d(TAG, "Media notification removed")
            }
        }
    }

    private fun extractMediaInfo(sbn: StatusBarNotification) {
        try {
            val notification = sbn.notification
            val extras = notification.extras
            val packageName = sbn.packageName
            
            // Check if this is a media notification
            if (isMediaNotification(sbn)) {
                val title = extras.getCharSequence("android.title")?.toString()
                val text = extras.getCharSequence("android.text")?.toString()
                val subText = extras.getCharSequence("android.subText")?.toString()
                
                // For Spotify and other music apps
                val artist = text ?: subText
                val album = extras.getCharSequence("android.infoText")?.toString()
                
                val metadata = MediaMetadata(
                    title = title,
                    artist = artist,
                    album = album,
                    packageName = packageName,
                    isPlaying = true
                )
                
                mediaUpdateListener?.invoke(metadata)
                Log.d(TAG, "Media info: $title by $artist from $packageName")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting media info", e)
        }
    }

    private fun isMediaNotification(sbn: StatusBarNotification): Boolean {
        val packageName = sbn.packageName.lowercase()
        val mediaApps = listOf(
            "spotify", "com.spotify.music",
            "youtube", "com.google.android.youtube",
            "music", "com.google.android.music",
            "soundcloud", "com.soundcloud.android",
            "pandora", "com.pandora.android",
            "apple.music", "com.apple.android.music",
            "deezer", "deezer.android.app"
        )
        
        return mediaApps.any { packageName.contains(it) } ||
                sbn.notification.extras.containsKey("android.mediaSession") ||
                sbn.notification.category == "transport"
    }
}