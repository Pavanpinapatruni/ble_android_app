package com.example.ble.utils

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationManagerCompat

object PermissionUtils {
    
    fun hasBlePermissions(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            hasPermission(context, Manifest.permission.BLUETOOTH_SCAN) &&
            hasPermission(context, Manifest.permission.BLUETOOTH_CONNECT) &&
            hasPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE)
        } else {
            hasPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }
    
    fun hasLocationPermission(context: Context): Boolean {
        return hasPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ||
               hasPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
    }
    
    fun hasPhonePermission(context: Context): Boolean {
        return hasPermission(context, Manifest.permission.READ_PHONE_STATE)
    }
    
    fun hasNotificationListenerAccess(context: Context): Boolean {
        return NotificationManagerCompat.getEnabledListenerPackages(context)
            .contains(context.packageName)
    }
    
    private fun hasPermission(context: Context, permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }
    
    fun getBlePermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.READ_PHONE_STATE
            )
        } else {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.READ_PHONE_STATE
            )
        }
    }
}