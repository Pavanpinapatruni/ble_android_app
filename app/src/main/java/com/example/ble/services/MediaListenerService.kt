package com.example.ble.services

import android.content.ComponentName
import android.media.MediaMetadata as AndroidMediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.example.ble.models.MediaMetadata
import com.example.ble.ble.BleManager

private const val TAG = "MediaListenerService"

class MediaListenerService : NotificationListenerService(), MediaSessionManager.OnActiveSessionsChangedListener {
    
    private var mediaSessionManager: MediaSessionManager? = null
    private val activeControllers = mutableMapOf<String, MediaController>()
    private val controllerCallbacks = mutableMapOf<String, MediaController.Callback>()
    
    companion object {
        private var instance: MediaListenerService? = null
        private var mediaUpdateListener: ((MediaMetadata) -> Unit)? = null
        private var currentMediaMetadata: MediaMetadata? = null
        var bleManager: BleManager? = null
            private set
            
        fun setBleManager(manager: BleManager) {
            bleManager = manager
        }
        
        fun setMediaUpdateListener(listener: (MediaMetadata) -> Unit) {
            mediaUpdateListener = listener
            // Send current metadata if available
            currentMediaMetadata?.let { listener(it) }
        }
        
        fun removeMediaUpdateListener() {
            mediaUpdateListener = null
        }
        
        fun getCurrentMetadata(): MediaMetadata? = currentMediaMetadata
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        setupMediaSessionManager()
        Log.d(TAG, "MediaListenerService created with Media Session support")
    }

    override fun onDestroy() {
        super.onDestroy()
        
        // Unregister the session change listener
        try {
            mediaSessionManager?.removeOnActiveSessionsChangedListener(this)
            Log.d(TAG, "Unregistered active session change listener")
        } catch (e: Exception) {
            Log.w(TAG, "Error unregistering session change listener", e)
        }
        
        cleanupMediaControllers()
        bleManager?.cleanup()
        instance = null
        Log.d(TAG, "MediaListenerService destroyed")
    }
    
    private fun setupMediaSessionManager() {
        try {
            mediaSessionManager = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
            
            // Register for active session changes to detect when apps start/stop
            mediaSessionManager?.addOnActiveSessionsChangedListener(
                this, // this service implements OnActiveSessionsChangedListener
                ComponentName(this, MediaListenerService::class.java)
            )
            Log.d(TAG, "Registered for active session changes")
            
            updateActiveMediaControllers()
            
            // Start periodic refresh to catch any missed sessions
            startPeriodicRefresh()
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up MediaSessionManager", e)
        }
    }
    
    private fun startPeriodicRefresh() {
        // Use a handler to periodically refresh active sessions
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (mediaSessionManager != null) {
                Log.d(TAG, "🔄 Periodic refresh of active media sessions")
                updateActiveMediaControllers()
                startPeriodicRefresh() // Schedule next refresh
            }
        }, 10000) // Refresh every 10 seconds
    }
    
    // Implementation of OnActiveSessionsChangedListener
    override fun onActiveSessionsChanged(controllers: List<MediaController>?) {
        Log.d(TAG, "🔄 Active sessions changed! Found ${controllers?.size ?: 0} controllers")
        
        // Clean up old controllers that are no longer active
        val currentPackages = controllers?.map { it.packageName }?.toSet() ?: emptySet()
        val oldPackages = activeControllers.keys.toSet()
        
        // Remove controllers for apps that are no longer active
        oldPackages.subtract(currentPackages).forEach { packageName ->
            Log.d(TAG, "📱 Removing controller for closed app: $packageName")
            controllerCallbacks[packageName]?.let { callback ->
                activeControllers[packageName]?.unregisterCallback(callback)
            }
            activeControllers.remove(packageName)
            controllerCallbacks.remove(packageName)
        }
        
        // Add controllers for new active apps
        controllers?.forEach { controller ->
            val packageName = controller.packageName
            if (isMediaApp(packageName) && !activeControllers.containsKey(packageName)) {
                Log.d(TAG, "📱 Adding controller for new app: $packageName")
                activeControllers[packageName] = controller
                setupControllerCallback(controller)
                
                // Get initial metadata if available
                controller.metadata?.let { metadata ->
                    handleMediaSessionMetadata(metadata, controller.playbackState, packageName)
                }
            }
        }
    }
    
    private fun updateActiveMediaControllers() {
        try {
            val activeSessions = mediaSessionManager?.getActiveSessions(
                ComponentName(this, MediaListenerService::class.java)
            ) ?: emptyList()
            
            Log.d(TAG, "🔍 Updating active media controllers. Found ${activeSessions.size} active sessions")
            
            // Get current active packages
            val currentPackages = activeSessions.map { it.packageName }.toSet()
            val knownPackages = activeControllers.keys.toSet()
            
            // Clean up controllers for packages that are no longer active
            knownPackages.subtract(currentPackages).forEach { packageName ->
                Log.d(TAG, "🗑️ Removing controller for inactive app: $packageName")
                controllerCallbacks[packageName]?.let { callback ->
                    activeControllers[packageName]?.unregisterCallback(callback)
                }
                activeControllers.remove(packageName)
                controllerCallbacks.remove(packageName)
            }
            
            // Add/update controllers for active sessions
            activeSessions.forEach { controller ->
                val packageName = controller.packageName
                if (isMediaApp(packageName)) {
                    if (!activeControllers.containsKey(packageName)) {
                        Log.d(TAG, "➕ Adding media controller for: $packageName")
                        activeControllers[packageName] = controller
                        setupControllerCallback(controller)
                        
                        // Get initial metadata if available
                        controller.metadata?.let { metadata ->
                            handleMediaSessionMetadata(metadata, controller.playbackState, packageName)
                        }
                    } else {
                        // Update existing controller reference in case it changed
                        activeControllers[packageName] = controller
                    }
                }
            }
            
        } catch (e: SecurityException) {
            Log.w(TAG, "Cannot access media sessions - notification listener permission needed")
        } catch (e: Exception) {
            Log.e(TAG, "Error updating media controllers", e)
        }
    }
    
    private fun setupControllerCallback(controller: MediaController) {
        val callback = object : MediaController.Callback() {
            override fun onMetadataChanged(metadata: AndroidMediaMetadata?) {
                super.onMetadataChanged(metadata)
                metadata?.let { 
                    handleMediaSessionMetadata(it, controller.playbackState, controller.packageName)
                }
            }
            
            override fun onPlaybackStateChanged(state: PlaybackState?) {
                super.onPlaybackStateChanged(state)
                controller.metadata?.let { metadata ->
                    handleMediaSessionMetadata(metadata, state, controller.packageName)
                }
            }
        }
        
        controllerCallbacks[controller.packageName] = callback
        controller.registerCallback(callback)
    }
    
    private fun cleanupMediaControllers() {
        activeControllers.forEach { (packageName, controller) ->
            try {
                controllerCallbacks[packageName]?.let { callback ->
                    controller.unregisterCallback(callback)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error unregistering controller callback", e)
            }
        }
        activeControllers.clear()
        controllerCallbacks.clear()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        sbn?.let { 
            extractMediaInfo(it)
            // Also update active controllers when new media notifications appear
            updateActiveMediaControllers()
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        sbn?.let { 
            if (isMediaNotification(it)) {
                // Check if we still have active media sessions before clearing
                val hasActiveMedia = activeControllers.values.any { controller ->
                    controller.playbackState?.state == PlaybackState.STATE_PLAYING
                }
                
                if (!hasActiveMedia) {
                    val emptyMetadata = MediaMetadata(isPlaying = false)
                    currentMediaMetadata = emptyMetadata
                    mediaUpdateListener?.invoke(emptyMetadata)
                    Log.d(TAG, "All media stopped")
                }
            }
        }
    }

    private fun handleMediaSessionMetadata(
        metadata: AndroidMediaMetadata, 
        playbackState: PlaybackState?, 
        packageName: String
    ) {
        try {
            val isPlaying = playbackState?.state == PlaybackState.STATE_PLAYING
            
            val title = metadata.getString(AndroidMediaMetadata.METADATA_KEY_TITLE)
            val artist = metadata.getString(AndroidMediaMetadata.METADATA_KEY_ARTIST)
            val album = metadata.getString(AndroidMediaMetadata.METADATA_KEY_ALBUM)
            val duration = metadata.getLong(AndroidMediaMetadata.METADATA_KEY_DURATION)
            val position = playbackState?.position ?: 0L
            
            val mediaMetadata = MediaMetadata(
                title = title,
                artist = artist,
                album = album,
                packageName = packageName,
                isPlaying = isPlaying,
                duration = duration,
                position = position
            )
            
            currentMediaMetadata = mediaMetadata
            mediaUpdateListener?.invoke(mediaMetadata)
            
            Log.d(TAG, "Media Session - Title: $title, Artist: $artist, Album: $album, Playing: $isPlaying, Package: $packageName")
        } catch (e: Exception) {
            Log.e(TAG, "Error handling media session metadata", e)
        }
    }
    
    private fun extractMediaInfo(sbn: StatusBarNotification) {
        try {
            val notification = sbn.notification
            val extras = notification.extras
            val packageName = sbn.packageName
            
            // Check if this is a media notification and we don't have media session data
            if (isMediaNotification(sbn) && !activeControllers.containsKey(packageName)) {
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
                
                // Only use notification data if we don't have media session data
                if (currentMediaMetadata?.packageName != packageName) {
                    currentMediaMetadata = metadata
                    mediaUpdateListener?.invoke(metadata)
                    Log.d(TAG, "Notification fallback - Title: $title, Artist: $artist, Package: $packageName")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting media info from notification", e)
        }
    }
    
    private fun isMediaApp(packageName: String): Boolean {
        val lowerPackage = packageName.lowercase()
        val mediaApps = listOf(
            "spotify", "com.spotify.music",
            "youtube", "com.google.android.youtube",
            "music", "com.google.android.music",
            "soundcloud", "com.soundcloud.android",
            "pandora", "com.pandora.android",
            "apple.music", "com.apple.android.music",
            "deezer", "deezer.android.app"
        )
        return mediaApps.any { lowerPackage.contains(it) }
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