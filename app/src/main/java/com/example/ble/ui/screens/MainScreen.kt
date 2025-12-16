package com.example.ble.ui.screens

import android.Manifest
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ble.models.BleDevice
import com.example.ble.models.PhoneCallState
import com.example.ble.utils.PermissionUtils
import com.example.ble.viewmodels.MainViewModel

@Composable
fun MainScreen(
    viewModel: MainViewModel = viewModel(),
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scannedDevices by viewModel.scannedDevices.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val currentMedia by viewModel.currentMedia.collectAsState()
    val currentCall by viewModel.currentCall.collectAsState()
    val hasNotificationAccess by viewModel.hasNotificationAccess.collectAsState()
    val scanFilter by viewModel.scanFilter.collectAsState()
    
    // Local state for filter input
    var filterText by remember { mutableStateOf("") }

    // Permission launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        // Handle permission results if needed
    }

    LaunchedEffect(Unit) {
        // Request permissions on first launch
        permissionLauncher.launch(PermissionUtils.getBlePermissions())
        
        // Check notification access
        viewModel.setNotificationAccess(PermissionUtils.hasNotificationListenerAccess(context))
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Status Section
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = "BLE Central Status",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // Connection status
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (connectionState.contains("Connected")) 
                            Icons.Default.CheckCircle else Icons.Default.Warning,
                        contentDescription = null,
                        tint = if (connectionState.contains("Connected")) 
                            Color.Green else Color.Red
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = connectionState)
                }
                
                // Media info
                if (currentMedia.title != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Playing: ${currentMedia.title}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (currentMedia.artist != null) {
                        Text(
                            text = "Artist: ${currentMedia.artist}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                
                // Phone call info
                if (currentCall.state != PhoneCallState.IDLE) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Call: ${currentCall.state}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (currentCall.state == PhoneCallState.RINGING) 
                            Color.Red else Color.Green
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Controls Section
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = "BLE Controls",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                
                Spacer(modifier = Modifier.height(12.dp))
                
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { 
                            if (isScanning) {
                                viewModel.stopBLEScan()
                            } else {
                                viewModel.startBLEScan()
                            }
                        },
                        modifier = Modifier.weight(1f),
                        enabled = viewModel.hasBlePermissions() && viewModel.isBluetoothEnabled()
                    ) {
                        Icon(
                            imageVector = if (isScanning) Icons.Default.Close else Icons.Default.Search,
                            contentDescription = null
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(if (isScanning) "Stop Scan" else "Start Scan")
                    }
                    
                    Button(
                        onClick = { viewModel.disconnectFromDevice() },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = null
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Disconnect")
                    }
                }
                
                // Notification access button
                if (!hasNotificationAccess) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                            context.startActivity(intent)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondary
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Notifications,
                            contentDescription = null
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Enable Notification Access")
                    }
                }
                
                // Scan Filter Input
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = filterText,
                    onValueChange = { newValue ->
                        filterText = newValue
                        viewModel.setScanFilter(newValue)
                    },
                    label = { Text("Filter by device name") },
                    placeholder = { Text("Enter device name...") },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Filter"
                        )
                    },
                    trailingIcon = {
                        if (filterText.isNotEmpty()) {
                            IconButton(
                                onClick = {
                                    filterText = ""
                                    viewModel.clearScanFilter()
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Clear,
                                    contentDescription = "Clear filter"
                                )
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                
                if (scanFilter.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Filtering devices containing: \"$scanFilter\"",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Device List
        if (scannedDevices.isNotEmpty()) {
            Text(
                text = "Discovered Devices (${scannedDevices.size})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(scannedDevices) { device ->
                    DeviceCard(
                        device = device,
                        onConnect = { viewModel.connectToDevice(device) }
                    )
                }
            }
        } else if (isScanning) {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Scanning for devices...")
                }
            }
        } else {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No devices found. Start scanning to discover BLE devices.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceCard(
    device: BleDevice,
    onConnect: () -> Unit
) {
    Card(
        onClick = onConnect,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (device.isConnected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = device.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = device.address,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "RSSI: ${device.rssi} dBm",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            Column(
                horizontalAlignment = Alignment.End
            ) {
                if (device.isConnected) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = "Connected",
                        tint = Color.Green
                    )
                } else if (device.isPaired) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Paired",
                        tint = Color.Blue
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Phone,
                        contentDescription = "Available",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}