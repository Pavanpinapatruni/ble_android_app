package com.example.ble.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.ble.models.BleDevice
import com.example.ble.models.MediaMetadata
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.util.*

private const val TAG = "BleManager"

@SuppressLint("MissingPermission")
class BleManager(private val context: Context) {

    // BLE Components
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter = bluetoothManager.adapter
    private val scanner = bluetoothAdapter.bluetoothLeScanner
    
    // Handler for delayed operations
    private val handler = Handler(Looper.getMainLooper())
    
    // GATT Server and Client
    private var gattServer: BluetoothGattServer? = null
    private var gattClient: BluetoothGatt? = null
    
    // Connected devices
    private val connectedDevices = mutableSetOf<BluetoothDevice>()
    private val subscribedDevices = mutableSetOf<BluetoothDevice>()
    
    // Track characteristic subscriptions per device
    private val deviceSubscriptions = mutableMapOf<BluetoothDevice, MutableSet<UUID>>()
    
    // Track change detection
    private var currentMediaMetadata: MediaMetadata? = null
    private var trackChangeCounter = 0
    
    // Track last sent values for change detection
    private val lastSentValues = mutableMapOf<UUID, String>()
    private val recentlyConnectedDevices = mutableSetOf<BluetoothDevice>()
    
    // Service UUIDs - Media Control Service (MCS) Bluetooth SIG Standard
    companion object {
        // MCS Service UUID (0x1849)
        val MEDIA_SERVICE_UUID = UUID.fromString("00001849-0000-1000-8000-00805f9b34fb")
        
        // MCS Characteristics - Official Bluetooth SIG UUIDs
        val MP_NAME_UUID = UUID.fromString("00002b93-0000-1000-8000-00805f9b34fb")          // Media Player Name (Source ID)
        val TRACK_CHANGED_UUID = UUID.fromString("00002b96-0000-1000-8000-00805f9b34fb")    // Track Changed
        val TITLE_UUID = UUID.fromString("00002b97-0000-1000-8000-00805f9b34fb")            // Track Title
        val DURATION_UUID = UUID.fromString("00002b98-0000-1000-8000-00805f9b34fb")         // Track Duration
        val POSITION_UUID = UUID.fromString("00002b99-0000-1000-8000-00805f9b34fb")         // Track Position
        val STATE_UUID = UUID.fromString("00002ba3-0000-1000-8000-00805f9b34fb")            // Media State
        val MCP_UUID = UUID.fromString("00002ba4-0000-1000-8000-00805f9b34fb")              // Media Control Point
        val MCP_OPCODE_SUPPORTED_UUID = UUID.fromString("00002ba5-0000-1000-8000-00805f9b34fb") // MCP Opcodes Supported
    }

    // State flows for UI
    private val _scannedDevices = MutableStateFlow<List<BleDevice>>(emptyList())
    val scannedDevices: StateFlow<List<BleDevice>> = _scannedDevices.asStateFlow()
    
    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()
    
    private val _connectionState = MutableStateFlow<String>("Disconnected")
    val connectionState: StateFlow<String> = _connectionState.asStateFlow()
    
    // Scan filter state
    private val _scanFilter = MutableStateFlow<String>("")
    val scanFilter: StateFlow<String> = _scanFilter.asStateFlow()

    private val foundDevices = mutableMapOf<String, BleDevice>()

    // Scan callback
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!hasBlePermissions()) return
            
            val device = result.device
            val rssi = result.rssi
            val deviceName = device.name ?: "Unknown Device"
            
            // Apply name filter if set
            val currentFilter = _scanFilter.value
            if (currentFilter.isNotEmpty() && !deviceName.lowercase().contains(currentFilter.lowercase())) {
                return // Skip this device as it doesn't match the filter
            }
            
            val bleDevice = BleDevice.fromBluetoothDevice(
                device = device,
                rssi = rssi,
                isConnected = connectedDevices.contains(device),
                isPaired = device.bondState == BluetoothDevice.BOND_BONDED
            )
            
            foundDevices[device.address] = bleDevice
            
            // Apply filter to the entire list before updating UI
            val filteredDevices = if (currentFilter.isNotEmpty()) {
                foundDevices.values.filter { 
                    it.name.lowercase().contains(currentFilter.lowercase()) 
                }
            } else {
                foundDevices.values.toList()
            }
            
            _scannedDevices.value = filteredDevices.sortedByDescending { it.rssi }
            
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
                    
                    // Discover services
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "Disconnected from GATT server")
                    connectedDevices.remove(gatt.device)
                    _connectionState.value = "Disconnected"
                    gatt.close()
                    shutdownGattServer()
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
                    Log.d(TAG, "🔗 TI CHIP CONNECTED TO GATT SERVER:")
                    Log.d(TAG, "   Device: ${device.address} (${device.name ?: "Unknown"})")
                    Log.d(TAG, "   Connection status: $status")
                    Log.d(TAG, "   New state: $newState")
                    
                    subscribedDevices.add(device)
                    deviceSubscriptions[device] = mutableSetOf()
                    connectedDevices.add(device)
                    recentlyConnectedDevices.add(device)
                    
                    Log.d(TAG, "✅ TI chip ready for subscriptions")
                    Log.d(TAG, "   Total connected GATT server devices: ${subscribedDevices.size}")
                    Log.d(TAG, "🆕 Device marked for initial notifications")
                    
                    // Check if we have any services available
                    gattServer?.services?.let { services ->
                        Log.d(TAG, "📋 GATT Server Services Available:")
                        Log.d(TAG, "   Number of services: ${services.size}")
                        services.forEach { service ->
                            val serviceName = if (service.uuid == MEDIA_SERVICE_UUID) "MCS (Media Control Service)" else service.uuid.toString()
                            Log.d(TAG, "   🎵 Service: $serviceName")
                            Log.d(TAG, "      Characteristics: ${service.characteristics.size}")
                            service.characteristics.forEach { char ->
                                val charName = when (char.uuid) {
                                    MP_NAME_UUID -> "MP_NAME (Media Player Name)"
                                    TRACK_CHANGED_UUID -> "TRACK_CHANGED"
                                    TITLE_UUID -> "TITLE"
                                    DURATION_UUID -> "DURATION"
                                    POSITION_UUID -> "POSITION"
                                    STATE_UUID -> "STATE"
                                    MCP_UUID -> "MCP (Media Control Point)"
                                    MCP_OPCODE_SUPPORTED_UUID -> "MCP_OPCODE_SUPPORTED"
                                    else -> char.uuid.toString()
                                }
                                Log.d(TAG, "         📡 $charName")
                            }
                        }
                    }
                    Log.d(TAG, "🔔 TI chip should now discover service and subscribe to MP_NAME first...")
                    
                    // Try to trigger service discovery by sending a characteristic notification
                    gattServer?.services?.let { services ->
                        val mpNameChar = services.firstOrNull()?.getCharacteristic(MP_NAME_UUID)
                        if (mpNameChar != null) {
                            Log.d(TAG, "🚨 Triggering MP_NAME notification to prompt TI chip discovery...")
                            try {
                                gattServer?.notifyCharacteristicChanged(device, mpNameChar, false)
                                Log.d(TAG, "✅ Sent MP_NAME change notification")
                            } catch (e: Exception) {
                                Log.w(TAG, "⚠️ Could not send notification (expected - not subscribed yet): ${e.message}")
                            }
                        }
                    }
                    
                    // Schedule a delayed service interaction to give TI chip time to settle
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        Log.d(TAG, "⏰ DELAYED SERVICE INTERACTION - Checking if TI chip is ready...")
                        if (connectedDevices.contains(device)) {
                            Log.d(TAG, "🔍 TI chip still connected - checking for any requests...")
                            
                            // Log current service status
                            gattServer?.services?.forEach { service ->
                                service.characteristics.forEach { char ->
                                    Log.d(TAG, "📊 Char ${char.uuid}: value size ${char.value?.size ?: 0}")
                                }
                            }
                            
                            // Try to update a characteristic to trigger activity
                            gattServer?.services?.firstOrNull()?.getCharacteristic(TRACK_CHANGED_UUID)?.let { trackChangedChar ->
                                trackChangedChar.value = byteArrayOf(0x01) // Change to indicate new track
                                Log.d(TAG, "🔄 Updated TRACK_CHANGED to trigger TI chip interest")
                            }
                        } else {
                            Log.d(TAG, "❌ TI chip disconnected during delay")
                        }
                    }, 2000) // 2 second delay
                    
                    // Log comprehensive status for debugging
                    logGattServerStatus()
                    
                    // Start active monitoring of TI chip behavior
                    startTiChipMonitoring(device)
                    
                    // Send initial notifications with current media state
                    sendInitialNotifications(device)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "🔌 TI CHIP DISCONNECTED FROM GATT SERVER:")
                    Log.d(TAG, "   Device: ${device.address}")
                    Log.d(TAG, "   Disconnection status: $status")
                    
                    subscribedDevices.remove(device)
                    deviceSubscriptions.remove(device)
                    connectedDevices.remove(device)
                    recentlyConnectedDevices.remove(device)
                    Log.d(TAG, "   Remaining connected GATT server devices: ${subscribedDevices.size}")
                }
                else -> {
                    Log.d(TAG, "🔄 TI Chip ${device.address} state change: status=$status, newState=$newState")
                }
            }
        }
        
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "🎉 MCS SERVICE SUCCESSFULLY ADDED TO GATT SERVER!")
                Log.d(TAG, "   Service UUID: ${service.uuid}")
                Log.d(TAG, "   Service Type: ${if (service.type == BluetoothGattService.SERVICE_TYPE_PRIMARY) "PRIMARY" else "SECONDARY"}")
                Log.d(TAG, "   Characteristics count: ${service.characteristics.size}")
                
                // Verify all our characteristics are present
                val expectedChars = listOf(
                    MP_NAME_UUID to "MP_NAME",
                    TRACK_CHANGED_UUID to "TRACK_CHANGED", 
                    TITLE_UUID to "TITLE",
                    DURATION_UUID to "DURATION",
                    POSITION_UUID to "POSITION",
                    STATE_UUID to "STATE",
                    MCP_UUID to "MCP",
                    MCP_OPCODE_SUPPORTED_UUID to "MCP_OPCODE_SUPPORTED"
                )
                
                expectedChars.forEach { (uuid, name) ->
                    val char = service.getCharacteristic(uuid)
                    if (char != null) {
                        // Initialize characteristics with proper default values
                        when (uuid) {
                            MP_NAME_UUID -> char.value = "MediaPlayer".toByteArray(Charsets.UTF_8) // String
                            TITLE_UUID -> char.value = "No Media".toByteArray(Charsets.UTF_8)
                            STATE_UUID -> char.value = byteArrayOf(0) // Numeric: 0=Inactive
                            TRACK_CHANGED_UUID -> char.value = byteArrayOf(0x00)
                            DURATION_UUID -> char.value = ByteArray(4) { 0 }
                            POSITION_UUID -> char.value = ByteArray(4) { 0 }
                            MCP_OPCODE_SUPPORTED_UUID -> char.value = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05)
                        }
                        Log.d(TAG, "   ✅ $name characteristic ready with value: ${char.value?.contentToString() ?: "null"}")
                    } else {
                        Log.e(TAG, "   ❌ MISSING $name characteristic!")
                    }
                }
                
                Log.d(TAG, "🚀 GATT Server is now ready for TI chip connections!")
                
                // Log comprehensive status for debugging
                logGattServerStatus()
            } else {
                Log.e(TAG, "❌ FAILED to add MCS service! Status: $status")
                Log.e(TAG, "   Service UUID: ${service.uuid}")
            }
        }
        
        override fun onDescriptorReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            descriptor: BluetoothGattDescriptor
        ) {
            Log.d(TAG, "🔍 TI CHIP DESCRIPTOR READ REQUEST:")
            Log.d(TAG, "   Device: ${device.address}")
            Log.d(TAG, "   Descriptor: ${descriptor.uuid}")
            Log.d(TAG, "   Characteristic: ${descriptor.characteristic.uuid}")
            Log.d(TAG, "   Request ID: $requestId")
            Log.d(TAG, "   Offset: $offset")
            Log.d(TAG, "   Value: ${descriptor.value?.contentToString() ?: "null"}")
            
            gattServer?.sendResponse(
                device,
                requestId,
                BluetoothGatt.GATT_SUCCESS,
                offset,
                descriptor.value
            )
            
            Log.d(TAG, "✅ Sent descriptor read response to TI chip")
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
            descriptor.value = value

            if (responseNeeded) {
                // Standard GATT Success response
                gattServer?.sendResponse(
                    device,
                    requestId,
                    BluetoothGatt.GATT_SUCCESS,
                    offset,
                    value
                )
                Log.d(TAG, "📤 Sent GATT_SUCCESS response")
            }

            Log.d(TAG, "✅ Client ${device.address} subscribed to notifications")
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (!hasBlePermissions()) return
            
            val charName = when (characteristic.uuid) {
                MP_NAME_UUID -> "MP_NAME (Media Player Name)"
                TRACK_CHANGED_UUID -> "TRACK_CHANGED"
                TITLE_UUID -> "TITLE"
                DURATION_UUID -> "DURATION"
                POSITION_UUID -> "POSITION"
                STATE_UUID -> "STATE"
                MCP_UUID -> "MCP (Media Control Point)"
                MCP_OPCODE_SUPPORTED_UUID -> "MCP_OPCODE_SUPPORTED"
                else -> characteristic.uuid.toString()
            }
            
            Log.d(TAG, "🔍 TI CHIP READ REQUEST:")
            Log.d(TAG, "   Device: ${device.address}")
            Log.d(TAG, "   Characteristic: $charName")
            Log.d(TAG, "   Request ID: $requestId")
            Log.d(TAG, "   Offset: $offset")
            Log.d(TAG, "   Value size: ${characteristic.value?.size ?: 0} bytes")
            Log.d(TAG, "   Value: ${characteristic.value?.contentToString() ?: "null"}")
            
            gattServer?.sendResponse(
                device,
                requestId,
                BluetoothGatt.GATT_SUCCESS,
                offset,
                characteristic.value
            )
            
            Log.d(TAG, "✅ Sent read response to TI chip")
        }
        
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            Log.d(TAG, "🔥 *** TI CHIP WRITE REQUEST RECEIVED! ***")
            
            val charName = when (characteristic.uuid) {
                MP_NAME_UUID -> "MP_NAME (Media Player Name)"
                TRACK_CHANGED_UUID -> "TRACK_CHANGED"
                TITLE_UUID -> "TITLE"
                DURATION_UUID -> "DURATION"
                POSITION_UUID -> "POSITION"
                STATE_UUID -> "STATE"
                MCP_UUID -> "MCP (Media Control Point)"
                MCP_OPCODE_SUPPORTED_UUID -> "MCP_OPCODE_SUPPORTED"
                else -> characteristic.uuid.toString()
            }
            
            Log.d(TAG, "   Device: ${device.address}")
            Log.d(TAG, "   Characteristic: $charName")
            Log.d(TAG, "   Request ID: $requestId")
            Log.d(TAG, "   Prepared Write: $preparedWrite")
            Log.d(TAG, "   Response Needed: $responseNeeded")
            Log.d(TAG, "   Offset: $offset")
            Log.d(TAG, "   Value size: ${value.size} bytes")
            Log.d(TAG, "   Value: ${value.contentToString()}")
            Log.d(TAG, "   Value (hex): ${value.joinToString(" ") { "%02x".format(it) }}")
            
            if (responseNeeded) {
                // Update the characteristic value
                characteristic.value = value
                
                // Send success response immediately
                val success = gattServer?.sendResponse(
                    device,
                    requestId,
                    BluetoothGatt.GATT_SUCCESS,
                    offset,
                    value
                )
                
                Log.d(TAG, "✅ Sent write response to TI chip: $success")
                
                // Process the write based on characteristic
                when (characteristic.uuid) {
                    MCP_UUID -> {
                        Log.d(TAG, "🎮 MEDIA CONTROL POINT COMMAND RECEIVED!")
                        if (value.isNotEmpty()) {
                            val command = value[0]
                            Log.d(TAG, "   Command opcode: 0x${"%02x".format(command)}")
                            when (command.toInt()) {
                                0x01 -> Log.d(TAG, "   ▶️ PLAY command")
                                0x02 -> Log.d(TAG, "   ⏸️ PAUSE command") 
                                0x03 -> Log.d(TAG, "   ⏹️ STOP command")
                                0x04 -> Log.d(TAG, "   ⏭️ NEXT TRACK command")
                                0x05 -> Log.d(TAG, "   ⏮️ PREVIOUS TRACK command")
                                else -> Log.d(TAG, "   ❓ Unknown command: $command")
                            }
                        }
                    }
                }
            } else {
                Log.d(TAG, "📝 Write request with no response needed")
            }
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

        Log.d(TAG, "🔗 Preparing to connect to device: ${bleDevice.name} (${bleDevice.address})")
        _connectionState.value = "Connecting..."
        
        // CRITICAL: Start GATT server FIRST, before any connection
        Log.d(TAG, "🚀 Pre-starting GATT server to ensure services are ready...")
        startGattServer()
        
        // Small delay to ensure GATT server setup completes
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            Log.d(TAG, "🔌 Now establishing GATT client connection...")
            gattClient?.close()
            gattClient = bleDevice.device.connectGatt(
                context,
                false,
                gattClientCallback,
                BluetoothDevice.TRANSPORT_LE
            )
        }, 100) // 100ms delay to ensure GATT server is ready
    }
    private fun shutdownGattServer() {
        Log.d(TAG, "🛑 Shutting down GATT server cleanly")
        gattServer?.clearServices()
        gattServer?.close()
        gattServer = null

        // 2️⃣ Mandatory cooldown
        handler.postDelayed({
            startGattServer()
        }, 800) // 🔴 REQUIRED DELAY
    }

    fun disconnectFromDevice() {
        if (!hasBlePermissions()) return
        
        gattClient?.disconnect()
        gattClient?.close()
        gattClient = null
        
        shutdownGattServer()
        
        connectedDevices.clear()
        subscribedDevices.clear()
        _connectionState.value = "Disconnected"
        updateDeviceConnectionStates()
    }

    private fun startGattServer() {
        if (gattServer != null) {
            Log.d(TAG, "GATT server already running")
            return
        }
        if (!hasBlePermissions()) {
            Log.e(TAG, "Missing BLE permissions for GATT server")
            return
        }

        Log.d(TAG, "🚀 Starting GATT server BEFORE any connections...")
        gattServer = bluetoothManager.openGattServer(context, gattServerCallback)
        
        if (gattServer == null) {
            Log.e(TAG, "❌ Failed to create GATT server")
            return
        }

        // Add required Generic Access Service first (mandatory for MCS compliance)
        addGenericAccessService()
        
        // Add the main Media Control Service
        addMediaControlService()
        
        Log.d(TAG, "✅ All services queued for addition")
    }
    
    private fun addGenericAccessService() {
        val gasService = BluetoothGattService(
            UUID.fromString("00001800-0000-1000-8000-00805f9b34fb"), // Generic Access Service
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )
        
        // Device Name characteristic (mandatory)
        val deviceNameChar = BluetoothGattCharacteristic(
            UUID.fromString("00002a00-0000-1000-8000-00805f9b34fb"), // Device Name
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        deviceNameChar.value = "MCS Media Server".toByteArray(Charsets.UTF_8)
        gasService.addCharacteristic(deviceNameChar)
        
        // Appearance characteristic (optional but recommended)
        val appearanceChar = BluetoothGattCharacteristic(
            UUID.fromString("00002a01-0000-1000-8000-00805f9b34fb"), // Appearance
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        appearanceChar.value = byteArrayOf(0x00, 0x00) // Generic category
        gasService.addCharacteristic(appearanceChar)
        
        Log.d(TAG, "📝 Adding Generic Access Service...")
        gattServer?.addService(gasService)
    }
    
    private fun addMediaControlService() {
        val service = BluetoothGattService(
            MEDIA_SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )

        // Create characteristics for Media Control Service (MCS) Standard with default values
        val mpNameChar = createNotifyCharacteristic(MP_NAME_UUID)
        mpNameChar.value = "MediaPlayer".toByteArray(Charsets.UTF_8) // String format
        service.addCharacteristic(mpNameChar)

        val trackChangedChar = createNotifyCharacteristic(TRACK_CHANGED_UUID)
        trackChangedChar.value = byteArrayOf(0x00)
        service.addCharacteristic(trackChangedChar)
        
        val titleChar = createNotifyCharacteristic(TITLE_UUID)
        titleChar.value = "No Media".toByteArray(Charsets.UTF_8)
        service.addCharacteristic(titleChar)
        
        val durationChar = createNotifyCharacteristic(DURATION_UUID)
        durationChar.value = ByteArray(4) { 0 }
        service.addCharacteristic(durationChar)
        
        val positionChar = createNotifyCharacteristic(POSITION_UUID)
        positionChar.value = ByteArray(4) { 0 }
        service.addCharacteristic(positionChar)
        
        val stateChar = createNotifyCharacteristic(STATE_UUID)
        stateChar.value = byteArrayOf(0x00) // Media State: 0x00 = Inactive, 0x01 = Playing, 0x02 = Paused
        service.addCharacteristic(stateChar)
        
        val mcpChar = createWriteCharacteristic(MCP_UUID)
        service.addCharacteristic(mcpChar)
        
        val mcpOpcodeSupportedChar = createNotifyCharacteristic(MCP_OPCODE_SUPPORTED_UUID)
        mcpOpcodeSupportedChar.value = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05)
        service.addCharacteristic(mcpOpcodeSupportedChar)

        Log.d(TAG, "📝 Adding MCS service to GATT server...")
        val serviceAdded = gattServer?.addService(service) ?: false
        Log.d(TAG, "🎯 GATT service addition result: $serviceAdded")
        
        if (!serviceAdded) {
            Log.e(TAG, "❌ CRITICAL: Failed to add MCS service to GATT server!")
            return
        }
        
        Log.d(TAG, "✅ MCS service successfully queued for addition")
        Log.d(TAG, "⏳ Waiting for onServiceAdded callback to confirm...")
    }

    private fun createNotifyCharacteristic(uuid: UUID): BluetoothGattCharacteristic {
        val characteristic = BluetoothGattCharacteristic(
            uuid,
            BluetoothGattCharacteristic.PROPERTY_READ or 
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        )

        // PERMISSION FIX: Allow Write, Encrypted Write, and Authenticated Write
        val descriptor = BluetoothGattDescriptor(
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"),
            BluetoothGattDescriptor.PERMISSION_READ or
                    BluetoothGattDescriptor.PERMISSION_WRITE
        )

        descriptor.value = BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
        characteristic.addDescriptor(descriptor)

        return characteristic
    }
    
    private fun createWriteCharacteristic(uuid: UUID): BluetoothGattCharacteristic {
        val characteristic = BluetoothGattCharacteristic(
            uuid,
            BluetoothGattCharacteristic.PROPERTY_WRITE or 
            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        
        // Add Client Characteristic Configuration Descriptor (CCCD) for notifications
        val descriptor = BluetoothGattDescriptor(
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"), // CCCD UUID
            BluetoothGattDescriptor.PERMISSION_READ or 
            BluetoothGattDescriptor.PERMISSION_WRITE
        )
        
        // Initialize CCCD descriptor with notifications disabled (standard default)
        descriptor.value = BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
        characteristic.addDescriptor(descriptor)

        return characteristic
    }

    fun updateMediaMetadata(metadata: MediaMetadata) {
        if (!hasBlePermissions()) return
        
        // Store current metadata for new connections
        currentMediaMetadata = metadata
        
        // Increment track change counter when track title changes
        val lastTitle = lastSentValues[TITLE_UUID]
        if (metadata.title != null && lastTitle != metadata.title) {
            trackChangeCounter++
            Log.d(TAG, "🎵 Track changed! Counter: $trackChangeCounter")
        }
        
        // MCS Standard Characteristics
        metadata.title?.let { 
            Log.d(TAG, "🎵 Sending TITLE: '$it'")
            notifyCharacteristic(TITLE_UUID, it) 
        }
        
        // Send track changed notification
        notifyCharacteristicBytes(TRACK_CHANGED_UUID, byteArrayOf(trackChangeCounter.toByte()))
        
        // Add duration and position if available
        metadata.duration?.let { duration ->
            val durationBytes = ByteBuffer.allocate(4).putInt(duration.toInt()).array()
            notifyCharacteristicBytes(DURATION_UUID, durationBytes)
        }
        
        metadata.position?.let { position ->
            val positionBytes = ByteBuffer.allocate(4).putInt(position.toInt()).array()
            notifyCharacteristicBytes(POSITION_UUID, positionBytes)
        }
        
        // Use MP_NAME for source identification (send as string)
        metadata.packageName?.let { packageName ->
            val appName = when {
                packageName.contains("spotify", ignoreCase = true) -> "Spotify"
                packageName.contains("youtube", ignoreCase = true) -> "YouTube Music"
                packageName.contains("music", ignoreCase = true) -> when {
                    packageName.contains("google") -> "YouTube Music"
                    packageName.contains("apple") -> "Apple Music"
                    else -> "Music"
                }
                packageName.contains("soundcloud", ignoreCase = true) -> "SoundCloud"
                packageName.contains("pandora", ignoreCase = true) -> "Pandora"
                packageName.contains("deezer", ignoreCase = true) -> "Deezer"
                else -> packageName.substringAfterLast('.').replaceFirstChar { it.uppercase() }
            }
            Log.d(TAG, "🎵 Sending MP_NAME (String): '$appName' for $packageName")
            notifyCharacteristic(MP_NAME_UUID, appName)
        }
        
        // MCS Media State (using proper byte values)
        val mcsStateCode = when {
            metadata.isPlaying -> 1 // Playing
            metadata.title != null -> 2 // Paused 
            else -> 0 // Inactive
        }
        val mcsStateName = when (mcsStateCode) {
            1 -> "Playing"
            2 -> "Paused"
            else -> "Inactive"
        }
        Log.d(TAG, "🎵 Sending STATE: $mcsStateName (code: $mcsStateCode, byte: ${mcsStateCode.toByte()})")
        notifyCharacteristicBytes(STATE_UUID, byteArrayOf(mcsStateCode.toByte()))
        
        // Clear recently connected devices after sending initial notifications
        if (recentlyConnectedDevices.isNotEmpty()) {
            Log.d(TAG, "🧹 Clearing recently connected devices list (${recentlyConnectedDevices.size} devices)")
            recentlyConnectedDevices.clear()
        }
        
        Log.d(TAG, "Updated MCS metadata: ${metadata.title} from ${metadata.packageName} - $mcsStateName")
    }

    private fun notifyCharacteristic(uuid: UUID, value: String) {
        val service = gattServer?.getService(MEDIA_SERVICE_UUID) ?: return
        val characteristic = service.getCharacteristic(uuid) ?: return

        // Check if value actually changed
        val lastValue = lastSentValues[uuid]
        val hasChanged = lastValue != value
        
        // Always send to newly connected devices, even if value hasn't changed
        val shouldSendToNewDevices = recentlyConnectedDevices.isNotEmpty()
        val shouldSendToAllDevices = hasChanged
        
        if (!shouldSendToAllDevices && !shouldSendToNewDevices) {
            Log.d(TAG, "📋 No change for $uuid: '$value' and no new devices (skipping notification)")
            return
        }

        // Update the characteristic value
        characteristic.value = value.toByteArray(Charsets.UTF_8)

        // Send notifications to devices
        if (shouldSendToAllDevices) {
            // Send to all connected devices on value change
            Log.d(TAG, "🔄 Value changed for $uuid: '$lastValue' → '$value'")
            connectedDevices.forEach { device ->
                val ok = gattServer?.notifyCharacteristicChanged(
                    device,
                    characteristic,
                    false
                )
                Log.d(TAG, "📡 Notify $uuid to ${device.address} (change) → $ok")
            }
            // Update last sent value
            lastSentValues[uuid] = value
        } else if (shouldSendToNewDevices) {
            // Send only to recently connected devices (same value)
            Log.d(TAG, "🆕 Sending current value for $uuid to newly connected devices: '$value'")
            recentlyConnectedDevices.forEach { device ->
                val ok = gattServer?.notifyCharacteristicChanged(
                    device,
                    characteristic,
                    false
                )
                Log.d(TAG, "📡 Notify $uuid to ${device.address} (new device) → $ok")
            }
            // Update last sent value for new devices too
            lastSentValues[uuid] = value
        }
    }

    private fun notifyCharacteristicBytes(uuid: UUID, value: ByteArray) {
        val service = gattServer?.getService(MEDIA_SERVICE_UUID) ?: return
        val characteristic = service.getCharacteristic(uuid) ?: return

        // Debug logging for byte values
        val charName = when (uuid) {
            STATE_UUID -> "STATE"
            TRACK_CHANGED_UUID -> "TRACK_CHANGED"
            DURATION_UUID -> "DURATION"
            POSITION_UUID -> "POSITION"
            MP_NAME_UUID -> "MP_NAME"
            else -> uuid.toString()
        }
        
        Log.d(TAG, "🔢 notifyCharacteristicBytes for $charName:")
        Log.d(TAG, "   Raw bytes: ${value.contentToString()}")
        Log.d(TAG, "   Decimal values: ${value.joinToString(", ") { it.toUByte().toString() }}")
        Log.d(TAG, "   Hex values: ${value.joinToString(", ") { "0x%02X".format(it) }}")

        // Check if value actually changed (compare byte arrays)
        val lastValue = lastSentValues[uuid]
        val currentValueString = value.contentToString()
        val hasChanged = lastValue != currentValueString
        
        // Always send to newly connected devices, even if value hasn't changed
        val shouldSendToNewDevices = recentlyConnectedDevices.isNotEmpty()
        val shouldSendToAllDevices = hasChanged
        
        if (!shouldSendToAllDevices && !shouldSendToNewDevices) {
            Log.d(TAG, "📋 No change for $uuid: ${value.contentToString()} and no new devices (skipping notification)")
            return
        }

        // Update the characteristic value
        characteristic.value = value

        // Send notifications to devices
        if (shouldSendToAllDevices) {
            // Send to all connected devices on value change
            Log.d(TAG, "🔄 Value changed for $uuid: '$lastValue' → '${value.contentToString()}'")
            connectedDevices.forEach { device ->
                val ok = gattServer?.notifyCharacteristicChanged(
                    device,
                    characteristic,
                    false
                )
                Log.d(TAG, "📡 Notify $uuid to ${device.address} (change) → $ok")
            }
            // Update last sent value
            lastSentValues[uuid] = currentValueString
        } else if (shouldSendToNewDevices) {
            // Send only to recently connected devices (same value)
            Log.d(TAG, "🆕 Sending current value for $uuid to newly connected devices: ${value.contentToString()}")
            recentlyConnectedDevices.forEach { device ->
                val ok = gattServer?.notifyCharacteristicChanged(
                    device,
                    characteristic,
                    false
                )
                Log.d(TAG, "📡 Notify $uuid to ${device.address} (new device) → $ok")
            }
            // Update last sent value for new devices too
            lastSentValues[uuid] = currentValueString
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

    // Scan filter management
    fun setScanFilter(filter: String) {
        _scanFilter.value = filter
        // Apply filter to already discovered devices
        applyFilterToFoundDevices()
        Log.d(TAG, "Scan filter set to: '$filter'")
    }

    fun clearScanFilter() {
        _scanFilter.value = ""
        // Show all discovered devices
        applyFilterToFoundDevices()
        Log.d(TAG, "Scan filter cleared")
    }

    private fun applyFilterToFoundDevices() {
        val currentFilter = _scanFilter.value
        val filteredDevices = if (currentFilter.isNotEmpty()) {
            foundDevices.values.filter { 
                it.name.lowercase().contains(currentFilter.lowercase()) 
            }
        } else {
            foundDevices.values.toList()
        }
        _scannedDevices.value = filteredDevices.sortedByDescending { it.rssi }
    }

    fun cleanup() {
        stopScan()
        shutdownGattServer()
        disconnectFromDevice()
    }
    
    fun getConnectionStatus(): String {
        if (!hasBlePermissions()) return "Missing BLE permissions"
        
        val gattServerStatus = if (gattServer != null) "Running" else "Not started"
        val connectedCount = connectedDevices.size
        val serviceStatus = gattServer?.services?.size ?: 0
        
        return "GATT Server: $gattServerStatus | Services: $serviceStatus | Connected: $connectedCount"
    }
    
    // Add comprehensive status logging for TI chip debugging
    private fun logGattServerStatus() {
        if (gattServer == null) {
            Log.w(TAG, "📊 GATT Server Status: NOT RUNNING")
            return
        }
        
        Log.d(TAG, "📊 === GATT SERVER STATUS REPORT ===")
        Log.d(TAG, "📊 Services count: ${gattServer?.services?.size ?: 0}")
        
        gattServer?.services?.forEach { service ->
            Log.d(TAG, "📊 Service: ${service.uuid}")
            Log.d(TAG, "📊   Type: ${if (service.type == BluetoothGattService.SERVICE_TYPE_PRIMARY) "PRIMARY" else "SECONDARY"}")
            Log.d(TAG, "📊   Characteristics: ${service.characteristics.size}")
            
            service.characteristics.forEach { char ->
                val props = mutableListOf<String>()
                if (char.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) props.add("READ")
                if (char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) props.add("WRITE")
                if (char.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) props.add("NOTIFY")
                if (char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) props.add("WRITE_NO_RESP")
                
                Log.d(TAG, "📊     Char: ${char.uuid} | Props: ${props.joinToString(", ")} | Value: ${char.value?.size ?: 0} bytes")
                Log.d(TAG, "📊       Descriptors: ${char.descriptors.size}")
                
                char.descriptors.forEach { desc ->
                    Log.d(TAG, "📊         Desc: ${desc.uuid} | Value: ${desc.value?.contentToString() ?: "null"}")
                }
            }
        }
        
        Log.d(TAG, "📊 Connected devices: ${connectedDevices.size}")
        connectedDevices.forEach { device ->
            Log.d(TAG, "📊   Device: ${device.name ?: "Unknown"} (${device.address}) | Bond: ${device.bondState}")
        }
        Log.d(TAG, "📊 === END STATUS REPORT ===")
    }
    
    // Send initial notifications to newly connected devices
    private fun sendInitialNotifications(device: BluetoothDevice) {
        Log.d(TAG, "🚀 SENDING INITIAL NOTIFICATIONS to ${device.address}")
        
        // Ensure this device is marked as recently connected
        if (!recentlyConnectedDevices.contains(device)) {
            recentlyConnectedDevices.add(device)
            Log.d(TAG, "📝 Added ${device.address} to recently connected devices")
        }
        
        // Use current metadata if available, otherwise send default values  
        currentMediaMetadata?.let { metadata ->
            Log.d(TAG, "📱 Using current media metadata for initial notifications")
            
            // Force send all current values to new device
            metadata.packageName?.let { packageName ->
                val appName = when {
                    packageName.contains("spotify", ignoreCase = true) -> "Spotify"
                    packageName.contains("youtube", ignoreCase = true) -> "YouTube Music"
                    packageName.contains("music", ignoreCase = true) -> when {
                        packageName.contains("google") -> "YouTube Music"
                        packageName.contains("apple") -> "Apple Music"
                        else -> "Music"
                    }
                    packageName.contains("soundcloud", ignoreCase = true) -> "SoundCloud"
                    packageName.contains("pandora", ignoreCase = true) -> "Pandora"
                    packageName.contains("deezer", ignoreCase = true) -> "Deezer"
                    else -> packageName.substringAfterLast('.').replaceFirstChar { it.uppercase() }
                }
                Log.d(TAG, "🆕 Force sending MP_NAME: '$appName' to new device")
                notifyCharacteristic(MP_NAME_UUID, appName)
            }
            
            metadata.title?.let { 
                Log.d(TAG, "🆕 Force sending TITLE: '$it' to new device")
                notifyCharacteristic(TITLE_UUID, it)
            }
            
            val mcsStateCode = when {
                metadata.isPlaying -> 1 
                metadata.title != null -> 2 
                else -> 0 
            }
            val mcsStateName = when (mcsStateCode) {
                1 -> "Playing"
                2 -> "Paused"
                else -> "Inactive"
            }
            Log.d(TAG, "🆕 Force sending STATE: $mcsStateName to new device")
            notifyCharacteristicBytes(STATE_UUID, byteArrayOf(mcsStateCode.toByte()))
            
        } ?: run {
            Log.d(TAG, "📱 Sending default initial values")
            // Send default values for initial connection
            notifyCharacteristic(MP_NAME_UUID, "MediaPlayer") 
            notifyCharacteristic(TITLE_UUID, "No Media")
            notifyCharacteristicBytes(STATE_UUID, byteArrayOf(0)) 
        }
        
        // Remove this specific device from recently connected after notifications
        recentlyConnectedDevices.remove(device)
        Log.d(TAG, "🧹 Removed ${device.address} from recently connected devices after initial notifications")
    }
    
    // Add active monitoring for TI chip behavior
    private fun startTiChipMonitoring(device: BluetoothDevice) {
        var checkCount = 0
        val maxChecks = 10 // Check for 20 seconds total
        
        val monitor = object : Runnable {
            override fun run() {
                checkCount++
                Log.d(TAG, "🔍 TI CHIP MONITORING CHECK #$checkCount")
                
                if (!connectedDevices.contains(device)) {
                    Log.d(TAG, "❌ TI chip disconnected, stopping monitoring")
                    return
                }
                
                Log.d(TAG, "📊 Current subscription status:")
                deviceSubscriptions[device]?.let { subscriptions ->
                    if (subscriptions.isEmpty()) {
                        Log.w(TAG, "⚠️ TI chip has NOT subscribed to any characteristics yet")
                        
                        // Try to make service more attractive by updating values
                        gattServer?.services?.firstOrNull()?.let { service ->
                            service.getCharacteristic(MP_NAME_UUID)?.let { char ->
                                val newValue = "MediaPlayer_${checkCount}".toByteArray(Charsets.UTF_8)
                                char.value = newValue
                                Log.d(TAG, "🔄 Updated MP_NAME to: ${String(newValue, Charsets.UTF_8)}")
                            }
                            
                            // Also update other characteristics to make them more "active"
                            service.getCharacteristic(TITLE_UUID)?.let { char ->
                                val newValue = "Track $checkCount".toByteArray(Charsets.UTF_8)
                                char.value = newValue
                                Log.d(TAG, "🔄 Updated TITLE to: ${String(newValue, Charsets.UTF_8)}")
                            }
                            
                            service.getCharacteristic(STATE_UUID)?.let { char ->
                                val stateValues = byteArrayOf(0x00, 0x01, 0x02) // Inactive, Playing, Paused
                                val stateNames = listOf("Inactive", "Playing", "Paused")
                                val stateIndex = checkCount % 3
                                char.value = byteArrayOf(stateValues[stateIndex])
                                Log.d(TAG, "🔄 Updated STATE to: ${stateNames[stateIndex]} (0x${"%02x".format(stateValues[stateIndex])})")
                            }
                            
                            service.getCharacteristic(TRACK_CHANGED_UUID)?.let { char ->
                                char.value = byteArrayOf(checkCount.toByte())
                                Log.d(TAG, "🔄 Updated TRACK_CHANGED to: $checkCount")
                            }
                        }
                    } else {
                        Log.d(TAG, "✅ TI chip has ${subscriptions.size} active subscriptions")
                        subscriptions.forEach { uuid ->
                            Log.d(TAG, "   📡 Subscribed to: $uuid")
                        }
                    }
                } ?: Log.w(TAG, "❓ No subscription record found for TI chip")
                
                // Continue monitoring if no subscriptions yet and within limit
                if (checkCount < maxChecks && deviceSubscriptions[device]?.isEmpty() != false) {
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(this, 2000)
                } else {
                    Log.d(TAG, "🏁 TI chip monitoring complete (checks: $checkCount)")
                }
            }
        }
        
        // Start monitoring after initial connection settling
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(monitor, 3000)
    }
}