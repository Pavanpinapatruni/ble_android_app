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
    
    // Position tracking
    private var lastKnownPosition = 0L
    private var lastPositionUpdateTime = 0L
    private var lastSentPositionSeconds = -1L  // Track last sent position in seconds
    private var isCurrentlyPlaying = false
    private val positionUpdateHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var positionUpdateRunnable: Runnable? = null
    
    // Simple manual position tracking as fallback
    private var manualPositionTracker = 0L
    private var trackStartTime = 0L
    private var useManualTracking = false
    
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
        
        fun executeMediaCommand(command: Int): Boolean {
            return instance?.executeMediaCommand(command) ?: false
        }
        
        fun getActiveControllersInfo(): String {
            return instance?.getActiveControllersInfo() ?: "MediaListenerService not active"
        }
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
        stopPositionUpdates()
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
                Log.d(TAG, "Playback state changed for ${controller.packageName}: ${state?.state}")
                
                // Manage position tracking based on playback state
                when (state?.state) {
                    PlaybackState.STATE_PLAYING -> {
                        Log.d(TAG, "🎵 Media started playing - starting position updates")
                        // Don't reset position tracking if we already have valid position data
                        // This preserves the actual current position when connecting mid-song
                        val currentPos = calculateCurrentPosition(state)
                        if (currentPos > 0 && !useManualTracking) {
                            Log.d(TAG, "🎯 Preserving current position: ${currentPos}ms on playback start")
                            lastKnownPosition = currentPos
                            lastPositionUpdateTime = System.currentTimeMillis()
                        } else if (lastSentPositionSeconds == -1L) {
                            // Only reset if this is truly a new session
                            Log.d(TAG, "🆕 New playback session - initializing position tracking")
                            lastKnownPosition = 0L
                            lastPositionUpdateTime = System.currentTimeMillis()
                            useManualTracking = false
                            manualPositionTracker = 0L
                            trackStartTime = System.currentTimeMillis()
                        }
                        // Send state change update immediately
                        sendStateChangeUpdate(controller, state)
                        startPositionUpdates()
                    }
                    PlaybackState.STATE_PAUSED,
                    PlaybackState.STATE_STOPPED -> {
                        Log.d(TAG, "⏸️ Media paused/stopped - stopping position updates")
                        useManualTracking = false
                        // Send state change update immediately
                        sendStateChangeUpdate(controller, state)
                        stopPositionUpdates()
                    }
                }
                
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

    private fun sendStateChangeUpdate(controller: MediaController, playbackState: PlaybackState) {
        try {
            val isPlaying = playbackState.state == PlaybackState.STATE_PLAYING
            
            // Update current metadata with new state
            currentMediaMetadata?.let { metadata ->
                val updatedMetadata = metadata.copy(
                    isPlaying = isPlaying,
                    position = calculateCurrentPosition(playbackState)
                )
                currentMediaMetadata = updatedMetadata
                mediaUpdateListener?.invoke(updatedMetadata)
                
                Log.d(TAG, "🔄 State change update sent: Playing=${isPlaying}")
            } ?: run {
                // If no current metadata, create basic metadata with state
                controller.metadata?.let { androidMetadata ->
                    val title = androidMetadata.getString(AndroidMediaMetadata.METADATA_KEY_TITLE) ?: "Unknown"
                    val artist = androidMetadata.getString(AndroidMediaMetadata.METADATA_KEY_ARTIST) ?: "Unknown"
                    val duration = androidMetadata.getLong(AndroidMediaMetadata.METADATA_KEY_DURATION)
                    val position = calculateCurrentPosition(playbackState)
                    
                    val mediaMetadata = MediaMetadata(
                        title = title,
                        artist = artist,
                        album = androidMetadata.getString(AndroidMediaMetadata.METADATA_KEY_ALBUM) ?: "",
                        duration = if (duration > 0) duration else 0L,
                        position = position,
                        isPlaying = isPlaying,
                        packageName = controller.packageName
                    )
                    
                    currentMediaMetadata = mediaMetadata
                    mediaUpdateListener?.invoke(mediaMetadata)
                    
                    Log.d(TAG, "🆕 Created metadata for state change: Playing=${isPlaying}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending state change update", e)
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
            val position = calculateCurrentPosition(playbackState)
            val positionSeconds = position / 1000L
            
            Log.d(TAG, "🕐 Position tracking - Raw: ${playbackState?.position ?: "null"}, Calculated: ${position}ms (${positionSeconds}s), Playing: $isPlaying")
            
            val mediaMetadata = MediaMetadata(
                title = title,
                artist = artist,
                album = album,
                packageName = packageName,
                isPlaying = isPlaying,
                duration = if (duration > 0) duration else 0L,
                position = position
            )
            
            // Check if this is a new track (reset position tracking)
            if (currentMediaMetadata?.title != title) {
                Log.d(TAG, "🆕 New track detected, resetting position tracking")
                lastSentPositionSeconds = -1L
                lastKnownPosition = 0L
                lastPositionUpdateTime = System.currentTimeMillis()
                useManualTracking = false
                manualPositionTracker = 0L
                trackStartTime = System.currentTimeMillis()
                
                // For new tracks, update metadata immediately
                currentMediaMetadata = mediaMetadata
                mediaUpdateListener?.invoke(mediaMetadata)
            } else {
                // Check if playing state has changed for existing track
                val stateChanged = currentMediaMetadata?.isPlaying != isPlaying
                
                if (stateChanged) {
                    Log.d(TAG, "🎭 State changed for existing track: ${currentMediaMetadata?.isPlaying} → $isPlaying")
                    currentMediaMetadata = mediaMetadata
                    mediaUpdateListener?.invoke(mediaMetadata)
                } else {
                    // For existing tracks with same state, only update metadata but don't invoke listener
                    // (position updates are handled by the periodic updater)
                    currentMediaMetadata = mediaMetadata
                    Log.d(TAG, "📝 Updated metadata for existing track (position updates handled separately)")
                }
            }
            
            Log.d(TAG, "Media Session - Title: $title, Artist: $artist, Album: $album, Playing: $isPlaying, Position: ${positionSeconds}s, Package: $packageName")
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
    
    // Media control methods for BLE commands
    fun executeMediaCommand(command: Int): Boolean {
        Log.d(TAG, "🎮 Executing media command: 0x${"%02x".format(command)}")
        
        // Find the active media controller (prioritize currently playing)
        val activeController = activeControllers.values.firstOrNull { controller ->
            controller.playbackState?.state == PlaybackState.STATE_PLAYING
        } ?: activeControllers.values.firstOrNull()
        
        if (activeController == null) {
            Log.w(TAG, "❌ No active media controller found for command execution")
            Log.d(TAG, "📊 Available controllers: ${activeControllers.keys}")
            return false
        }
        
        try {
            val transportControls = activeController.transportControls
            val playbackState = activeController.playbackState
            Log.d(TAG, "🎵 Using controller for: ${activeController.packageName}")
            Log.d(TAG, "🎵 Current playback state: ${playbackState?.state}")
            
            when (command) {
                0x01 -> {
                    Log.d(TAG, "▶️ Executing PLAY command")
                    transportControls.play()
                }
                0x02 -> {
                    Log.d(TAG, "⏸️ Executing PAUSE command")
                    transportControls.pause()
                }
                0x03 -> {
                    Log.d(TAG, "⏹️ Executing STOP command")
                    transportControls.stop()
                }
                0x04 -> {
                    Log.d(TAG, "⏭️ Executing NEXT TRACK command")
                    transportControls.skipToNext()
                }
                0x05 -> {
                    Log.d(TAG, "⏮️ Executing PREVIOUS TRACK command")
                    transportControls.skipToPrevious()
                }
                0x10 -> {
                    Log.d(TAG, "⏪ Executing FAST REWIND command")
                    transportControls.rewind()
                }
                0x11 -> {
                    Log.d(TAG, "⏩ Executing FAST FORWARD command")
                    transportControls.fastForward()
                }
                0x30 -> {
                    Log.d(TAG, "🔀 GOTO command received (not implemented)")
                    Log.w(TAG, "GOTO command requires position parameter - not supported yet")
                    return false
                }
                else -> {
                    Log.w(TAG, "❓ Unsupported command: 0x${"%02x".format(command)}")
                    return false
                }
            }
            
            Log.d(TAG, "✅ Media command executed successfully")
            
            // Give a small delay for the command to take effect, then check state
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                val newState = activeController.playbackState
                Log.d(TAG, "🔄 Playback state after command: ${newState?.state}")
            }, 500)
            
            return true
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error executing media command", e)
            return false
        }
    }
    
    fun getCurrentActiveController(): MediaController? {
        return activeControllers.values.firstOrNull { controller ->
            controller.playbackState?.state == PlaybackState.STATE_PLAYING
        } ?: activeControllers.values.firstOrNull()
    }
    
    fun getActiveControllersInfo(): String {
        return if (activeControllers.isEmpty()) {
            "No active media controllers"
        } else {
            "Active controllers: ${activeControllers.keys.joinToString(", ")}"
        }
    }
    
    /**
     * Calculate current position based on playback state and elapsed time
     */
    private fun calculateCurrentPosition(playbackState: PlaybackState?): Long {
        if (playbackState == null) {
            Log.d(TAG, "⚠️ PlaybackState is null, using manual tracking")
            return if (useManualTracking && isCurrentlyPlaying) {
                val elapsed = System.currentTimeMillis() - trackStartTime
                manualPositionTracker + elapsed
            } else 0L
        }
        
        val rawPosition = playbackState.position
        val lastUpdateTime = playbackState.lastPositionUpdateTime
        val playbackSpeed = playbackState.playbackSpeed
        val currentState = playbackState.state
        
        Log.d(TAG, "🔍 PlaybackState details: position=$rawPosition, updateTime=$lastUpdateTime, speed=$playbackSpeed, state=$currentState")
        
        // Accept any non-negative position (including 0 at start of track)
        if (rawPosition >= 0 && lastUpdateTime > 0) {
            Log.d(TAG, "✅ Using valid PlaybackState position: $rawPosition")
            useManualTracking = false
            
            val currentTime = System.currentTimeMillis()
            val timeSinceUpdate = currentTime - lastUpdateTime
            
            // Update our tracking variables
            lastKnownPosition = rawPosition
            lastPositionUpdateTime = lastUpdateTime
            isCurrentlyPlaying = playbackState.state == PlaybackState.STATE_PLAYING
            
            // Calculate estimated current position
            if (isCurrentlyPlaying && playbackSpeed > 0 && timeSinceUpdate < 60000) {
                val estimatedPosition = rawPosition + (timeSinceUpdate * playbackSpeed).toLong()
                Log.d(TAG, "📊 Position calc - Raw: $rawPosition, Elapsed: ${timeSinceUpdate}ms, Speed: $playbackSpeed, Estimated: $estimatedPosition")
                return estimatedPosition.coerceAtLeast(0)
            }
            return rawPosition
        }
        
        // Fallback to manual tracking only if we really can't get position from PlaybackState
        Log.d(TAG, "🔄 PlaybackState position unavailable, checking manual tracking")
        
        if (!useManualTracking && currentState == PlaybackState.STATE_PLAYING) {
            Log.d(TAG, "🆕 Starting manual position tracking from last known position")
            useManualTracking = true
            manualPositionTracker = lastKnownPosition
            trackStartTime = System.currentTimeMillis()
            return lastKnownPosition
        }
        
        if (useManualTracking && currentState == PlaybackState.STATE_PLAYING) {
            val elapsed = System.currentTimeMillis() - trackStartTime
            val currentPosition = manualPositionTracker + elapsed
            Log.d(TAG, "⏱️ Manual tracking: ${currentPosition}ms (${elapsed}ms elapsed)")
            return currentPosition
        }
        
        Log.d(TAG, "🚫 No position tracking available")
        return lastKnownPosition.coerceAtLeast(0L)
    }
    
    /**
     * Start periodic position updates for currently playing media
     */
    private fun startPositionUpdates() {
        stopPositionUpdates() // Stop any existing updates
        
        positionUpdateRunnable = object : Runnable {
            override fun run() {
                try {
                    getCurrentActiveController()?.let { controller ->
                        val playbackState = controller.playbackState
                        if (playbackState?.state == PlaybackState.STATE_PLAYING && currentMediaMetadata != null) {
                            val currentPosition = calculateCurrentPosition(playbackState)
                            val currentPositionSeconds = (currentPosition / 1000).toInt()
                            
                            // Only update if position has changed by at least 1 second
                            if (currentPositionSeconds != lastSentPositionSeconds.toInt()) {
                                val updatedMetadata = currentMediaMetadata!!.copy(position = currentPosition)
                                currentMediaMetadata = updatedMetadata
                                mediaUpdateListener?.invoke(updatedMetadata)
                                
                                Log.d(TAG, "🔄 Position update: ${currentPositionSeconds}s (${currentPosition}ms)")
                                lastSentPositionSeconds = currentPositionSeconds.toLong()
                            }
                            
                            // Schedule next update
                            positionUpdateHandler.postDelayed(this, 1000)
                        } else {
                            Log.d(TAG, "⏹️ Position updates stopped - not playing or no metadata")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error during position update", e)
                }
            }
        }
        
        // Add small delay to prevent rapid-fire updates when changing tracks
        val initialDelay = if (lastSentPositionSeconds == -1L) 500L else 100L
        positionUpdateHandler.postDelayed(positionUpdateRunnable!!, initialDelay)
        Log.d(TAG, "▶️ Started position updates with ${initialDelay}ms delay")
    }
    
    /**
     * Stop periodic position updates
     */
    private fun stopPositionUpdates() {
        positionUpdateRunnable?.let {
            positionUpdateHandler.removeCallbacks(it)
            positionUpdateRunnable = null
            Log.d(TAG, "⏹️ Stopped position updates")
        }
    }
}