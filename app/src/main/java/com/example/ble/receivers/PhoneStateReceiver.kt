package com.example.ble.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import android.util.Log
import com.example.ble.models.PhoneCallInfo
import com.example.ble.models.PhoneCallState
import com.example.ble.services.CallListenerService

private const val TAG = "PhoneStateReceiver"

class PhoneStateReceiver : BroadcastReceiver() {
    
    companion object {
        private var phoneStateListener: ((PhoneCallInfo) -> Unit)? = null
        
        fun setPhoneStateListener(listener: (PhoneCallInfo) -> Unit) {
            phoneStateListener = listener
        }
        
        fun removePhoneStateListener() {
            phoneStateListener = null
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == TelephonyManager.ACTION_PHONE_STATE_CHANGED) {
            val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
            val phoneNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
            
            Log.d(TAG, "*** PHONE STATE RECEIVER ***")
            Log.d(TAG, "State: $state")
            Log.d(TAG, "Phone number raw: '$phoneNumber'")
            Log.d(TAG, "Phone number null: ${phoneNumber == null}")
            Log.d(TAG, "Phone number empty: ${phoneNumber?.isEmpty()}")
            
            val callState = when (state) {
                TelephonyManager.EXTRA_STATE_IDLE -> PhoneCallState.IDLE
                TelephonyManager.EXTRA_STATE_RINGING -> PhoneCallState.RINGING
                TelephonyManager.EXTRA_STATE_OFFHOOK -> PhoneCallState.OFFHOOK
                else -> PhoneCallState.IDLE
            }
            
            // Clean up phone number - only use real phone numbers, not placeholder text
            val cleanPhoneNumber = when {
                phoneNumber.isNullOrEmpty() -> {
                    Log.d(TAG, "Phone number is null/empty - system privacy restriction")
                    null  // Don't use placeholder text, let CallManager handle it
                }
                phoneNumber.equals("unknown", ignoreCase = true) -> {
                    Log.d(TAG, "Phone number is 'unknown' - private/blocked call")
                    null  // Don't use placeholder text, let CallManager handle it
                }
                else -> {
                    Log.d(TAG, "Using real phone number: '$phoneNumber'")
                    phoneNumber
                }
            }
            
            val phoneCallInfo = PhoneCallInfo(
                state = callState,
                phoneNumber = cleanPhoneNumber
            )
            
            phoneStateListener?.invoke(phoneCallInfo)
            Log.d(TAG, "Sent PhoneCallInfo: state=$callState, number='$cleanPhoneNumber'")
            
            // Bridge to CallListenerService for TBS notifications
            CallListenerService.updateCallStateFromReceiver(state ?: "IDLE", cleanPhoneNumber)
        }
    }
}