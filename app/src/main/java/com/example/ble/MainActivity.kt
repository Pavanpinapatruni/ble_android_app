package com.example.ble

import android.content.IntentFilter
import android.os.Bundle
import android.telephony.TelephonyManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ble.receivers.PhoneStateReceiver
import com.example.ble.ui.screens.MainScreen
import com.example.ble.ui.theme.BleTheme
import com.example.ble.viewmodels.MainViewModel
import com.example.ble.utils.NotificationListenerUtils
import android.util.Log

class MainActivity : ComponentActivity() {

    private lateinit var phoneStateReceiver: PhoneStateReceiver
    
    companion object {
        private const val TAG = "MainActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Register phone state receiver
        phoneStateReceiver = PhoneStateReceiver()
        val intentFilter = IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
        registerReceiver(phoneStateReceiver, intentFilter)
        
        // Check notification listener permission for caller ID
        checkNotificationListenerPermission()

        setContent {
            BleTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    MainScreen(
                        viewModel = viewModel<MainViewModel>(),
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(phoneStateReceiver)
        } catch (e: Exception) {
            // Receiver might not be registered
        }
    }
    
    private fun checkNotificationListenerPermission() {
        if (!NotificationListenerUtils.hasNotificationListenerPermission(this)) {
            Log.i(TAG, "Notification listener permission not granted")
            Log.i(TAG, "Caller ID will not work until permission is granted")
            Log.i(TAG, "To grant permission: ${NotificationListenerUtils.getPermissionExplanation()}")
            
            // For now, just log. In a real app, you might show a dialog or prompt
            // NotificationListenerUtils.requestNotificationListenerPermission(this)
        } else {
            Log.i(TAG, "Notification listener permission is granted - caller ID will work")
        }
    }
}
