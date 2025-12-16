package com.example.ble.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import android.util.Log
import com.example.ble.models.PhoneCallInfo
import com.example.ble.models.PhoneCallState

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
            
            val callState = when (state) {
                TelephonyManager.EXTRA_STATE_IDLE -> PhoneCallState.IDLE
                TelephonyManager.EXTRA_STATE_RINGING -> PhoneCallState.RINGING
                TelephonyManager.EXTRA_STATE_OFFHOOK -> PhoneCallState.OFFHOOK
                else -> PhoneCallState.IDLE
            }
            
            val phoneCallInfo = PhoneCallInfo(
                state = callState,
                phoneNumber = phoneNumber
            )
            
            phoneStateListener?.invoke(phoneCallInfo)
            Log.d(TAG, "Phone state changed: $callState, number: $phoneNumber")
        }
    }
}