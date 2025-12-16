# BLE Central Manager with Spotify & Phone Call Integration

This Android application acts as a BLE (Bluetooth Low Energy) Central device that can:
- Scan for and connect to BLE peripheral devices
- Access Spotify (and other music apps) metadata via NotificationListener
- Monitor phone call states
- Broadcast media and call information to connected BLE devices via GATT server

## Features

### 🎵 **Media Metadata Access**
- Monitors music apps including Spotify, YouTube Music, Google Play Music, etc.
- Extracts title, artist, album, and playback state
- Broadcasts metadata to connected BLE devices

### 📞 **Phone Call Monitoring** 
- Tracks phone call states (IDLE, RINGING, OFFHOOK)
- Captures incoming phone numbers
- Sends call state updates to BLE devices

### 🔗 **BLE Central Functionality**
- Scans for nearby BLE devices
- Shows device information (name, address, RSSI)
- Connects and pairs with selected devices
- Acts as GATT server to provide metadata services

### 🎛️ **Modern UI**
- Clean Material Design 3 interface
- Real-time status indicators
- Permission management system
- Live device scanning with status updates

## Setup & Installation

### Prerequisites
- Android Studio Arctic Fox or newer
- Android device with API 26+ (Android 8.0+)
- BLE support (most modern Android devices)

### Installation Steps

1. **Clone/Open Project**
   ```bash
   git clone <your-repo>
   cd ble
   ```

2. **Build Dependencies**
   ```bash
   ./gradlew build
   ```

3. **Install on Device**
   - Connect Android device via USB
   - Enable Developer Options & USB Debugging
   - Run from Android Studio or:
   ```bash
   ./gradlew installDebug
   ```

## Permissions Required

### Runtime Permissions
The app will request these permissions at startup:
- **BLUETOOTH_SCAN** - Scan for BLE devices (Android 12+)
- **BLUETOOTH_CONNECT** - Connect to BLE devices (Android 12+)
- **BLUETOOTH_ADVERTISE** - Advertise BLE services (Android 12+)
- **ACCESS_FINE_LOCATION** - Required for BLE scanning
- **READ_PHONE_STATE** - Monitor phone call state

### Manual Setup Required
1. **Notification Listener Access**
   - Go to Settings → Security & Privacy → Notification access
   - Enable access for "BLE Central Manager"
   - This allows reading Spotify/music metadata

2. **Bluetooth Enable**
   - App will prompt to enable Bluetooth if disabled

## Usage Guide

### 1. First Launch
- Grant all requested permissions
- Enable notification listener access when prompted
- Ensure Bluetooth is enabled

### 2. Media Integration
- Play music on Spotify or other supported apps
- App will display current track information
- Connected BLE devices receive metadata updates

### 3. BLE Device Connection
- Tap "Start Scanning" to discover nearby devices
- Tap on any device to connect
- Green indicator shows connected devices
- Blue indicator shows paired devices

### 4. Phone Call Integration
- Make/receive phone calls
- Call state automatically broadcast to BLE devices
- Incoming number displayed (if available)

## Technical Architecture

### Core Components

**BleManager** (`ble/BleManager.kt`)
- Handles all BLE operations
- Manages GATT server/client connections
- Provides StateFlow-based updates

**MediaListenerService** (`services/MediaListenerService.kt`) 
- NotificationListenerService implementation
- Extracts metadata from music app notifications
- Supports Spotify, YouTube Music, Google Play Music, etc.

**PhoneStateReceiver** (`receivers/PhoneStateReceiver.kt`)
- BroadcastReceiver for phone state changes
- Monitors IDLE/RINGING/OFFHOOK states

**MainViewModel** (`viewmodels/MainViewModel.kt`)
- Manages UI state with StateFlow
- Coordinates between BLE, media, and phone components

### BLE Service UUIDs

```kotlin
MEDIA_SERVICE_UUID  = "0000abcd-0000-1000-8000-00805f9b34fb"
TITLE_UUID         = "00001111-0000-1000-8000-00805f9b34fb" 
ARTIST_UUID        = "00002222-0000-1000-8000-00805f9b34fb"
STATE_UUID         = "00003333-0000-1000-8000-00805f9b34fb"
CALL_STATE_UUID    = "00004444-0000-1000-8000-00805f9b34fb"
```

### Data Models
- **BleDevice** - Represents discovered BLE devices
- **MediaMetadata** - Current media information
- **PhoneCallInfo** - Phone call state data

## Troubleshooting

### Common Issues

**"No devices found during scan"**
- Ensure location services enabled
- Check Bluetooth permissions granted
- Verify target devices are advertising
- Try restarting Bluetooth

**"Spotify metadata not showing"**
- Enable notification listener access
- Restart app after enabling
- Check Spotify is actively playing

**"Phone calls not detected"**
- Grant READ_PHONE_STATE permission
- Test with actual phone call
- Check device compatibility

**"Connection fails"**
- Ensure target device accepts connections
- Check if device requires pairing
- Verify GATT service compatibility

### Debugging
- Monitor Android Studio Logcat
- Filter by "BLE_APP" or "BleManager" tags
- Check for permission-related errors

## Development Notes

### Adding New Music Apps
Edit `MediaListenerService.kt` and add package names to `mediaApps` list:
```kotlin
val mediaApps = listOf(
    "spotify", "com.spotify.music",
    "your.new.app", "another.music.app"
)
```

### Customizing BLE Services
Modify UUIDs in `BleManager.kt` companion object to match your peripheral requirements.

### UI Customization
- Main UI in `ui/screens/MainScreen.kt`
- Uses Jetpack Compose with Material Design 3
- StateFlow-based reactive updates

## Security Considerations

- App only reads notification metadata, not content
- Phone number access limited to incoming calls
- BLE connections use standard Android security
- No data stored permanently
- All permissions explicitly requested

## Support

For issues or questions:
1. Check Android Studio Logcat for errors
2. Verify all permissions granted
3. Test with different BLE devices
4. Ensure target Android API compatibility

---

**Built with:** Kotlin, Jetpack Compose, BLE GATT, NotificationListenerService
**Target:** Android 8.0+ (API 26+)
**Architecture:** MVVM with StateFlow and Coroutines