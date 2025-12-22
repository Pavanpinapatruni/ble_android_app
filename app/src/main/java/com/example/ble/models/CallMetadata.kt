package com.example.ble.models

/**
 * Data class representing call metadata for Telephone Bearer Service (TBS)
 */
data class CallMetadata(
    val phoneNumber: String? = null,
    val callerName: String? = null,
    val state: CallState = CallState.IDLE,
    val callId: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val terminationReason: TerminationReason? = null,
    val isIncoming: Boolean = false,
    val isOnSpeaker: Boolean = false,
    val isMuted: Boolean = false
) {
    
    /**
     * Call states according to TBS specification
     */
    enum class CallState(val value: Int) {
        IDLE(0x00),              // No active call
        INCOMING(0x01),          // Incoming call (ringing)
        DIALING(0x02),           // Outgoing call (dialing)
        ALERTING(0x03),          // Outgoing call (remote ringing)
        ACTIVE(0x04),            // Call is connected and active
        LOCALLY_HELD(0x05),      // Call held by local party
        REMOTELY_HELD(0x06),     // Call held by remote party
        LOCALLY_AND_REMOTELY_HELD(0x07) // Call held by both parties
    }
    
    /**
     * Termination reasons according to TBS specification
     */
    enum class TerminationReason(val value: Int) {
        UNKNOWN(0x00),
        LOCAL_PARTY(0x01),       // Ended by local user
        REMOTE_PARTY(0x02),      // Ended by remote party
        NETWORK(0x03),           // Network terminated
        BUSY(0x04),              // Remote party busy
        NO_ANSWER(0x05)          // No answer from remote party
    }
    
    companion object {
        /**
         * Creates a default idle state for when no call is active
         */
        fun createIdleState(): CallMetadata {
            return CallMetadata(
                state = CallState.IDLE,
                callerName = null  // No caller name for idle state
            )
        }
        
        /**
         * Creates metadata for an incoming call
         */
        fun createIncomingCall(
            phoneNumber: String? = null,
            callerName: String? = null,
            callId: String? = null
        ): CallMetadata {
            return CallMetadata(
                phoneNumber = phoneNumber,
                callerName = callerName ?: phoneNumber ?: "Unknown Caller",
                state = CallState.INCOMING,
                callId = callId,
                isIncoming = true
            )
        }
        
        /**
         * Creates metadata for an outgoing call
         */
        fun createOutgoingCall(
            phoneNumber: String,
            callerName: String? = null,
            callId: String? = null
        ): CallMetadata {
            return CallMetadata(
                phoneNumber = phoneNumber,
                callerName = callerName ?: phoneNumber,
                state = CallState.DIALING,
                callId = callId,
                isIncoming = false
            )
        }
        
        /**
         * Creates metadata for an active call
         */
        fun createActiveCall(
            phoneNumber: String? = null,
            callerName: String? = null,
            callId: String? = null,
            isIncoming: Boolean = false
        ): CallMetadata {
            return CallMetadata(
                phoneNumber = phoneNumber,
                callerName = callerName ?: phoneNumber ?: "Active Call",
                state = CallState.ACTIVE,
                callId = callId,
                isIncoming = isIncoming
            )
        }
        
        /**
         * Creates metadata for a terminated call
         */
        fun createTerminatedCall(
            phoneNumber: String? = null,
            callerName: String? = null,
            callId: String? = null,
            terminationReason: TerminationReason = TerminationReason.UNKNOWN
        ): CallMetadata {
            return CallMetadata(
                phoneNumber = phoneNumber,
                callerName = callerName,
                state = CallState.IDLE,
                callId = callId,
                terminationReason = terminationReason
            )
        }
    }
    
    /**
     * Gets a display-friendly name for the call
     */
    fun getDisplayName(): String {
        return callerName ?: phoneNumber ?: "Unknown"
    }
    
    /**
     * Checks if this call is currently active (not idle)
     */
    fun isCallActive(): Boolean {
        return state != CallState.IDLE
    }
    
    /**
     * Checks if this call is currently connected (active or held)
     */
    fun isCallConnected(): Boolean {
        return state in listOf(
            CallState.ACTIVE,
            CallState.LOCALLY_HELD,
            CallState.REMOTELY_HELD,
            CallState.LOCALLY_AND_REMOTELY_HELD
        )
    }
    
    /**
     * Checks if this call is ringing (incoming or alerting)
     */
    fun isCallRinging(): Boolean {
        return state in listOf(CallState.INCOMING, CallState.ALERTING)
    }
}