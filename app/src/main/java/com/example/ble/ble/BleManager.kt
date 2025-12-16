package com.example.ble.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.ble.models.BleDevice
import com.example.ble.models.MediaMetadata
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.*

private const val TAG = "BleManager"

@SuppressLint("MissingPermission")
class BleManager(private val context: Context) {

    // BLE Components
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter = bluetoothManager.adapter
    private val scanner = bluetoothAdapter.bluetoothLeScanner
    
    // GATT Server and Client
    private var gattServer: BluetoothGattServer? = null
    private var gattClient: BluetoothGatt? = null
    
    // Connected devices
    private val connectedDevices = mutableSetOf<BluetoothDevice>()
    private val subscribedDevices = mutableSetOf<BluetoothDevice>()
    
    // Service UUIDs
    companion object {
        val MEDIA_SERVICE_UUID = UUID.fromString("0000abcd-0000-1000-8000-00805f9b34fb")
        val TITLE_UUID = UUID.fromString("00001111-0000-1000-8000-00805f9b34fb")
        val ARTIST_UUID = UUID.fromString("00002222-0000-1000-8000-00805f9b34fb")
        val STATE_UUID = UUID.fromString("00003333-0000-1000-8000-00805f9b34fb")
        val CALL_STATE_UUID = UUID.fromString("00004444-0000-1000-8000-00805f9b34fb")
    }

    // State flows for UI
    private val _scannedDevices = MutableStateFlow<List<BleDevice>>(emptyList())
    val scannedDevices: StateFlow<List<BleDevice>> = _scannedDevices.asStateFlow()
    
    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()
    
    private val _connectionState = MutableStateFlow<String>("Disconnected")
    val connectionState: StateFlow<String> = _connectionState.asStateFlow()

    private val foundDevices = mutableMapOf<String, BleDevice>()

    // Scan callback
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!hasBlePermissions()) return
            
            val device = result.device
            val rssi = result.rssi
            
            val bleDevice = BleDevice.fromBluetoothDevice(
                device = device,
                rssi = rssi,
                isConnected = connectedDevices.contains(device),
                isPaired = device.bondState == BluetoothDevice.BOND_BONDED
            )
            
            foundDevices[device.address] = bleDevice
            _scannedDevices.value = foundDevices.values.toList().sortedByDescending { it.rssi }
            
            Log.d(TAG, "Found device: ${bleDevice.name} (${bleDevice.address}) RSSI: ${bleDevice.rssi}")
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed with error: $errorCode")
            _isScanning.value = false
        }
    }

    // GATT client callback
    private val gattClientCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (!hasBlePermissions()) return
            
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d(TAG, "Connected to GATT server")
                    connectedDevices.add(gatt.device)
                    _connectionState.value = "Connected to ${gatt.device.name ?: "Unknown"}"
                    
                    // Start bonding process
                    if (gatt.device.bondState != BluetoothDevice.BOND_BONDED) {
                        Log.d(TAG, "Starting bonding process...")
                        gatt.device.createBond()
                    }
                    
                    // Start GATT server
                    startGattServer()
                    
                    // Discover services
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "Disconnected from GATT server")
                    connectedDevices.remove(gatt.device)
                    _connectionState.value = "Disconnected"
                    gatt.close()
                }
            }
            updateDeviceConnectionStates()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Services discovered successfully")
                // Handle discovered services if needed
            }
        }
    }

    // GATT server callback
    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d(TAG, "Device ${device.address} connected to GATT server")
                    subscribedDevices.add(device)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "Device ${device.address} disconnected from GATT server")
                    subscribedDevices.remove(device)
                }
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            if (!hasBlePermissions()) return
            
            if (responseNeeded) {
                gattServer?.sendResponse(
                    device,
                    requestId,
                    BluetoothGatt.GATT_SUCCESS,
                    0,
                    null
                )
            }
            Log.d(TAG, "Client ${device.address} subscribed to notifications")
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (!hasBlePermissions()) return
            
            gattServer?.sendResponse(
                device,
                requestId,
                BluetoothGatt.GATT_SUCCESS,
                0,
                characteristic.value
            )
        }
    }

    fun startScan() {
        if (!hasBlePermissions()) {
            Log.e(TAG, "Missing BLE permissions")
            return
        }

        if (!bluetoothAdapter.isEnabled) {
            Log.e(TAG, "Bluetooth is not enabled")
            return
        }

        if (_isScanning.value) {
            Log.d(TAG, "Already scanning")
            return
        }

        foundDevices.clear()
        _scannedDevices.value = emptyList()
        _isScanning.value = true

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
            .setReportDelay(0)
            .build()

        scanner.startScan(emptyList(), settings, scanCallback)
        Log.d(TAG, "Started BLE scan")
    }

    fun stopScan() {
        if (!_isScanning.value) return
        
        if (hasBlePermissions()) {
            scanner.stopScan(scanCallback)
        }
        _isScanning.value = false
        Log.d(TAG, "Stopped BLE scan")
    }

    fun connectToDevice(bleDevice: BleDevice) {
        if (!hasBlePermissions()) {
            Log.e(TAG, "Missing BLE permissions")
            return
        }

        Log.d(TAG, "Connecting to device: ${bleDevice.name} (${bleDevice.address})")
        _connectionState.value = "Connecting..."
        
        gattClient?.close()
        gattClient = bleDevice.device.connectGatt(
            context,
            false,
            gattClientCallback,
            BluetoothDevice.TRANSPORT_LE
        )
    }

    fun disconnectFromDevice() {
        if (!hasBlePermissions()) return
        
        gattClient?.disconnect()
        gattClient?.close()
        gattClient = null
        
        gattServer?.close()
        gattServer = null
        
        connectedDevices.clear()
        subscribedDevices.clear()
        _connectionState.value = "Disconnected"
        updateDeviceConnectionStates()
    }

    private fun startGattServer() {
        if (gattServer != null) return
        if (!hasBlePermissions()) return

        Log.d(TAG, "Starting GATT server")
        gattServer = bluetoothManager.openGattServer(context, gattServerCallback)

        val service = BluetoothGattService(
            MEDIA_SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )

        // Create characteristics for media metadata and call state
        service.addCharacteristic(createNotifyCharacteristic(TITLE_UUID))
        service.addCharacteristic(createNotifyCharacteristic(ARTIST_UUID))
        service.addCharacteristic(createNotifyCharacteristic(STATE_UUID))
        service.addCharacteristic(createNotifyCharacteristic(CALL_STATE_UUID))

        gattServer?.addService(service)
    }

    private fun createNotifyCharacteristic(uuid: UUID): BluetoothGattCharacteristic {
        val characteristic = BluetoothGattCharacteristic(
            uuid,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        )

        val descriptor = BluetoothGattDescriptor(
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"),
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )
        characteristic.addDescriptor(descriptor)

        return characteristic
    }

    fun updateMediaMetadata(metadata: MediaMetadata) {
        if (!hasBlePermissions()) return
        
        metadata.title?.let { notifyCharacteristic(TITLE_UUID, it) }
        metadata.artist?.let { notifyCharacteristic(ARTIST_UUID, it) }
        notifyCharacteristic(STATE_UUID, if (metadata.isPlaying) "PLAYING" else "PAUSED")
        
        Log.d(TAG, "Updated media metadata: ${metadata.title} by ${metadata.artist}")
    }

    fun updateCallState(callState: String) {
        if (!hasBlePermissions()) return
        
        notifyCharacteristic(CALL_STATE_UUID, callState)
        Log.d(TAG, "Updated call state: $callState")
    }

    private fun notifyCharacteristic(uuid: UUID, value: String) {
        if (!hasBlePermissions()) return
        
        val service = gattServer?.getService(MEDIA_SERVICE_UUID) ?: return
        val characteristic = service.getCharacteristic(uuid) ?: return

        characteristic.value = value.toByteArray(Charsets.UTF_8)

        subscribedDevices.forEach { device ->
            try {
                gattServer?.notifyCharacteristicChanged(device, characteristic, false)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to notify device ${device.address}", e)
            }
        }
    }

    private fun updateDeviceConnectionStates() {
        val updatedDevices = foundDevices.values.map { bleDevice ->
            bleDevice.copy(
                isConnected = connectedDevices.any { it.address == bleDevice.address },
                isPaired = bleDevice.device.bondState == BluetoothDevice.BOND_BONDED
            )
        }
        _scannedDevices.value = updatedDevices.sortedByDescending { it.rssi }
    }

    fun hasBlePermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun isBluetoothEnabled(): Boolean = bluetoothAdapter.isEnabled

    fun cleanup() {
        stopScan()
        disconnectFromDevice()
    }
}