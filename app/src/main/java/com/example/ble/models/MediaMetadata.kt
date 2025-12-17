package com.example.ble.models

data class MediaMetadata(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val packageName: String? = null,
    val isPlaying: Boolean = false,
    val duration: Long = 0L, // Duration in milliseconds
    val position: Long = 0L, // Current position in milliseconds
    val timestamp: Long = System.currentTimeMillis()
)