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
import com.example.ble.models.PhoneCallInfo
import com.example.ble.models.PhoneCallState
import com.example.ble.ble.BleManager
import com.example.ble.receivers.PhoneStateReceiver
import java.util.concurrent.Executor
import java.util.concurrent.Executors

private const val TAG = "CallListenerService"

/**
 * Service to monitor phone calls and notify BLE clients via TBS
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
         * Test method to manually trigger call state for debugging
         */
        fun testCallNotification(testState: String) {
            val instance = instance ?: return
            
            Log.d(TAG, "*** MANUAL CALL TEST TRIGGERED: $testState ***")
            
            val testMetadata = when (testState.uppercase()) {
                "INCOMING" -> CallMetadata.createIncomingCall(
                    phoneNumber = "+1234567890",
                    callerName = "Test Caller",
                    callId = "test_call_123"
                )
                "ACTIVE" -> CallMetadata.createActiveCall(
                    phoneNumber = "+1234567890", 
                    callerName = "Test Caller",
                    callId = "test_call_123"
                )
                "IDLE" -> CallMetadata.createIdleState()
                else -> CallMetadata.createIdleState()
            }
            
            instance.updateCallState(testMetadata)
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
        
        // Also listen to PhoneStateReceiver events as backup
        setupPhoneStateReceiverListener()
        
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
        
        // Remove PhoneStateReceiver listener
        PhoneStateReceiver.removePhoneStateListener()
        
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
                CallMetadata.createIncomingCall(
                    phoneNumber = phoneNumber ?: lastPhoneNumber,
                    callerName = getContactName(phoneNumber ?: lastPhoneNumber),
                    callId = generateCallId()
                )
            }
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                Log.d(TAG, "Call off hook (active or dialing)")
                // Determine if this is an incoming call being answered or outgoing call
                val isIncoming = currentCallState?.state == CallMetadata.CallState.INCOMING
                
                if (isIncoming) {
                    // Incoming call was answered
                    CallMetadata.createActiveCall(
                        phoneNumber = currentCallState?.phoneNumber ?: phoneNumber ?: lastPhoneNumber,
                        callerName = currentCallState?.callerName ?: getContactName(phoneNumber ?: lastPhoneNumber),
                        callId = currentCallState?.callId ?: generateCallId(),
                        isIncoming = true
                    )
                } else {
                    // Outgoing call
                    CallMetadata.createActiveCall(
                        phoneNumber = phoneNumber ?: lastPhoneNumber,
                        callerName = getContactName(phoneNumber ?: lastPhoneNumber),
                        callId = generateCallId(),
                        isIncoming = false
                    )
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
        
        Log.d(TAG, "*** CALL STATE UPDATED *** ")
        Log.d(TAG, "State: ${metadata.state.name}")
        Log.d(TAG, "Caller: ${metadata.getDisplayName()}")
        Log.d(TAG, "Phone: ${metadata.phoneNumber ?: "Unknown"}")
        Log.d(TAG, "Is Incoming: ${metadata.isIncoming}")
        
        // Notify BLE clients immediately
        bleManager?.let { manager ->
            try {
                Log.d(TAG, "*** SENDING CALL NOTIFICATION TO BLE ***")
                manager.updateCallMetadata(metadata)
                Log.d(TAG, "Call notification sent successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Error sending call notification to BLE", e)
            }
        } ?: Log.e(TAG, "BLE Manager is null - cannot send call notifications!")
    }
    
    private fun getContactName(phoneNumber: String?): String? {
        // TODO: Implement contact lookup from phone's contact database
        // For now, return the phone number or a default name
        return phoneNumber?.let { "Contact: $it" }
    }
    
    private fun generateCallId(): String {
        return "call_${System.currentTimeMillis()}"
    }
    
    /**
     * Setup listener for PhoneStateReceiver events (backup call monitoring)
     */
    private fun setupPhoneStateReceiverListener() {
        Log.d(TAG, "Setting up PhoneStateReceiver bridge for BLE notifications")
        
        PhoneStateReceiver.setPhoneStateListener { phoneCallInfo ->
            Log.d(TAG, "*** RECEIVED CALL FROM PHONESTATECEIVER ***")
            Log.d(TAG, "State: ${phoneCallInfo.state}")
            Log.d(TAG, "Number: ${phoneCallInfo.phoneNumber}")
            
            // Convert PhoneCallInfo to CallMetadata
            val callMetadata = convertPhoneCallInfoToCallMetadata(phoneCallInfo)
            
            // Update our state and send to BLE
            updateCallState(callMetadata)
        }
    }
    
    /**
     * Convert PhoneCallInfo to CallMetadata for BLE transmission
     */
    private fun convertPhoneCallInfoToCallMetadata(phoneCallInfo: PhoneCallInfo): CallMetadata {
        val callState = when (phoneCallInfo.state) {
            PhoneCallState.IDLE -> CallMetadata.CallState.IDLE
            PhoneCallState.RINGING -> CallMetadata.CallState.INCOMING
            PhoneCallState.OFFHOOK -> {
                // Determine if this was an incoming call being answered or outgoing
                val wasIncoming = currentCallState?.state == CallMetadata.CallState.INCOMING
                if (wasIncoming) {
                    CallMetadata.CallState.ACTIVE
                } else {
                    CallMetadata.CallState.ACTIVE // Could also be DIALING initially
                }
            }
        }
        
        val callerName = if (phoneCallInfo.phoneNumber != null) {
            getContactName(phoneCallInfo.phoneNumber) ?: phoneCallInfo.phoneNumber
        } else {
            "Unknown Caller"
        }
        
        val terminationReason = if (callState == CallMetadata.CallState.IDLE && currentCallState?.isCallActive() == true) {
            CallMetadata.TerminationReason.UNKNOWN
        } else {
            null
        }
        
        return CallMetadata(
            phoneNumber = phoneCallInfo.phoneNumber,
            callerName = callerName,
            state = callState,
            callId = currentCallState?.callId ?: generateCallId(),
            timestamp = phoneCallInfo.timestamp,
            terminationReason = terminationReason,
            isIncoming = callState == CallMetadata.CallState.INCOMING
        )
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
}