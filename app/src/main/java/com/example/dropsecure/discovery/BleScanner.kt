package com.example.dropsecure.discovery

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid

/** Scans only for advertisements carrying the DropSecure service UUID. */
class BleScanner(context: Context) {

    private val adapter =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private var callback: ScanCallback? = null

    @SuppressLint("MissingPermission")
    fun start(onDevice: (id: String, address: String, rssi: Int, name: String?, classicAddress: String?) -> Unit, onError: (String) -> Unit) {
        if (callback != null) return
        if (adapter == null || !adapter.isEnabled) {
            onError("Bluetooth is off. Turn it on and try again.")
            return
        }
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            onError("BLE scanner unavailable.")
            return
        }

        val uuid = ParcelUuid(BleConstants.SERVICE_UUID)
        val filters = emptyList<ScanFilter>()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val record = result.scanRecord ?: return
                val data = record.getServiceData(uuid) ?: record.serviceData?.values?.firstOrNull() ?: return
                if (data.size != BleConstants.ID_LENGTH && data.size != BleConstants.ID_LENGTH + 6) return
                val address = result.device?.address ?: return
                val devName = result.device?.name ?: record.deviceName
                val idHex = data.copyOfRange(0, BleConstants.ID_LENGTH).toHex()
                val classicAddress = if (data.size >= BleConstants.ID_LENGTH + 6) {
                    com.example.dropsecure.transport.BluetoothAddressResolver.bytesToMac(data, BleConstants.ID_LENGTH)
                } else null
                onDevice(idHex, address, result.rssi, devName, classicAddress)
            }

            override fun onBatchScanResults(results: List<ScanResult>) {
                for (r in results) onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, r)
            }

            override fun onScanFailed(errorCode: Int) {
                callback = null
                onError("Scan failed (code $errorCode)")
            }
        }
        try {
            callback = cb
            scanner.startScan(filters, settings, cb)
        } catch (e: SecurityException) {
            callback = null
            onError("Bluetooth permission missing.")
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        val cb = callback ?: return
        try {
            adapter?.bluetoothLeScanner?.stopScan(cb)
        } catch (_: Exception) {
        }
        callback = null
    }
}
