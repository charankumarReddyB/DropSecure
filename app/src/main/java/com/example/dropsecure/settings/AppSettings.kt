package com.example.dropsecure.settings

import android.content.Context
import android.os.Build
import android.provider.Settings
import com.example.dropsecure.transport.TransportType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AppSettings(private val ctx: Context) {
    private val prefs = ctx.getSharedPreferences("dropsecure_settings", Context.MODE_PRIVATE)

    private fun defaultName(): String =
        (try { Settings.Global.getString(ctx.contentResolver, "device_name") } catch (_: Exception) { null })
            ?.takeIf { it.isNotBlank() } ?: Build.MODEL ?: "Android"

    private val _deviceName = MutableStateFlow((prefs.getString("device_name", null) ?: defaultName()).take(24))
    val deviceName: StateFlow<String> = _deviceName.asStateFlow()

    private val _defaultTransport = MutableStateFlow(
        runCatching { TransportType.valueOf(prefs.getString("default_transport", null) ?: "") }.getOrDefault(TransportType.WIFI_DIRECT)
    )
    val defaultTransport: StateFlow<TransportType> = _defaultTransport.asStateFlow()

    private val _saveTreeUri = MutableStateFlow(prefs.getString("save_tree", null))
    val saveTreeUri: StateFlow<String?> = _saveTreeUri.asStateFlow()

    private val _keepScreenOn = MutableStateFlow(prefs.getBoolean("keep_screen_on", true))
    val keepScreenOn: StateFlow<Boolean> = _keepScreenOn.asStateFlow()

    fun setDeviceName(v: String) {
        val t = v.trim().take(24).ifEmpty { defaultName() }
        prefs.edit().putString("device_name", t).apply(); _deviceName.value = t
    }

    fun setDefaultTransport(t: TransportType) {
        prefs.edit().putString("default_transport", t.name).apply(); _defaultTransport.value = t
    }

    fun setSaveTreeUri(uri: String?) {
        prefs.edit().apply { if (uri == null) remove("save_tree") else putString("save_tree", uri) }.apply()
        _saveTreeUri.value = uri
    }

    fun setKeepScreenOn(v: Boolean) {
        prefs.edit().putBoolean("keep_screen_on", v).apply(); _keepScreenOn.value = v
    }
}
