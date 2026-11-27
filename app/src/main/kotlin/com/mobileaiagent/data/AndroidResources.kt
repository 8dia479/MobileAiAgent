package com.mobileaiagent.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import com.mobileaiagent.domain.repo.ResourceMonitor

/**
 * Reads real network/battery/storage state so the runner can defer work instead
 * of hammering a dead network or draining a dying battery (spec §1.4).
 */
class AndroidResourceMonitor(private val context: Context) : ResourceMonitor {

    private val connectivity: ConnectivityManager?
        get() = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    override fun isNetworkAvailable(): Boolean {
        val cm = connectivity ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    override fun isUnmeteredNetwork(): Boolean {
        val cm = connectivity ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    override fun isBatteryLow(): Boolean {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return false
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return level in 1..15
    }

    override fun isPowerSaving(): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return pm.isPowerSaveMode
    }

    override fun availableDiskBytes(): Long = try {
        StatFs(context.filesDir.absolutePath).availableBytes
    } catch (e: Exception) {
        try {
            StatFs(Environment.getDataDirectory().absolutePath).availableBytes
        } catch (e2: Exception) {
            0L
        }
    }
}
