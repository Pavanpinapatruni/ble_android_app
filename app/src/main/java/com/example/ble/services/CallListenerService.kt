package com.example.ble.services

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.telecom.TelecomManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import android.os.Build
import androidx.annotation.RequiresApi
import com.example.ble.models.CallMetadata
import com.example.ble.ble.BleManager
import java.util.concurrent.Executor
import java.util.concurrent.Executors

private const val TAG = "CallListenerService"

/**
 * Service to monitor phone calls and notify BLE clients via TBS
 * 
 * This service uses two approaches for complete call monitoring:
 * 1. TelephonyManager/PhoneStateListener - for call state timing (ringing, active, idle)
 * 2. CallNotificationListener - for caller name/ID (works with Android 9+ privacy restrictions)
 * 
 * The notification listener approach is the standard method used by smartwatches
 * and companion apps to get caller information without requiring Default Dialer status.
 */
class CallListenerService : Service() {
    
    private var telephonyManager: TelephonyManager? = null
    private var telecomManager: TelecomManager? = null
    private var callStateListener: PhoneStateListener? = null
    private var callbackExecutor: Executor = Executors.newSingleThreadExecutor()
    
    // Current call state tracking
    private var currentCallState: CallMetadata? = null
    private var lastPhoneNumber: String? = null
    
    companion object {
        private var instance: CallListenerService? = null
        private var bleManager: BleManager? = null
        
        /**
         * Start the call monitoring service
         */
        fun startService(context: Context, bleManagerInstance: BleManager) {
            bleManager = bleManagerInstance
            val intent = Intent(context, CallListenerService::class.java)
            context.startForegroundService(intent)
        }
        
        /**
         * Stop the call monitoring service
         */
        fun stopService(context: Context) {
            val intent = Intent(context, CallListenerService::class.java)
            context.stopService(intent)
        }
        
        /**
         * Execute a call control command (answer, reject, end call, etc.)
         */
        fun executeCallCommand(command: Int): Boolean {
            Log.d(TAG, "Executing call command: 0x${"%02x".format(command)}")
            
            val telecomManager = instance?.telecomManager
            if (telecomManager == null) {
                Log.w(TAG, "TelecomManager not available for call commands")
                return false
            }
            
            return try {
                when (command) {
                    0x01 -> {
                        Log.d(TAG, "ACCEPT_CALL command")
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            telecomManager.acceptRingingCall()
                            true
                        } else {
                            Log.w(TAG, "acceptRingingCall requires API 26+")
                            false
                        }
                    }
                    0x02 -> {
                        Log.d(TAG, "REJECT_CALL command")
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            telecomManager.endCall()
                            true
                        } else {
                            Log.w(TAG, "endCall requires API 28+")
                            false
                        }
                    }
                    0x03 -> {
                        Log.d(TAG, "END_CALL command")
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            telecomManager.endCall()
                            true
                        } else {
                            Log.w(TAG, "endCall requires API 28+")
                            false
                        }
                    }
                    0x04 -> {
                        Log.d(TAG, "HOLD_CALL command")
                        // Hold call functionality would require more complex call management
                        Log.w(TAG, "Hold call not implemented yet")
                        false
                    }
                    0x05 -> {
                        Log.d(TAG, "UNHOLD_CALL command") 
                        // Unhold call functionality would require more complex call management
                        Log.w(TAG, "Unhold call not implemented yet")
                        false
                    }
                    else -> {
                        Log.w(TAG, "Unknown call command: 0x${"%02x".format(command)}")
                        false
                    }
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "Security exception executing call command", e)
                false
            } catch (e: Exception) {
                Log.e(TAG, "Error executing call command", e)
                false
            }
        }
        
        /**
         * Get current call state information
         */
        fun getCurrentCallState(): CallMetadata? = instance?.currentCallState
        
        /**
         * Get debug info about call monitoring capabilities
         */
        fun getCallMonitoringInfo(): String {
            val instance = instance ?: return "CallListenerService not running"
            
            val telephonyAvailable = instance.telephonyManager != null
            val telecomAvailable = instance.telecomManager != null
            val currentState = instance.currentCallState?.state?.name ?: "UNKNOWN"
            
            return "Call Monitoring: Telephony=$telephonyAvailable, Telecom=$telecomAvailable, State=$currentState"
        }
        
        /**
         * Update caller name from notification listener (main solution for caller ID)
         */
        fun updateCallerNameFromNotification(name: String) {
            val instance = instance ?: return
            
            // Only update if we are currently in a call state (prevent random notification noise)
            val currentState = instance.currentCallState
            if (currentState != null && currentState.state != CallMetadata.CallState.IDLE) {
                // Only update if the new name is better than the current one
                val currentName = currentState.callerName
                if (shouldUpdateCallerName(currentName, name)) {
                    Log.d(TAG, "Injecting Friendly Name from Notification: '$name' (replacing '$currentName')")
                    
                    val updatedMetadata = currentState.copy(
                        callerName = name
                    )
                    instance.updateCallState(updatedMetadata)
                } else {
                    Log.v(TAG, "Keeping existing caller name '$currentName' instead of '$name'")
                }
            } else {
                Log.v(TAG, "Ignoring notification caller name '$name' - no active call")
            }
        }
        
        /**
         * Determine if we should replace the current caller name with a new one
         */
        private fun shouldUpdateCallerName(current: String?, new: String): Boolean {
            // If we have no current name, accept any valid new name
            if (current.isNullOrEmpty() || current == "Unknown Number" || current == "Private Number") {
                return true
            }
            
            // Replace default/placeholder names with proper caller names
            val defaultNames = listOf(
                "Incoming Call", "Unknown Number", "Private Number", "Blocked", 
                "Call in progress", "Ongoing call"
            )
            
            if (defaultNames.any { current.equals(it, ignoreCase = true) }) {
                Log.d(TAG, "Upgrading default name '$current' to caller name '$new'")
                return true
            }
            
            // Don't replace a good name with system messages
            val systemNames = listOf(
                "Unknown Number", "Private Number", "Blocked", "Spam protection disabled",
                "Allow Truecaller", "Premium", "Subscription"
            )
            
            if (systemNames.any { new.contains(it, ignoreCase = true) }) {
                Log.d(TAG, "Rejecting system/promotional name: '$new'")
                return false
            }
            
            // Don't replace a proper name with a phone number
            val currentIsPhoneNumber = current.replace(Regex("[\u202A-\u202E\u2066-\u2069]"), "").matches(Regex("^[+\\d\\s\\-\\(\\)]+$"))
            val newIsPhoneNumber = new.replace(Regex("[\u202A-\u202E\u2066-\u2069]"), "").matches(Regex("^[+\\d\\s\\-\\(\\)]+$"))
            
            if (!currentIsPhoneNumber && current.any { it.isLetter() } && newIsPhoneNumber) {
                Log.d(TAG, "Keeping name '$current' instead of number '$new'")
                return false
            }
            
            // Replace phone number with proper name (prioritize contact names)
            if (currentIsPhoneNumber && !newIsPhoneNumber && new.any { it.isLetter() }) {
                Log.d(TAG, "Upgrading number '$current' to name '$new'")
                return true
            }
            
            // For other cases, keep the first valid name (don't keep changing)
            Log.d(TAG, "Keeping stable caller name '$current'")
            return false
        }
        
        /**
         * Update call state from PhoneStateReceiver (bridge method)
         * This method now properly delegates to handleCallStateChange for correct state logic
         */
        fun updateCallStateFromReceiver(state: String, phoneNumber: String?) {
            val instance = instance ?: return
            
            Log.d(TAG, "Received call state from PhoneStateReceiver: $state, number: $phoneNumber")
            
            // Convert string state to TelephonyManager constants and delegate to proper handler
            val telephonyState = when (state) {
                "IDLE" -> TelephonyManager.CALL_STATE_IDLE
                "RINGING" -> TelephonyManager.CALL_STATE_RINGING
                "OFFHOOK" -> TelephonyManager.CALL_STATE_OFFHOOK
                else -> TelephonyManager.CALL_STATE_IDLE
            }
            
            // Use the proper call state handling with all our OFFHOOK logic
            instance.handleCallStateChange(telephonyState, phoneNumber)
        }
    }
    
    override fun onCreate() {
        super.onCreate()
        instance = this
        
        Log.d(TAG, "CallListenerService created")
        
        // Initialize telephony and telecom managers
        telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        telecomManager = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        
        startCallMonitoring()
        
        // Create notification for foreground service
        createNotificationChannel()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "CallListenerService started")
        return START_STICKY
    }
    
    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "CallListenerService destroyed")
        
        stopCallMonitoring()
        instance = null
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    @SuppressLint("MissingPermission")
    private fun startCallMonitoring() {
        val telephonyManager = this.telephonyManager
        if (telephonyManager == null) {
            Log.e(TAG, "TelephonyManager not available")
            return
        }
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Use TelephonyCallback for Android 12+
                val callback = createTelephonyCallback()
                telephonyManager.registerTelephonyCallback(callbackExecutor, callback)
                Log.d(TAG, "Registered TelephonyCallback for call monitoring")
            } else {
                // Use PhoneStateListener for older versions
                callStateListener = createPhoneStateListener()
                telephonyManager.listen(callStateListener, PhoneStateListener.LISTEN_CALL_STATE)
                Log.d(TAG, "Registered PhoneStateListener for call monitoring")
            }
            
            // Send initial idle state
            updateCallState(CallMetadata.createIdleState())
            
        } catch (e: SecurityException) {
            Log.e(TAG, "Missing permissions for call monitoring", e)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting call monitoring", e)
        }
    }
    
    private fun stopCallMonitoring() {
        try {
            callStateListener?.let { listener ->
                telephonyManager?.listen(listener, PhoneStateListener.LISTEN_NONE)
                callStateListener = null
                Log.d(TAG, "Unregistered call state listener")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping call monitoring", e)
        }
    }
    
    @RequiresApi(Build.VERSION_CODES.S)
    private fun createTelephonyCallback(): TelephonyCallback {
        return object : TelephonyCallback(), TelephonyCallback.CallStateListener {
            override fun onCallStateChanged(state: Int) {
                handleCallStateChange(state, null)
            }
        }
    }
    
    private fun createPhoneStateListener(): PhoneStateListener {
        return object : PhoneStateListener() {
            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                handleCallStateChange(state, phoneNumber)
            }
        }
    }
    
    private fun handleCallStateChange(state: Int, phoneNumber: String?) {
        Log.d(TAG, "Call state changed: $state, phone: ${phoneNumber ?: "unknown"}")
        
        // Store phone number for later use
        if (!phoneNumber.isNullOrEmpty()) {
            lastPhoneNumber = phoneNumber
        }
        
        val callMetadata = when (state) {
            TelephonyManager.CALL_STATE_IDLE -> {
                Log.d(TAG, "Call ended or no call")
                val terminationReason = if (currentCallState?.isCallActive() == true) {
                    CallMetadata.TerminationReason.UNKNOWN // Could be local or remote
                } else {
                    null
                }
                
                if (currentCallState?.isCallActive() == true) {
                    // Call was active and now ended
                    CallMetadata.createTerminatedCall(
                        phoneNumber = currentCallState?.phoneNumber ?: lastPhoneNumber,
                        callerName = currentCallState?.callerName,
                        callId = currentCallState?.callId,
                        terminationReason = terminationReason ?: CallMetadata.TerminationReason.UNKNOWN
                    )
                } else {
                    // Just idle state
                    CallMetadata.createIdleState()
                }
            }
            TelephonyManager.CALL_STATE_RINGING -> {
                Log.d(TAG, "Incoming call ringing")
                // Create initial call metadata - caller name will be updated by notification listener
                CallMetadata.createIncomingCall(
                    phoneNumber = phoneNumber ?: lastPhoneNumber,
                    callerName = "Incoming Call", // Temporary - will be updated by notification
                    callId = generateCallId()
                )
            }
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                Log.d(TAG, "Call off hook (active or dialing)")
                Log.d(TAG, "Current call state: ${currentCallState?.state?.name ?: "null"}")
                Log.d(TAG, "Current caller name: ${currentCallState?.callerName ?: "null"}")
                Log.d(TAG, "Phone number from event: $phoneNumber")
                
                // Determine if this is an incoming call being answered or outgoing call
                val isIncomingBeingAnswered = currentCallState?.state == CallMetadata.CallState.INCOMING
                val isOutgoingDialing = currentCallState?.state == CallMetadata.CallState.DIALING
                val isAlreadyActive = currentCallState?.state == CallMetadata.CallState.ACTIVE
                val isPreviousCallIdle = currentCallState?.state == CallMetadata.CallState.IDLE
                
                when {
                    isIncomingBeingAnswered -> {
                        // Incoming call was answered - preserve existing caller name from notification
                        Log.d(TAG, "Incoming call answered - transitioning to ACTIVE")
                        CallMetadata.createActiveCall(
                            phoneNumber = currentCallState?.phoneNumber ?: phoneNumber ?: lastPhoneNumber,
                            callerName = currentCallState?.callerName ?: "Active Call",
                            callId = currentCallState?.callId ?: generateCallId(),
                            isIncoming = true
                        )
                    }
                    isOutgoingDialing -> {
                        // Check if this is a rapid duplicate OFFHOOK or genuine call answer
                        val timeSinceLastUpdate = System.currentTimeMillis() - (currentCallState?.timestamp ?: 0)
                        if (timeSinceLastUpdate > 500) { // More than 500ms - likely call answered
                            Log.d(TAG, "Second OFFHOOK after ${timeSinceLastUpdate}ms: Outgoing call answered - transitioning DIALING to ACTIVE")
                            CallMetadata.createActiveCall(
                                phoneNumber = currentCallState?.phoneNumber ?: phoneNumber ?: lastPhoneNumber,
                                callerName = currentCallState?.callerName ?: "Outgoing Call",
                                callId = currentCallState?.callId ?: generateCallId(),
                                isIncoming = false
                            )
                        } else {
                            // Too rapid - likely duplicate OFFHOOK during call setup
                            Log.d(TAG, "Ignoring rapid OFFHOOK during DIALING (${timeSinceLastUpdate}ms gap) - keeping DIALING state")
                            currentCallState!!
                        }
                    }
                    isPreviousCallIdle -> {
                        // Starting a new outgoing call after a previous call ended - start with DIALING state
                        Log.d(TAG, "New outgoing call after IDLE - setting to DIALING state")
                        val metadata = CallMetadata.createOutgoingCall(
                            phoneNumber = phoneNumber ?: lastPhoneNumber ?: "Unknown Number",
                            callerName = "Outgoing Call",
                            callId = generateCallId()
                        )
                        
                        // Schedule transition to ACTIVE state for outgoing calls (backup mechanism)
                        scheduleOutgoingCallActivation(metadata)
                        
                        metadata
                    }
                    isAlreadyActive -> {
                        // Duplicate OFFHOOK event during active call - check if this is actually a new call
                        val timeSinceLastUpdate = System.currentTimeMillis() - (currentCallState?.timestamp ?: 0)
                        if (timeSinceLastUpdate > 1000) { // More than 1 second - likely new call
                            Log.d(TAG, "New outgoing call detected during ACTIVE (time gap: ${timeSinceLastUpdate}ms) - resetting to DIALING")
                            val metadata = CallMetadata.createOutgoingCall(
                                phoneNumber = phoneNumber ?: lastPhoneNumber ?: "Unknown Number",
                                callerName = "Outgoing Call",
                                callId = generateCallId()
                            )
                            
                            scheduleOutgoingCallActivation(metadata)
                            metadata
                        } else {
                            // Real duplicate - ignore
                            Log.d(TAG, "Duplicate OFFHOOK detected during ACTIVE (time gap: ${timeSinceLastUpdate}ms) - ignoring")
                            currentCallState!! // Safe to use !! here since we checked it's not null above
                        }
                    }
                    currentCallState?.state == CallMetadata.CallState.DIALING -> {
                        // Second OFFHOOK during DIALING - check if duplicate or new call
                        val timeSinceLastUpdate = System.currentTimeMillis() - (currentCallState?.timestamp ?: 0)
                        if (timeSinceLastUpdate > 1000) { // More than 1 second - likely new call
                            Log.d(TAG, "New outgoing call detected during DIALING (time gap: ${timeSinceLastUpdate}ms) - creating new call")
                            val metadata = CallMetadata.createOutgoingCall(
                                phoneNumber = phoneNumber ?: lastPhoneNumber ?: "Unknown Number",
                                callerName = "Outgoing Call",
                                callId = generateCallId()
                            )
                            
                            scheduleOutgoingCallActivation(metadata)
                            metadata
                        } else {
                            // Real duplicate during DIALING - ignore
                            Log.d(TAG, "Duplicate OFFHOOK detected during DIALING (time gap: ${timeSinceLastUpdate}ms) - ignoring")
                            currentCallState!!
                        }
                    }
                    else -> {
                        // First OFFHOOK for outgoing call - start with DIALING state
                        Log.d(TAG, "First OFFHOOK: Outgoing call starting - setting to DIALING state")
                        val metadata = CallMetadata.createOutgoingCall(
                            phoneNumber = phoneNumber ?: lastPhoneNumber ?: "Unknown Number",
                            callerName = "Outgoing Call",
                            callId = generateCallId()
                        )
                        
                        // Schedule transition to ACTIVE state for outgoing calls (backup mechanism)
                        scheduleOutgoingCallActivation(metadata)
                        
                        metadata
                    }
                }
            }
            else -> {
                Log.w(TAG, "Unknown call state: $state")
                currentCallState ?: CallMetadata.createIdleState()
            }
        }
        
        updateCallState(callMetadata)
    }
    
    private fun updateCallState(metadata: CallMetadata) {
        currentCallState = metadata
        
        Log.d(TAG, "Updated call state to: ${metadata.state.name} for ${metadata.getDisplayName()}")
        Log.d(TAG, "Call details: state=${metadata.state.name}, caller='${metadata.callerName}', number='${metadata.phoneNumber}'")
        
        // Notify BLE clients
        bleManager?.let { manager ->
            try {
                Log.d(TAG, "Notifying BLE clients of call state change")
                manager.updateCallMetadata(metadata)
            } catch (e: Exception) {
                Log.e(TAG, "Error notifying BLE clients", e)
            }
        }
    }
    
    private fun getContactName(phoneNumber: String?): String? {
        // Note: We primarily rely on CallNotificationListener for caller names now
        // This is just a fallback for cases where notification doesn't provide a name
        return phoneNumber?.let { "Contact: $it" }
    }
    
    private fun generateCallId(): String {
        return "call_${System.currentTimeMillis()}"
    }
    
    private fun createNotificationChannel() {
        // Create notification for foreground service
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelId = "call_monitor_channel"
            val channel = android.app.NotificationChannel(
                channelId,
                "Call Monitoring",
                android.app.NotificationManager.IMPORTANCE_LOW
            )
            
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            notificationManager.createNotificationChannel(channel)
            
            val notification = android.app.Notification.Builder(this, channelId)
                .setContentTitle("Call Monitoring Active")
                .setContentText("Monitoring phone calls for BLE")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .build()
            
            startForeground(1, notification)
        }
    }
    
    /**
     * Schedule transition from DIALING to ACTIVE for outgoing calls
     */
    private fun scheduleOutgoingCallActivation(dialingMetadata: CallMetadata) {
        Log.d(TAG, "Scheduling outgoing call activation in 3 seconds")
        Log.d(TAG, "Dialing metadata: callId=${dialingMetadata.callId}, state=${dialingMetadata.state.name}")
        
        // Use a handler to schedule the transition
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            Log.d(TAG, "*** SCHEDULED TRANSITION CHECK ***")
            Log.d(TAG, "Current call state: ${currentCallState?.state?.name ?: "null"}")
            Log.d(TAG, "Current call ID: ${currentCallState?.callId}")
            Log.d(TAG, "Expected call ID: ${dialingMetadata.callId}")
            Log.d(TAG, "Call IDs match: ${currentCallState?.callId == dialingMetadata.callId}")
            Log.d(TAG, "Still in DIALING: ${currentCallState?.state == CallMetadata.CallState.DIALING}")
            
            // Only transition if we're still in the same call and still in DIALING state
            if (currentCallState?.callId == dialingMetadata.callId && 
                currentCallState?.state == CallMetadata.CallState.DIALING) {
                
                Log.d(TAG, "✓ Transitioning outgoing call from DIALING to ACTIVE")
                val activeMetadata = CallMetadata.createActiveCall(
                    phoneNumber = dialingMetadata.phoneNumber,
                    callerName = dialingMetadata.callerName,
                    callId = dialingMetadata.callId,
                    isIncoming = false
                )
                Log.d(TAG, "Active metadata created: state=${activeMetadata.state.name}, caller='${activeMetadata.callerName}'")
                updateCallState(activeMetadata)
            } else {
                Log.w(TAG, "✗ Skipping scheduled outgoing call activation (call state changed)")
                Log.d(TAG, "Reason: callId match=${currentCallState?.callId == dialingMetadata.callId}, still dialing=${currentCallState?.state == CallMetadata.CallState.DIALING}")
            }
        }, 5000) // 5 seconds delay as backup (in case no second OFFHOOK event)
    }
}