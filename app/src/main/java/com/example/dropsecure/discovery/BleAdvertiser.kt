package com.example.dropsecure.discovery

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.os.ParcelUuid

/**
 * Advertises the DropSecure service UUID (main packet) and a short random ID
 * (scan response). Non-connectable: discovery only. Permissions are checked by the caller.
 */
class BleAdvertiser(context: Context) {

    private val ctx = context.applicationContext
    private val adapter =
        (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private var callback: AdvertiseCallback? = null

    @SuppressLint("MissingPermission")
    fun start(localId: ByteArray, onStarted: () -> Unit, onError: (String) -> Unit) {
        if (callback != null) return
        if (adapter == null || !adapter.isEnabled) {
            onError("Bluetooth is off. Turn it on and try again.")
            return
        }
        val advertiser = adapter.bluetoothLeAdvertiser
        if (advertiser == null) {
            onError("This phone does not support BLE advertising.")
            return
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(false)
            .setTimeout(0)
            .build()

        val uuid = ParcelUuid(BleConstants.SERVICE_UUID)
        val ownClassicMac = com.example.dropsecure.transport.BluetoothAddressResolver.getOwnClassicAddress(ctx)
        val macBytes = ownClassicMac?.let { com.example.dropsecure.transport.BluetoothAddressResolver.macToBytes(it) }
        val payload = if (macBytes != null) localId + macBytes else localId
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceData(uuid, payload)
            .build()
        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .build()

        val cb = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) = onStarted()
            override fun onStartFailure(errorCode: Int) {
                if (errorCode == AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE) {
                    try {
                        advertiser.startAdvertising(settings, data, null, object : AdvertiseCallback() {
                            override fun onStartSuccess(s: AdvertiseSettings) = onStarted()
                            override fun onStartFailure(err: Int) {
                                callback = null
                                onError("Advertising failed (code $err)")
                            }
                        })
                        return
                    } catch (_: Exception) {}
                }
                callback = null
                onError("Advertising failed (code $errorCode)")
            }
        }
        try {
            callback = cb
            advertiser.startAdvertising(settings, data, scanResponse, cb)
        } catch (e: SecurityException) {
            callback = null
            onError("Bluetooth permission missing.")
        } catch (e: Exception) {
            try {
                advertiser.startAdvertising(settings, data, null, cb)
            } catch (_: Exception) {
                callback = null
                onError("Advertising failed: ${e.message}")
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        val cb = callback ?: return
        try {
            adapter?.bluetoothLeAdvertiser?.stopAdvertising(cb)
        } catch (_: Exception) {
        }
        callback = null
    }
}
