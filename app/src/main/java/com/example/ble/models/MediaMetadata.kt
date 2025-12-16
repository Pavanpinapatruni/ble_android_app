package com.example.ble.models

data class MediaMetadata(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val packageName: String? = null,
    val isPlaying: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)