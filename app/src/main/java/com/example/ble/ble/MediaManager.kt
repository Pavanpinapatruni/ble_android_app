package com.example.ble.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.util.Log
import com.example.ble.models.MediaMetadata
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.*

private const val TAG = "MediaManager"

/**
 * MediaManager handles all Media Control Service (MCS) operations for BLE communication
 * Implements Bluetooth SIG Media Control Service specification
 */
@SuppressLint("MissingPermission")
class MediaManager(private val bleManager: BleManagerInterface) {
    
    // Track current media state
    private var currentMediaMetadata: MediaMetadata? = null
    private var trackChangeCounter = 0
    private var lastSentPositionSeconds = -1L
    
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
    
    /**
     * Interface for BleManager to provide necessary GATT operations
     */
    interface BleManagerInterface {
        fun getGattServer(): BluetoothGattServer?
        fun getConnectedDevices(): Set<BluetoothDevice>
        fun hasBlePermissions(): Boolean
        fun notifyCharacteristicChanged(device: BluetoothDevice, characteristic: BluetoothGattCharacteristic): Boolean
    }
    
    /**
     * Creates the MCS GATT service with all required characteristics
     */
    fun createMediaControlService(): BluetoothGattService {
        Log.d(TAG, "🎵 Creating Media Control Service (MCS)")
        
        val service = BluetoothGattService(MEDIA_SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        
        // Create all MCS characteristics
        val mpNameChar = createNotifyCharacteristic(MP_NAME_UUID)
        mpNameChar.value = "MediaPlayer".toByteArray(Charsets.UTF_8)
        service.addCharacteristic(mpNameChar)
        
        val trackChangedChar = createNotifyCharacteristic(TRACK_CHANGED_UUID)
        trackChangedChar.value = ByteArray(1) { 0 }
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
        stateChar.value = ByteArray(1) { 0 }
        service.addCharacteristic(stateChar)
        
        // Media Control Point - Write & Notify
        val mcpChar = BluetoothGattCharacteristic(
            MCP_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        val mcpDescriptor = BluetoothGattDescriptor(
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"),
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )
        mcpDescriptor.value = BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
        mcpChar.addDescriptor(mcpDescriptor)
        mcpChar.value = ByteArray(1) { 0 }
        service.addCharacteristic(mcpChar)
        
        val mcpOpcodeSupportedChar = createNotifyCharacteristic(MCP_OPCODE_SUPPORTED_UUID)
        val supportedOpcodes = ByteBuffer.allocate(4).putInt(0x1F).array() // Bits 0-4 set (Play, Pause, Fast Rewind, Fast Forward, Stop)
        mcpOpcodeSupportedChar.value = supportedOpcodes
        service.addCharacteristic(mcpOpcodeSupportedChar)
        
        Log.d(TAG, "✅ Media Control Service created with ${service.characteristics.size} characteristics")
        return service
    }
    
    /**
     * Creates a characteristic with notify property and CCCD descriptor
     */
    private fun createNotifyCharacteristic(uuid: UUID): BluetoothGattCharacteristic {
        val characteristic = BluetoothGattCharacteristic(
            uuid,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        
        // Add Client Characteristic Configuration Descriptor (CCCD) for notifications
        val descriptor = BluetoothGattDescriptor(
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"), // CCCD UUID
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )
        
        // Initialize CCCD descriptor with notifications disabled (standard default)
        descriptor.value = BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
        characteristic.addDescriptor(descriptor)
        
        return characteristic
    }
    
    /**
     * Updates media metadata and sends notifications to connected devices
     */
    fun updateMediaMetadata(metadata: MediaMetadata) {
        if (!bleManager.hasBlePermissions()) return
        
        // Check if this is a new track (reset position tracking)
        if (currentMediaMetadata?.title != metadata.title) {
            Log.d(TAG, "🆕 New track detected in MediaManager, resetting position tracking")
            lastSentPositionSeconds = -1L
        }
        
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
            // Encode duration according to MCS spec: 4 bytes representing centiseconds (0.01s resolution)
            val durationCentiseconds = (duration / 10).toInt() // Convert ms to centiseconds
            val durationBytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(durationCentiseconds).array()
            notifyCharacteristicBytes(DURATION_UUID, durationBytes)
        }
        
        metadata.position?.let { position ->
            val positionSeconds = position / 1000L
            
            // Temporarily send all position updates for debugging
            Log.d(TAG, "🔍 DEBUG: Position=${position}ms (${positionSeconds}s), LastSent=${lastSentPositionSeconds}s")
            
            // Encode position according to MCS spec: 4 bytes representing centiseconds (0.01s resolution)
            val positionCentiseconds = (position / 10).toInt() // Convert ms to centiseconds
            val positionBytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(positionCentiseconds).array()
            
            Log.d(TAG, "🕐 Sending POSITION: ${position}ms (${positionSeconds}s) = ${positionCentiseconds} centiseconds to BLE devices")
            notifyCharacteristicBytes(POSITION_UUID, positionBytes)
            lastSentPositionSeconds = positionSeconds
        }
        
        // Use MP_NAME for source identification (send as string)
        metadata.packageName?.let { packageName ->
            val appName = getAppNameFromPackage(packageName)
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
    
    /**
     * Maps package name to user-friendly app name
     */
    private fun getAppNameFromPackage(packageName: String): String {
        return when {
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
    }
    
    /**
     * Sends string notification to characteristic
     */
    private fun notifyCharacteristic(uuid: UUID, value: String) {
        val gattServer = bleManager.getGattServer() ?: return
        val service = gattServer.getService(MEDIA_SERVICE_UUID) ?: return
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
        val connectedDevices = bleManager.getConnectedDevices()
        if (shouldSendToAllDevices) {
            // Send to all connected devices on value change
            Log.d(TAG, "🔄 Value changed for $uuid: '$lastValue' → '$value'")
            connectedDevices.forEach { device ->
                val ok = bleManager.notifyCharacteristicChanged(device, characteristic)
                Log.d(TAG, "📡 Notify $uuid to ${device.address} (change) → $ok")
            }
            // Update last sent value
            lastSentValues[uuid] = value
        } else if (shouldSendToNewDevices) {
            // Send only to recently connected devices (same value)
            Log.d(TAG, "🆕 Sending current value for $uuid to newly connected devices: '$value'")
            recentlyConnectedDevices.forEach { device ->
                val ok = bleManager.notifyCharacteristicChanged(device, characteristic)
                Log.d(TAG, "📡 Notify $uuid to ${device.address} (new device) → $ok")
            }
        }
    }
    
    /**
     * Sends byte array notification to characteristic
     */
    private fun notifyCharacteristicBytes(uuid: UUID, value: ByteArray) {
        val gattServer = bleManager.getGattServer() ?: return
        val service = gattServer.getService(MEDIA_SERVICE_UUID) ?: return
        val characteristic = service.getCharacteristic(uuid) ?: return

        // Check if value actually changed (for byte arrays)
        val lastValue = characteristic.value
        val hasChanged = !lastValue.contentEquals(value)
        
        // Always send to newly connected devices
        val shouldSendToNewDevices = recentlyConnectedDevices.isNotEmpty()
        val shouldSendToAllDevices = hasChanged
        
        if (!shouldSendToAllDevices && !shouldSendToNewDevices) {
            Log.d(TAG, "📋 No change for $uuid: ${value.contentToString()} and no new devices (skipping notification)")
            return
        }

        // Log the byte details for debugging
        Log.d(TAG, "🔢 notifyCharacteristicBytes for ${getCharacteristicName(uuid)}:")
        Log.d(TAG, "    Raw bytes: ${value.contentToString()}")
        Log.d(TAG, "    Decimal values: ${value.joinToString(", ") { (it.toInt() and 0xFF).toString() }}")
        Log.d(TAG, "    Hex values: ${value.joinToString(", ") { "0x%02X".format(it.toInt() and 0xFF) }}")

        // Update the characteristic value
        characteristic.value = value

        // Send notifications to devices
        val connectedDevices = bleManager.getConnectedDevices()
        if (shouldSendToAllDevices) {
            // Send to all connected devices on value change
            Log.d(TAG, "🔄 Value changed for $uuid: '${lastValue?.contentToString() ?: "null"}' → '${value.contentToString()}'")
            connectedDevices.forEach { device ->
                val ok = bleManager.notifyCharacteristicChanged(device, characteristic)
                Log.d(TAG, "📡 Notify $uuid to ${device.address} (change) → $ok")
            }
        } else if (shouldSendToNewDevices) {
            // Send only to recently connected devices
            Log.d(TAG, "🆕 Sending current value for $uuid to newly connected devices: ${value.contentToString()}")
            recentlyConnectedDevices.forEach { device ->
                val ok = bleManager.notifyCharacteristicChanged(device, characteristic)
                Log.d(TAG, "📡 Notify $uuid to ${device.address} (new device) → $ok")
            }
        }
    }
    
    /**
     * Gets human-readable characteristic name for logging
     */
    private fun getCharacteristicName(uuid: UUID): String {
        return when (uuid) {
            MP_NAME_UUID -> "MP_NAME"
            TRACK_CHANGED_UUID -> "TRACK_CHANGED"
            TITLE_UUID -> "TITLE"
            DURATION_UUID -> "DURATION"
            POSITION_UUID -> "POSITION"
            STATE_UUID -> "STATE"
            MCP_UUID -> "MCP"
            MCP_OPCODE_SUPPORTED_UUID -> "MCP_OPCODE_SUPPORTED"
            else -> uuid.toString()
        }
    }
    
    /**
     * Handles Media Control Point commands
     */
    fun handleMediaControlCommand(value: ByteArray): Int {
        if (value.isEmpty()) return 0
        
        val rawCommand = value[0].toInt() and 0xFF
        Log.d(TAG, "📱 MCP Command received: 0x${"%02x".format(rawCommand)}")
        
        // Map TI chip commands to standard MCS commands
        val command = mapTiChipCommand(rawCommand)
        
        if (command != rawCommand) {
            Log.d(TAG, "🔄 TI Chip mapping: 0x${"%02x".format(rawCommand)} → 0x${"%02x".format(command)}")
        }
        
        return command
    }
    
    /**
     * Maps TI-specific chip commands to standard MCS opcodes
     */
    private fun mapTiChipCommand(rawCommand: Int): Int {
        return when (rawCommand) {
            0x30 -> 0x05  // TI Previous Track → MCS Fast Rewind
            0x31 -> 0x04  // TI Next Track → MCS Fast Forward  
            else -> rawCommand  // Use command as-is for standard opcodes
        }
    }
    
    /**
     * Adds a recently connected device to track for initial notifications
     */
    fun addRecentlyConnectedDevice(device: BluetoothDevice) {
        recentlyConnectedDevices.add(device)
        Log.d(TAG, "📱 Added recently connected device: ${device.address}")
    }
    
    /**
     * Sends initial media state to newly connected device
     */
    fun sendInitialMediaState(device: BluetoothDevice) {
        currentMediaMetadata?.let { metadata ->
            Log.d(TAG, "📤 Sending initial media state to ${device.address}")
            updateMediaMetadata(metadata)
        }
    }
    
    /**
     * Gets current media metadata
     */
    fun getCurrentMediaMetadata(): MediaMetadata? = currentMediaMetadata
}