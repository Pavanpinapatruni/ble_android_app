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

class MainActivity : ComponentActivity() {

    private lateinit var phoneStateReceiver: PhoneStateReceiver

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Register phone state receiver
        phoneStateReceiver = PhoneStateReceiver()
        val intentFilter = IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
        registerReceiver(phoneStateReceiver, intentFilter)

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
}
