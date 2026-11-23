package com.example.dropsecure.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.provider.Settings

object BluetoothAddressResolver {

    @SuppressLint("HardwareIds", "MissingPermission")
    fun getOwnClassicAddress(context: Context): String? {
        // Attempt 1: Settings.Secure (works on ColorOS, OxygenOS, Realme UI, MIUI, OneUI)
        try {
            val addr = Settings.Secure.getString(context.contentResolver, "bluetooth_address")
            if (isValidAddress(addr)) return addr.uppercase()
        } catch (_: Exception) {}

        // Attempt 2: BluetoothAdapter reflection via mService
        try {
            val adapter = BluetoothAdapter.getDefaultAdapter()
            if (adapter != null) {
                val mServiceField = adapter.javaClass.getDeclaredField("mService")
                mServiceField.isAccessible = true
                val mService = mServiceField.get(adapter)
                if (mService != null) {
                    val m = mService.javaClass.getMethod("getAddress")
                    val addr = m.invoke(mService) as? String
                    if (isValidAddress(addr)) return addr!!.uppercase()
                }
            }
        } catch (_: Exception) {}

        // Attempt 3: Direct getAddress()
        try {
            val adapter = BluetoothAdapter.getDefaultAdapter()
            val m = adapter?.javaClass?.getMethod("getAddress")
            val addr = m?.invoke(adapter) as? String
            if (isValidAddress(addr)) return addr!!.uppercase()
        } catch (_: Exception) {}

        return null
    }

    fun isValidAddress(addr: String?): Boolean {
        if (addr.isNullOrBlank()) return false
        if (addr == "02:00:00:00:00:00") return false
        val parts = addr.split(":")
        return parts.size == 6 && parts.all { it.length == 2 && it.toIntOrNull(16) != null }
    }

    fun macToBytes(mac: String): ByteArray? {
        if (!isValidAddress(mac)) return null
        val parts = mac.split(":")
        return try {
            ByteArray(6) { parts[it].toInt(16).toByte() }
        } catch (_: Exception) {
            null
        }
    }

    fun bytesToMac(bytes: ByteArray, offset: Int = 0): String {
        return (0..5).joinToString(":") { i -> "%02X".format(bytes[offset + i]) }
    }
}
