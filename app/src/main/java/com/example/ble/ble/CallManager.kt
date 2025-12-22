package com.example.ble.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.util.Log
import com.example.ble.models.CallMetadata
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.*

private const val TAG = "CallManager"

/**
 * CallManager handles all Telephone Bearer Service (TBS) operations for BLE communication
 * Implements Bluetooth SIG Telephone Bearer Service specification
 */
@SuppressLint("MissingPermission")
class CallManager(private val bleManager: MediaManager.BleManagerInterface) {
    
    // Track current call state
    private var currentCallMetadata: CallMetadata? = null
    private var lastSentValues = mutableMapOf<UUID, String>()
    private val recentlyConnectedDevices = mutableSetOf<BluetoothDevice>()
    
    // Service UUIDs - Telephone Bearer Service (TBS) Bluetooth SIG Standard
    companion object {
        // TBS Service UUID (0x184C)
        val TBS_SERVICE_UUID = UUID.fromString("0000184C-0000-1000-8000-00805f9b34fb")
        
        // TBS Characteristics - Official Bluetooth SIG UUIDs
        val CALL_STATE_UUID = UUID.fromString("00002BBD-0000-1000-8000-00805f9b34fb")           // Call State
        val CALL_CONTROL_POINT_UUID = UUID.fromString("00002BBE-0000-1000-8000-00805f9b34fb")   // Call Control Point
        val CALL_FRIENDLY_NAME_UUID = UUID.fromString("00002BC2-0000-1000-8000-00805f9b34fb")   // Call Friendly Name
        val TERMINATION_REASON_UUID = UUID.fromString("00002BC0-0000-1000-8000-00805f9b34fb")   // Termination Reason
    }
    
    /**
     * Creates the TBS GATT service with all required characteristics
     */
    fun createTelephoneBearerService(): BluetoothGattService {
        Log.d(TAG, "Creating Telephone Bearer Service (TBS)")
        
        val service = BluetoothGattService(TBS_SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        
        // Create all TBS characteristics
        val callStateChar = createNotifyCharacteristic(CALL_STATE_UUID)
        callStateChar.value = byteArrayOf(0x00, 0x00, 0x00) // No active calls (3-byte structure)
        service.addCharacteristic(callStateChar)
        
        // Call Control Point - Write & Notify
        val callControlChar = BluetoothGattCharacteristic(
            CALL_CONTROL_POINT_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        val ccpDescriptor = BluetoothGattDescriptor(
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"),
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )
        ccpDescriptor.value = BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
        callControlChar.addDescriptor(ccpDescriptor)
        callControlChar.value = ByteArray(1) { 0 }
        service.addCharacteristic(callControlChar)
        
        val callFriendlyNameChar = createNotifyCharacteristic(CALL_FRIENDLY_NAME_UUID)
        callFriendlyNameChar.value = "No Active Call".toByteArray(Charsets.UTF_8)
        service.addCharacteristic(callFriendlyNameChar)
        
        val terminationReasonChar = createNotifyCharacteristic(TERMINATION_REASON_UUID)
        terminationReasonChar.value = byteArrayOf(0x00) // No termination
        service.addCharacteristic(terminationReasonChar)
        
        Log.d(TAG, "Telephone Bearer Service created with ${service.characteristics.size} characteristics")
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
     * Updates call metadata and sends notifications to connected devices
     */
    fun updateCallMetadata(metadata: CallMetadata) {
        Log.d(TAG, "=== UPDATE CALL METADATA ===")
        Log.d(TAG, "New metadata: state=${metadata.state.name}, caller='${metadata.callerName}', number='${metadata.phoneNumber}'")
        Log.d(TAG, "Current metadata: ${currentCallMetadata?.let { "state=${it.state.name}, caller='${it.callerName}'" } ?: "null"}")
        
        if (!bleManager.hasBlePermissions()) {
            Log.w(TAG, "No BLE permissions - skipping call metadata update")
            return
        }
        
        // Store current metadata for new connections
        currentCallMetadata = metadata
        
        // Handle call termination first (before sending state)
        if (metadata.state == CallMetadata.CallState.IDLE && metadata.terminationReason != null) {
            val terminationValue = when (metadata.terminationReason) {
                CallMetadata.TerminationReason.LOCAL_PARTY -> 0x01
                CallMetadata.TerminationReason.REMOTE_PARTY -> 0x02
                CallMetadata.TerminationReason.NETWORK -> 0x03
                CallMetadata.TerminationReason.BUSY -> 0x04
                CallMetadata.TerminationReason.NO_ANSWER -> 0x05
                CallMetadata.TerminationReason.UNKNOWN -> 0x00
            }
            // Termination Reason characteristic: [Call Index, Termination Reason]
            val callIndex = 0x01 // Use same call index as other states
            val terminationBytes = byteArrayOf(callIndex.toByte(), terminationValue.toByte())
            Log.d(TAG, "Sending TERMINATION_REASON: ${metadata.terminationReason.name} (index: 0x${"%%02x".format(callIndex)}, reason: 0x${"%%02x".format(terminationValue)})")
            notifyCharacteristicBytes(TERMINATION_REASON_UUID, terminationBytes)
        }

        // Send call state based on current call status - TBS specification values
        val callStateValue = when (metadata.state) {
            CallMetadata.CallState.INCOMING -> 0x00
            CallMetadata.CallState.DIALING -> 0x01
            CallMetadata.CallState.ALERTING -> 0x02
            CallMetadata.CallState.ACTIVE -> 0x03
            CallMetadata.CallState.LOCALLY_HELD -> 0x04
            CallMetadata.CallState.REMOTELY_HELD -> 0x05
            CallMetadata.CallState.LOCALLY_AND_REMOTELY_HELD -> 0x06
            CallMetadata.CallState.IDLE -> {
                // For IDLE state, don't send call state notification
                // But DO send the friendly name from termination metadata before clearing
                
                // Send the caller name from termination metadata (if available)
                metadata.callerName?.let { name ->
                    Log.d(TAG, "Sending final CALL_FRIENDLY_NAME during termination: '$name'")
                    notifyCharacteristic(CALL_FRIENDLY_NAME_UUID, name)
                } ?: metadata.phoneNumber?.let { number ->
                    Log.d(TAG, "Sending final CALL_FRIENDLY_NAME during termination: '$number'")
                    notifyCharacteristic(CALL_FRIENDLY_NAME_UUID, number)
                } ?: run {
                    // Only clear if no caller info available
                    Log.d(TAG, "No caller info available - clearing CALL_FRIENDLY_NAME")
                    notifyCharacteristic(CALL_FRIENDLY_NAME_UUID, "")
                }
                
                // Clear recently connected devices after sending termination
                if (recentlyConnectedDevices.isNotEmpty()) {
                    Log.d(TAG, "Clearing recently connected devices list (${recentlyConnectedDevices.size} devices)")
                    recentlyConnectedDevices.clear()
                }
                
                Log.d(TAG, "Updated TBS metadata: Call ended - IDLE")
                return // Don't send call state notification for IDLE
            }
        }
        
        // Create complete TBS Call State structure (3 bytes):
        // Byte 0: Call Index (0x01 for first call)
        // Byte 1: Call State value
        // Byte 2: Call Flags (0x00 for basic calls)
        val callIndex = 0x01
        val callFlags = 0x00
        val callStateBytes = byteArrayOf(
            callIndex.toByte(),
            callStateValue.toByte(),
            callFlags.toByte()
        )
        
        Log.d(TAG, "Sending CALL_STATE: ${metadata.state.name} (index: 0x${"%02x".format(callIndex)}, state: 0x${"%02x".format(callStateValue)}, flags: 0x${"%02x".format(callFlags)})")
        
        // Force send call state for new calls (different call IDs) even if state appears same
        val isNewCall = currentCallMetadata?.callId != metadata.callId
        if (isNewCall) {
            Log.d(TAG, "New call detected (ID: ${metadata.callId} vs ${currentCallMetadata?.callId}) - forcing call state notification")
            val gattServer = bleManager.getGattServer()
            val service = gattServer?.getService(TBS_SERVICE_UUID)
            val characteristic = service?.getCharacteristic(CALL_STATE_UUID)
            
            if (characteristic != null) {
                characteristic.value = callStateBytes
                bleManager.getConnectedDevices().forEach { device ->
                    val ok = bleManager.notifyCharacteristicChanged(device, characteristic)
                    Log.d(TAG, "Force notify CALL_STATE to ${device.address} (new call) → $ok")
                }
            }
        } else {
            // Normal call state notification
            notifyCharacteristicBytes(CALL_STATE_UUID, callStateBytes)
        }
        
        // Send caller name for active calls
        metadata.callerName?.let { name ->
            Log.d(TAG, "Sending CALL_FRIENDLY_NAME: '$name'")
            Log.d(TAG, "Connected devices count: ${bleManager.getConnectedDevices().size}")
            Log.d(TAG, "Recently connected devices count: ${recentlyConnectedDevices.size}")
            
            // Force send friendly name on state changes to ensure it's delivered
            val gattServer = bleManager.getGattServer()
            val service = gattServer?.getService(TBS_SERVICE_UUID)
            val characteristic = service?.getCharacteristic(CALL_FRIENDLY_NAME_UUID)
            
            if (characteristic != null) {
                Log.d(TAG, "Forcing CALL_FRIENDLY_NAME notification (bypassing change detection)")
                characteristic.value = name.toByteArray(Charsets.UTF_8)
                
                bleManager.getConnectedDevices().forEach { device ->
                    val ok = bleManager.notifyCharacteristicChanged(device, characteristic)
                    Log.d(TAG, "Force notify CALL_FRIENDLY_NAME to ${device.address} → $ok")
                }
                
                // Update last sent value
                lastSentValues[CALL_FRIENDLY_NAME_UUID] = name
            } else {
                Log.w(TAG, "CALL_FRIENDLY_NAME characteristic not found for forced notification")
            }
        } ?: metadata.phoneNumber?.let { number ->
            Log.d(TAG, "Sending CALL_FRIENDLY_NAME: '$number'")
            Log.d(TAG, "Connected devices count: ${bleManager.getConnectedDevices().size}")
            Log.d(TAG, "Recently connected devices count: ${recentlyConnectedDevices.size}")
            
            // Force send friendly name on state changes to ensure it's delivered
            val gattServer = bleManager.getGattServer()
            val service = gattServer?.getService(TBS_SERVICE_UUID)
            val characteristic = service?.getCharacteristic(CALL_FRIENDLY_NAME_UUID)
            
            if (characteristic != null) {
                Log.d(TAG, "Forcing CALL_FRIENDLY_NAME notification (bypassing change detection)")
                characteristic.value = number.toByteArray(Charsets.UTF_8)
                
                bleManager.getConnectedDevices().forEach { device ->
                    val ok = bleManager.notifyCharacteristicChanged(device, characteristic)
                    Log.d(TAG, "Force notify CALL_FRIENDLY_NAME to ${device.address} → $ok")
                }
                
                // Update last sent value
                lastSentValues[CALL_FRIENDLY_NAME_UUID] = number
            } else {
                Log.w(TAG, "CALL_FRIENDLY_NAME characteristic not found for forced notification")
            }
        }

        // Clear recently connected devices after sending initial notifications
        if (recentlyConnectedDevices.isNotEmpty()) {
            Log.d(TAG, "Clearing recently connected devices list (${recentlyConnectedDevices.size} devices)")
            recentlyConnectedDevices.clear()
        }
        
        Log.d(TAG, "Updated TBS metadata: ${metadata.callerName ?: metadata.phoneNumber ?: "Unknown"} - ${metadata.state.name}")
    }
    
    /**
     * Sends string notification to characteristic
     */
    private fun notifyCharacteristic(uuid: UUID, value: String) {
        val gattServer = bleManager.getGattServer() ?: return
        val service = gattServer.getService(TBS_SERVICE_UUID) ?: return
        val characteristic = service.getCharacteristic(uuid) ?: return

        // Check if value actually changed
        val lastValue = lastSentValues[uuid]
        val hasChanged = lastValue != value
        
        // Always send to newly connected devices, even if value hasn't changed
        val shouldSendToNewDevices = recentlyConnectedDevices.isNotEmpty()
        val shouldSendToAllDevices = hasChanged
        
        if (!shouldSendToAllDevices && !shouldSendToNewDevices) {
            Log.d(TAG, "No change for $uuid: '$value' and no new devices (skipping notification)")
            return
        }

        // Update the characteristic value
        characteristic.value = value.toByteArray(Charsets.UTF_8)

        // Send notifications to devices
        val connectedDevices = bleManager.getConnectedDevices()
        Log.d(TAG, "Total connected devices for notification: ${connectedDevices.size}")
        
        if (shouldSendToAllDevices) {
            // Send to all connected devices on value change
            Log.d(TAG, "Value changed for $uuid: '$lastValue' → '$value'")
            connectedDevices.forEach { device ->
                val ok = bleManager.notifyCharacteristicChanged(device, characteristic)
                Log.d(TAG, "Notify $uuid to ${device.address} (change) → $ok")
            }
            // Update last sent value
            lastSentValues[uuid] = value
        } else if (shouldSendToNewDevices) {
            // Send only to recently connected devices (same value)
            Log.d(TAG, "Sending current value for $uuid to newly connected devices: '$value'")
            recentlyConnectedDevices.forEach { device ->
                val ok = bleManager.notifyCharacteristicChanged(device, characteristic)
                Log.d(TAG, "Notify $uuid to ${device.address} (new device) → $ok")
            }
        }
    }
    
    /**
     * Sends byte array notification to characteristic
     */
    private fun notifyCharacteristicBytes(uuid: UUID, value: ByteArray) {
        val gattServer = bleManager.getGattServer() ?: return
        val service = gattServer.getService(TBS_SERVICE_UUID) ?: return
        val characteristic = service.getCharacteristic(uuid) ?: return

        // Check if value actually changed (for byte arrays)
        val lastValue = characteristic.value
        val hasChanged = !lastValue.contentEquals(value)
        
        // Always send to newly connected devices
        val shouldSendToNewDevices = recentlyConnectedDevices.isNotEmpty()
        val shouldSendToAllDevices = hasChanged
        
        if (!shouldSendToAllDevices && !shouldSendToNewDevices) {
            Log.d(TAG, "No change for $uuid: ${value.contentToString()} and no new devices (skipping notification)")
            return
        }

        // Log the byte details for debugging
        Log.d(TAG, "notifyCharacteristicBytes for ${getCharacteristicName(uuid)}:")
        Log.d(TAG, "    Raw bytes: ${value.contentToString()}")
        Log.d(TAG, "    Decimal values: ${value.joinToString(", ") { (it.toInt() and 0xFF).toString() }}")
        Log.d(TAG, "    Hex values: ${value.joinToString(", ") { "0x%02X".format(it.toInt() and 0xFF) }}")

        // Update the characteristic value
        characteristic.value = value

        // Send notifications to devices
        val connectedDevices = bleManager.getConnectedDevices()
        if (shouldSendToAllDevices) {
            // Send to all connected devices on value change
            Log.d(TAG, "Value changed for $uuid: '${lastValue?.contentToString() ?: "null"}' → '${value.contentToString()}'")
            connectedDevices.forEach { device ->
                val ok = bleManager.notifyCharacteristicChanged(device, characteristic)
                Log.d(TAG, "Notify $uuid to ${device.address} (change) → $ok")
            }
        } else if (shouldSendToNewDevices) {
            // Send only to recently connected devices
            Log.d(TAG, "Sending current value for $uuid to newly connected devices: ${value.contentToString()}")
            recentlyConnectedDevices.forEach { device ->
                val ok = bleManager.notifyCharacteristicChanged(device, characteristic)
                Log.d(TAG, "Notify $uuid to ${device.address} (new device) → $ok")
            }
        }
    }
    
    /**
     * Gets human-readable characteristic name for logging
     */
    private fun getCharacteristicName(uuid: UUID): String {
        return when (uuid) {
            CALL_STATE_UUID -> "CALL_STATE"
            CALL_CONTROL_POINT_UUID -> "CALL_CONTROL_POINT"
            CALL_FRIENDLY_NAME_UUID -> "CALL_FRIENDLY_NAME"
            TERMINATION_REASON_UUID -> "TERMINATION_REASON"
            else -> uuid.toString()
        }
    }
    
    /**
     * Handles Call Control Point commands
     */
    fun handleCallControlCommand(value: ByteArray): Int {
        if (value.isEmpty()) return 0
        
        val rawCommand = value[0].toInt() and 0xFF
        Log.d(TAG, "TBS Call Control Command received: 0x${"%02x".format(rawCommand)}")
        
        return rawCommand
    }
    
    /**
     * Adds a recently connected device to track for initial notifications
     */
    fun addRecentlyConnectedDevice(device: BluetoothDevice) {
        recentlyConnectedDevices.add(device)
        Log.d(TAG, "Added recently connected device: ${device.address}")
    }
    
    /**
     * Sends initial call state to newly connected device
     */
    fun sendInitialCallState(device: BluetoothDevice) {
        currentCallMetadata?.let { metadata ->
            Log.d(TAG, "Sending initial call state to ${device.address}")
            updateCallMetadata(metadata)
        } ?: run {
            // Send default idle state
            Log.d(TAG, "Sending default idle call state to ${device.address}")
            updateCallMetadata(CallMetadata.createIdleState())
        }
    }
    
    /**
     * Gets current call metadata
     */
    fun getCurrentCallMetadata(): CallMetadata? = currentCallMetadata
}