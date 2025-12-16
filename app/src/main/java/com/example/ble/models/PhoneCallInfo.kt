package com.example.ble.models

enum class PhoneCallState {
    IDLE,
    RINGING,
    OFFHOOK
}

data class PhoneCallInfo(
    val state: PhoneCallState = PhoneCallState.IDLE,
    val phoneNumber: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)