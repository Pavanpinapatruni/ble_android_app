package com.example.ble.models

import android.bluetooth.BluetoothDevice

data class BleDevice(
    val device: BluetoothDevice,
    val name: String,
    val address: String,
    val rssi: Int,
    val isConnected: Boolean = false,
    val isPaired: Boolean = false,
    val lastSeen: Long = System.currentTimeMillis()
) {
    companion object {
        fun fromBluetoothDevice(
            device: BluetoothDevice,
            rssi: Int = 0,
            isConnected: Boolean = false,
            isPaired: Boolean = false
        ): BleDevice {
            return BleDevice(
                device = device,
                name = device.name ?: "Unknown Device",
                address = device.address,
                rssi = rssi,
                isConnected = isConnected,
                isPaired = isPaired
            )
        }
    }
}