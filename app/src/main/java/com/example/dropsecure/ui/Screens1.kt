package com.example.dropsecure.ui

import com.example.dropsecure.container
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.dropsecure.discovery.DiscoveryController
import com.example.dropsecure.manager.Direction
import com.example.dropsecure.manager.Stage
import com.example.dropsecure.permissions.PermissionHelper
import com.example.dropsecure.transport.TransportType
import com.example.dropsecure.util.Format
import com.example.dropsecure.util.UriInfo
import kotlinx.coroutines.delay

// ------------------------------------------------------------------------------------ 1. splash

@Composable
fun SplashScreen(onDone: () -> Unit) {
    LaunchedEffect(Unit) {
        delay(1400)
        onDone()
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            DropSecureLogo(size = 120.dp)
            Spacer(Modifier.height(16.dp))
            Text("DropSecure", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Text("Offline encrypted file sharing", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

// ------------------------------------------------------------------------------------ 2. home

@Composable
fun HomeScreen(
    onSend: (TransportType) -> Unit,
    onReceive: (TransportType) -> Unit,
    onHistory: () -> Unit,
    onSettings: () -> Unit,
    onActiveTransfers: () -> Unit
) {
    val ctx = LocalContext.current
    val container = ctx.container()
    val jobs by container.manager.jobs.collectAsState()
    val default by container.settings.defaultTransport.collectAsState()
    val activeCount = jobs.count { !it.stage.finished }
    val order = if (default == TransportType.WIFI_DIRECT) listOf(TransportType.WIFI_DIRECT, TransportType.BLUETOOTH)
    else listOf(TransportType.BLUETOOTH, TransportType.WIFI_DIRECT)

    AppScaffold(
        title = "DropSecure",
        actions = { androidx.compose.material3.TextButton(onClick = onSettings) { Text("Settings") } }
    ) { padding ->
        Column(
            Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DropSecureLogo(size = 48.dp)
                Text("Send files to phones near you. No internet needed.", style = MaterialTheme.typography.bodyLarge)
            }
            if (activeCount > 0) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("$activeCount transfer(s) in progress")
                        Button(onClick = onActiveTransfers) { Text("View") }
                    }
                }
            }
            for (t in order) {
                TransportCard(
                    title = t.label,
                    subtitle = if (t == TransportType.WIFI_DIRECT) "Fast. Best for large files." else "Slower, but works on most phones.",
                    onSend = { onSend(t) },
                    onReceive = { onReceive(t) }
                )
            }
            OutlinedButton(onClick = onHistory, modifier = Modifier.fillMaxWidth()) { Text("Transfer history") }
            Text(
                "Files are encrypted on your phone with AES-256-GCM before they are sent. " +
                    "Always compare the verification code on both phones.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun TransportCard(title: String, subtitle: String, onSend: () -> Unit, onReceive: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = onSend, modifier = Modifier.weight(1f)) { Text("Send") }
                OutlinedButton(onClick = onReceive, modifier = Modifier.weight(1f)) { Text("Receive") }
            }
        }
    }
}

// ------------------------------------------------------------------------------------ 3. send

@Composable
fun SendScreen(transport: TransportType, onBack: () -> Unit, onFindDevices: () -> Unit) {
    val ctx = LocalContext.current
    val draft = ctx.container().draft
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) { /* some providers do not allow it; resume then needs re-picking */ }
            val (name, size) = UriInfo.describe(ctx, uri)
            draft.uri = uri; draft.name = name; draft.size = size; draft.resumeTransferId = null
        }
    }
    AppScaffold("Send via ${transport.label}", onBack) { padding ->
        Column(Modifier.padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("1. Choose the file to send", style = MaterialTheme.typography.titleMedium)
            Button(onClick = { picker.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
                Text(if (draft.uri == null) "Choose file" else "Choose a different file")
            }
            if (draft.uri != null) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(draft.name, style = MaterialTheme.typography.titleMedium)
                        if (draft.size >= 0) Text(Format.bytes(draft.size), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            Text("2. Find the receiving phone", style = MaterialTheme.typography.titleMedium)
            Text(
                "The other person opens DropSecure, taps ${transport.label} > Receive, and keeps that screen open.",
                style = MaterialTheme.typography.bodyMedium
            )
            Button(onClick = onFindDevices, enabled = draft.uri != null, modifier = Modifier.fillMaxWidth()) {
                Text("Find nearby devices")
            }
        }
    }
}

private fun isLocationEnabled(ctx: Context): Boolean {
    val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
    return lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
}

// ------------------------------------------------------------------------------------ 4. discovery

@Composable
fun DiscoveryScreen(transport: TransportType, onBack: () -> Unit, onStarted: () -> Unit) {
    RequirePermissions(
        permissions = PermissionHelper.forTransport(transport),
        title = "Allow nearby device access",
        rationale = if (transport == TransportType.WIFI_DIRECT)
            "DropSecure needs Location and Nearby devices permissions to discover phones around you with Wi-Fi Direct and " +
                "Bluetooth Low Energy. It is used only to find the phone you want to send to."
        else
            "DropSecure needs Location and Nearby devices permissions to find and connect to Bluetooth devices around you."
    ) {
        DiscoveryContent(transport, onBack, onStarted)
    }
}

@Composable
private fun DiscoveryContent(transport: TransportType, onBack: () -> Unit, onStarted: () -> Unit) {
    val ctx = LocalContext.current
    val container = ctx.container()
    val scope = rememberCoroutineScope()
    val controller = remember { DiscoveryController(ctx, scope, container.manager.wifi, container.manager.bluetooth, transport) }
    var isSending by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        controller.start()
        onDispose {
            controller.stop(stopWifi = !isSending)
        }
    }
    val peers by controller.peers.collectAsState()
    val status by controller.status.collectAsState()
    val selected = remember { mutableStateListOf<String>() }
    val draft = container.draft

    val enableBt = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { controller.start() }

    AppScaffold("Nearby devices", onBack) { padding ->
        Column(Modifier.padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("File: ${draft.name}", style = MaterialTheme.typography.bodyMedium)
            if (draft.resumeTransferId != null) {
                Text("Resuming an interrupted transfer. Choose the SAME receiver as before.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            }

            if (!isLocationEnabled(ctx)) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            "⚠️ Location (GPS) switch is turned OFF",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "Android requires Location (GPS) switch to be ON in Quick Settings to discover nearby Wi-Fi Direct & Bluetooth devices.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Button(
                            onClick = { ctx.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Turn Location ON") }
                    }
                }
            }

            if (transport == TransportType.WIFI_DIRECT && !container.manager.wifi.isWifiOn()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            "⚠️ Wi-Fi switch is turned OFF",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "Wi-Fi must be turned ON to discover nearby Wi-Fi Direct devices.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Button(
                            onClick = { ctx.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Turn Wi-Fi ON") }
                    }
                }
            }

            if (transport == TransportType.BLUETOOTH) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Bluetooth Discovery Tip", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Text(
                            "The receiver phone must be on Receive screen and tap 'Allow' on the discoverable prompt. You can also pair both phones once in Bluetooth Settings for instant discovery.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        OutlinedButton(
                            onClick = { ctx.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Open Bluetooth Settings to Pair") }
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { controller.start() }) { Text(if (status.scanning) "Scanning..." else "Scan again") }
                Text(
                    if (transport == TransportType.WIFI_DIRECT) "Wi-Fi Direct + Bluetooth LE" else "Bluetooth",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            status.message?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                if (it.contains("Bluetooth is off") || it.contains("Turn Bluetooth on")) {
                    Button(onClick = { enableBt.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }) { Text("Turn Bluetooth on") }
                }
            }
            if (peers.isEmpty()) {
                Text(
                    if (transport == TransportType.BLUETOOTH)
                        "Searching... The other phone must be on DropSecure > Bluetooth > Receive."
                    else "Searching... The other phone must be on DropSecure > Wi-Fi Direct > Receive.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(peers, key = { it.key }) { p ->
                    val checked = p.key in selected
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = p.sendable) {
                                if (checked) selected.remove(p.key) else selected.add(p.key)
                            }
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = checked,
                                enabled = p.sendable,
                                onCheckedChange = { if (it) selected.add(p.key) else selected.remove(p.key) }
                            )
                            Column(Modifier.weight(1f)) {
                                Text(p.name, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    buildString {
                                        append(
                                            when {
                                                !p.sendable && transport == TransportType.BLUETOOTH -> "Locating Bluetooth Classic address... (Tap 'Make discoverable' on receiver or pair in Settings)"
                                                !p.sendable -> "Locating over Wi-Fi Direct..."
                                                p.verifiedDropSecure -> "DropSecure phone, ready"
                                                transport == TransportType.BLUETOOTH -> "Bluetooth device"
                                                else -> "Wi-Fi Direct device (not confirmed as DropSecure)"
                                            }
                                        )
                                        p.rssi?.let { append("  -  signal $it dBm") }
                                    },
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
            }
            val chosen = peers.filter { it.key in selected && it.sendable }
            if (chosen.size > 1) {
                Text(
                    if (transport == TransportType.WIFI_DIRECT)
                        "Several receivers: each gets its own secure session. Wi-Fi Direct sends run one after another."
                    else "Several receivers: each gets its own secure session (up to 3 at the same time).",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Button(
                onClick = {
                    val uri = draft.uri
                    if (uri == null) {
                        Toast.makeText(ctx, "No file selected", Toast.LENGTH_SHORT).show()
                    } else {
                        isSending = true
                        controller.stop(stopWifi = false)
                        container.manager.startSend(uri, chosen.map { it.toPeer() }, transport, draft.resumeTransferId)
                        draft.clear()
                        onStarted()
                    }
                },
                enabled = chosen.isNotEmpty(),
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (chosen.size <= 1) "Send" else "Send to ${chosen.size} devices") }
        }
    }
}

// ------------------------------------------------------------------------------------ 5-8. send flow

@Composable
fun FlowScreen(onHome: () -> Unit) {
    val ctx = LocalContext.current
    val container = ctx.container()
    val all by container.manager.jobs.collectAsState()
    val keepOn by container.settings.keepScreenOn.collectAsState()
    val jobs = all.filter { it.direction == Direction.SEND }
    KeepScreenOn(keepOn && jobs.any { !it.stage.finished })

    AppScaffold("Sending", onBack = onHome) { padding ->
        if (jobs.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No transfers.")
                    Button(onClick = onHome) { Text("Home") }
                }
            }
        } else {
            LazyColumn(Modifier.padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(jobs, key = { it.jobId }) { JobCard(it, container.manager) }
                item {
                    if (jobs.all { it.stage.finished }) {
                        Button(onClick = onHome, modifier = Modifier.fillMaxWidth()) { Text("Done") }
                    } else {
                        Text(
                            "Keep both phones close together and this screen open until the transfer is finished.",
                            style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------------------------ receive

@Composable
fun ReceiveScreen(transport: TransportType, onBack: () -> Unit) {
    RequirePermissions(
        permissions = PermissionHelper.forTransport(transport),
        title = "Allow nearby device access",
        rationale = "DropSecure needs the Nearby devices permission so the sender can find your phone. " +
            "It is only active while this screen is open."
    ) {
        ReceiveContent(transport, onBack)
    }
}

@Composable
private fun ReceiveContent(transport: TransportType, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val container = ctx.container()
    val manager = container.manager
    val status by manager.receiving.collectAsState()
    val all by manager.jobs.collectAsState()
    val keepOn by container.settings.keepScreenOn.collectAsState()
    val jobs = all.filter { it.direction == Direction.RECEIVE }
    KeepScreenOn(keepOn && status.active)

    val discoverable = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        manager.startReceiving(TransportType.BLUETOOTH)
    }
    fun start() {
        manager.startReceiving(transport)
        if (transport == TransportType.BLUETOOTH) {
            try {
                discoverable.launch(
                    Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
                )
            } catch (_: Exception) {}
        }
    }

    LaunchedEffect(transport) {
        start()
    }

    DisposableEffect(transport) {
        onDispose {
            val busy = manager.jobs.value.any { it.direction == Direction.RECEIVE && !it.stage.finished }
            if (!busy) manager.stopReceiving()
        }
    }

    AppScaffold("Receive via ${transport.label}", onBack) { padding ->
        LazyColumn(Modifier.padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!isLocationEnabled(ctx)) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                    ) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                "⚠️ Location (GPS) switch is turned OFF",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "Android requires Location (GPS) switch to be ON in Quick Settings so the sender can discover this phone.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Button(
                                onClick = { ctx.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("Turn Location ON") }
                        }
                    }
                }
            }
            if (transport == TransportType.WIFI_DIRECT && !manager.wifi.isWifiOn()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                    ) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                "⚠️ Wi-Fi switch is turned OFF",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "Wi-Fi must be turned ON to receive files via Wi-Fi Direct. Turn it on in Quick Settings or Settings below.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Button(
                                onClick = { ctx.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("Turn Wi-Fi ON") }
                        }
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (status.active && status.transport == transport) {
                            Text("Ready to receive", style = MaterialTheme.typography.titleMedium)
                            Text("Visible as: ${status.deviceName}" + if (status.localId.isNotEmpty()) "  (DS-${status.localId})" else "", style = MaterialTheme.typography.bodyMedium)
                            Text(status.message, style = MaterialTheme.typography.bodyMedium)
                            if (transport == TransportType.WIFI_DIRECT) {
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                                ) {
                                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text("🔔 ACTION REQUIRED WHEN SENDER CONNECTS:", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                        Text(
                                            "When the sender taps Send, Android WILL show a system prompt at top of screen or notification bar: 'Accept Wi-Fi Direct invitation?'\n" +
                                            "You MUST tap ACCEPT on that prompt!",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer
                                        )
                                    }
                                }
                            }
                            Text(
                                if (transport == TransportType.WIFI_DIRECT)
                                    "On the sender's phone choose Send > Wi-Fi Direct and pick this device. Android may ask you to accept a Wi-Fi Direct invitation: tap Accept."
                                else "On the sender's phone choose Send > Bluetooth and pick this device. Bluetooth stays visible for 5 minutes.",
                                style = MaterialTheme.typography.bodySmall
                            )
                            if (transport == TransportType.BLUETOOTH) {
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                                ) {
                                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text("Bluetooth Receiver Visibility", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                        Text(
                                            "Make sure to tap 'Allow' when Android prompts to make your device visible to nearby Bluetooth devices.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer
                                        )
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Button(
                                                onClick = {
                                                    try {
                                                        discoverable.launch(Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300))
                                                    } catch (_: Exception) {}
                                                },
                                                modifier = Modifier.weight(1f)
                                            ) { Text("Make discoverable") }
                                            OutlinedButton(
                                                onClick = { ctx.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) },
                                                modifier = Modifier.weight(1f)
                                            ) { Text("Bluetooth settings") }
                                        }
                                    }
                                }
                            }
                            OutlinedButton(onClick = { manager.stopReceiving() }, modifier = Modifier.fillMaxWidth()) { Text("Stop receiving") }
                        } else {
                            Text("Not receiving", style = MaterialTheme.typography.titleMedium)
                            Text(
                                if (status.message.isNotEmpty()) status.message else "Start receiving, then ask the sender to pick this phone.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (status.message.isNotEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                            )
                            if (transport == TransportType.WIFI_DIRECT && !manager.wifi.isWifiOn()) {
                                Button(
                                    onClick = { ctx.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("Turn Wi-Fi ON") }
                            } else {
                                Button(onClick = { start() }, modifier = Modifier.fillMaxWidth()) { Text("Start receiving") }
                            }
                        }
                    }
                }
            }
            items(jobs, key = { it.jobId }) { JobCard(it, manager) }
        }
    }
}
