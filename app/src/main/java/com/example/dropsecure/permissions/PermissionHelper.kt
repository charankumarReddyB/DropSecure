package com.example.dropsecure.permissions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.example.dropsecure.transport.TransportType

/** Runtime permissions per transport. They differ by Android version. */
object PermissionHelper {

    private fun blePermissions(): List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)   // Android 8-11: BLE/Bluetooth scan needs location
        }

    /** Wi-Fi Direct + BLE discovery. */
    fun forWifiDirect(): Array<String> {
        val list = ArrayList<String>()
        list.add(Manifest.permission.ACCESS_FINE_LOCATION)
        list.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) list.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        list.addAll(blePermissions())
        return list.distinct().toTypedArray()
    }

    /** Classic Bluetooth discovery, RFCOMM connection and "discoverable" mode. */
    fun forBluetooth(): Array<String> {
        val list = ArrayList<String>()
        list.add(Manifest.permission.ACCESS_FINE_LOCATION)
        list.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        list.addAll(blePermissions())
        return list.distinct().toTypedArray()
    }

    fun forTransport(t: TransportType): Array<String> =
        if (t == TransportType.WIFI_DIRECT) forWifiDirect() else forBluetooth()

    fun hasAll(context: Context, perms: Array<String>): Boolean =
        perms.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

    /** Location switch (GPS toggle) is required by Android OS for Wi-Fi Direct & Bluetooth discovery. */
    fun needsLocationSwitch(): Boolean = true
}
