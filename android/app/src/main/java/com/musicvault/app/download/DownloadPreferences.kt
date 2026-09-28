package com.musicvault.app.download

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

object DownloadPreferences {
    const val FILE = "musicvault.settings"
    const val QUALITY = "download_quality"
    const val WIFI_ONLY = "download_wifi_only"
    const val CACHE_MB = "stream_cache_mb"
    val cacheSizes = listOf(256, 512, 1024, 2048)
    fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    fun quality(context: Context) = DownloadQuality.parse(prefs(context).getString(QUALITY, null))
    fun wifiOnly(context: Context) = prefs(context).getBoolean(WIFI_ONLY, false)
    fun cacheMb(context: Context) = prefs(context).getInt(CACHE_MB, 1024).takeIf { it in cacheSizes } ?: 1024
    fun wifiAvailable(context: Context): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val caps = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
