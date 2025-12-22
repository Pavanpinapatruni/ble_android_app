package com.example.ble.viewmodels

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.ble.ble.BleManager
import com.example.ble.models.BleDevice
import com.example.ble.models.MediaMetadata
import com.example.ble.models.PhoneCallInfo
import com.example.ble.receivers.PhoneStateReceiver
import com.example.ble.services.MediaListenerService
import com.example.ble.services.CallListenerService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val bleManager = BleManager(application)

    // BLE related state
    val scannedDevices: StateFlow<List<BleDevice>> = bleManager.scannedDevices
    val isScanning: StateFlow<Boolean> = bleManager.isScanning
    val connectionState: StateFlow<String> = bleManager.connectionState
    val scanFilter: StateFlow<String> = bleManager.scanFilter

    // Media metadata state
    private val _currentMedia = MutableStateFlow(MediaMetadata())
    val currentMedia: StateFlow<MediaMetadata> = _currentMedia.asStateFlow()

    // Phone call state
    private val _currentCall = MutableStateFlow(PhoneCallInfo())
    val currentCall: StateFlow<PhoneCallInfo> = _currentCall.asStateFlow()

    // Permission states
    private val _hasNotificationAccess = MutableStateFlow(false)
    val hasNotificationAccess: StateFlow<Boolean> = _hasNotificationAccess.asStateFlow()

    init {
        MediaListenerService.setBleManager(bleManager)
        setupMediaListener()
        setupPhoneStateListener()
        startCallListenerService()
    }

    private fun setupMediaListener() {
        MediaListenerService.setMediaUpdateListener { metadata ->
            viewModelScope.launch {
                _currentMedia.value = metadata
                bleManager.updateMediaMetadata(metadata)
            }
        }
    }

    private fun setupPhoneStateListener() {
        PhoneStateReceiver.setPhoneStateListener { callInfo ->
            viewModelScope.launch {
                _currentCall.value = callInfo
                // Call state removed from MCS - only for UI display now
                Log.d("MainViewModel", "Call state changed: ${callInfo.state}")
            }
        }
    }

    private fun startCallListenerService() {
        Log.d("MainViewModel", "Starting CallListenerService for TBS monitoring...")
        CallListenerService.startService(getApplication(), bleManager)
    }

    // BLE functions
    fun startBLEScan() {
        viewModelScope.launch {
            bleManager.startScan()
        }
    }

    fun stopBLEScan() {
        viewModelScope.launch {
            bleManager.stopScan()
        }
    }

    fun connectToDevice(device: BleDevice) {
        viewModelScope.launch {
            bleManager.connectToDevice(device)
        }
    }

    fun disconnectFromDevice() {
        viewModelScope.launch {
            bleManager.disconnectFromDevice()
        }
    }

    fun hasBlePermissions(): Boolean = bleManager.hasBlePermissions()
    fun isBluetoothEnabled(): Boolean = bleManager.isBluetoothEnabled()

    // Scan filter methods
    fun setScanFilter(filter: String) {
        bleManager.setScanFilter(filter)
    }

    fun clearScanFilter() {
        bleManager.clearScanFilter()
    }

    fun setNotificationAccess(hasAccess: Boolean) {
        _hasNotificationAccess.value = hasAccess
    }

    override fun onCleared() {
        super.onCleared()
        bleManager.cleanup()
        MediaListenerService.removeMediaUpdateListener()
        PhoneStateReceiver.removePhoneStateListener()
        CallListenerService.stopService(getApplication())
    }
}