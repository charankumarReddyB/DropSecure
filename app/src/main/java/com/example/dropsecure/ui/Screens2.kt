package com.example.dropsecure.ui

import com.example.dropsecure.container
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.dropsecure.data.TransferEntity
import com.example.dropsecure.transport.TransportType
import com.example.dropsecure.util.Format
import kotlinx.coroutines.launch

// ------------------------------------------------------------------------------------ 9. history

private enum class HistoryFilter(val label: String) { ALL("All"), ACTIVE("Resumable"), DONE("Completed"), PROBLEM("Failed") }

private fun matches(f: HistoryFilter, e: TransferEntity) = when (f) {
    HistoryFilter.ALL -> true
    HistoryFilter.ACTIVE -> e.status == "IN_PROGRESS" || e.status == "INTERRUPTED"
    HistoryFilter.DONE -> e.status == "COMPLETED"
    HistoryFilter.PROBLEM -> e.status == "FAILED" || e.status == "CANCELLED" || e.status == "REJECTED"
}

private fun statusLabel(s: String) = when (s) {
    "IN_PROGRESS" -> "In progress"
    "INTERRUPTED" -> "Interrupted (resumable)"
    "COMPLETED" -> "Completed"
    "FAILED" -> "Failed"
    "CANCELLED" -> "Cancelled"
    "REJECTED" -> "Declined"
    else -> s
}

@Composable
fun HistoryScreen(onBack: () -> Unit, onResume: (TransportType) -> Unit, onReceive: (TransportType) -> Unit) {
    val ctx = LocalContext.current
    val container = ctx.container()
    val manager = container.manager
    val list by manager.history.collectAsState(initial = emptyList())
    var filter by remember { mutableStateOf(HistoryFilter.ALL) }
    var showMenu by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) scope.launch {
            try {
                val n = manager.exportCsv(uri)
                Toast.makeText(ctx, "Exported $n record(s)", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(ctx, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
    val shown = list.filter { matches(filter, it) }

    AppScaffold(
        title = "Transfer history",
        onBack = onBack,
        actions = {
            IconButton(onClick = { showMenu = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = "More options")
            }
            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                DropdownMenuItem(
                    text = { Text("Export CSV") },
                    onClick = {
                        showMenu = false
                        exporter.launch("dropsecure-results.csv")
                    }
                )
                DropdownMenuItem(
                    text = { Text("Clear finished") },
                    onClick = {
                        showMenu = false
                        manager.clearFinishedRecords()
                    }
                )
            }
        }
    ) { padding ->
        LazyColumn(Modifier.padding(padding).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { PerformanceCard(list) }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    for (f in HistoryFilter.values()) {
                        FilterChip(
                            selected = f == filter,
                            onClick = { filter = f },
                            label = { Text(f.label) }
                        )
                    }
                }
            }
            if (shown.isEmpty()) item { Text("No transfers here yet.", style = MaterialTheme.typography.bodyMedium) }
            items(shown, key = { it.transferId }) { e ->
                HistoryItem(
                    e = e,
                    onResume = {
                        val uri = e.sourceUri?.let { Uri.parse(it) }
                        val ok = uri != null && try { ctx.contentResolver.openInputStream(uri)?.close(); true } catch (_: Exception) { false }
                        if (!ok) {
                            Toast.makeText(ctx, "The original file is no longer accessible. Choose it again from Send.", Toast.LENGTH_LONG).show()
                        } else {
                            val d = container.draft
                            d.uri = uri; d.name = e.fileName; d.size = e.size; d.resumeTransferId = e.transferId
                            onResume(runCatching { TransportType.valueOf(e.transport) }.getOrDefault(TransportType.WIFI_DIRECT))
                        }
                    },
                    onReceive = { onReceive(runCatching { TransportType.valueOf(e.transport) }.getOrDefault(TransportType.WIFI_DIRECT)) },
                    onDelete = { manager.deleteRecord(e.transferId) }
                )
            }
            item { androidx.compose.foundation.layout.Spacer(Modifier.padding(8.dp)) }
        }
    }
}

@Composable
private fun HistoryItem(e: TransferEntity, onResume: () -> Unit, onReceive: () -> Unit, onDelete: () -> Unit) {
    val sent = e.direction == "SEND"
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(e.fileName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                (if (sent) "Sent to " else "Received from ") + e.peerName.ifEmpty { "unknown" } + " - " +
                    (runCatching { TransportType.valueOf(e.transport).label }.getOrDefault(e.transport)) + " - " + Format.bytes(e.size),
                style = MaterialTheme.typography.bodySmall
            )
            Text(statusLabel(e.status) + " - " + Format.time(e.updatedAtMs), style = MaterialTheme.typography.bodyMedium)
            if ((e.status == "INTERRUPTED" || e.status == "IN_PROGRESS") && e.size > 0) {
                LinearProgressIndicator(progress = { (e.bytesDone.toDouble() / e.size).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                Text("${Format.bytes(e.bytesDone)} of ${Format.bytes(e.size)} saved", style = MaterialTheme.typography.bodySmall)
            }
            if (e.status == "COMPLETED" && e.throughputMBps > 0) {
                Text("${Format.mbps(e.throughputMBps)} over ${Format.duration(e.durationMs)}", style = MaterialTheme.typography.bodySmall)
            }
            if (e.integrityFailure) Text("Integrity check failure detected", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            e.error?.let { if (e.status != "COMPLETED") Text(it, style = MaterialTheme.typography.bodySmall) }
            e.savedTo?.let { if (e.status == "COMPLETED") Text("Saved to: $it", style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (sent && e.sourceUri != null && e.status != "COMPLETED" && e.status != "IN_PROGRESS") {
                    OutlinedButton(onClick = onResume) { Text(if (e.status == "INTERRUPTED") "Resume" else "Retry") }
                }
                if (!sent && e.status == "INTERRUPTED") OutlinedButton(onClick = onReceive) { Text("Wait for sender") }
                TextButton(onClick = onDelete) { Text("Delete") }
            }
        }
    }
}

/** Real measurements from this phone's sent transfers. Nothing here is estimated or pre-filled. */
@Composable
private fun PerformanceCard(all: List<TransferEntity>) {
    val sends = all.filter { it.direction == "SEND" }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Measured performance (this phone, sent files)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            if (sends.isEmpty()) {
                Text("No data yet. Send files to see real measurements here. Use Export CSV for your report.", style = MaterialTheme.typography.bodySmall)
            }
            for (t in TransportType.values()) {
                val s = sends.filter { it.transport == t.name }
                if (s.isEmpty()) continue
                val done = s.filter { it.status == "COMPLETED" }
                val bad = s.filter { it.status == "FAILED" || it.status == "INTERRUPTED" }
                val attempts = done.size + bad.size
                val rate = if (attempts == 0) "-" else "${done.size * 100 / attempts}%"
                val timed = done.filter { it.throughputMBps > 0 }
                val avg = if (timed.isEmpty()) "-" else Format.mbps(timed.map { it.throughputMBps }.average())
                val conn = done.filter { it.connectMs >= 0 }.map { it.connectMs.toDouble() }
                val avgConn = if (conn.isEmpty()) "-" else Format.duration(conn.average().toLong())
                val resumed = s.filter { it.resumeCount > 0 }
                val resumedOk = resumed.count { it.status == "COMPLETED" }
                val integrity = s.count { it.integrityFailure }
                Text(t.label, style = MaterialTheme.typography.titleSmall)
                Text("Completed ${done.size} of $attempts attempts ($rate)  -  average speed $avg  -  average connect time $avgConn", style = MaterialTheme.typography.bodySmall)
                Text("Resumed transfers that completed: $resumedOk of ${resumed.size}  -  integrity failures: $integrity", style = MaterialTheme.typography.bodySmall)
                val small = timed.filter { it.size <= 20L * 1024 * 1024 }
                val mid = timed.filter { it.size > 20L * 1024 * 1024 && it.size <= 200L * 1024 * 1024 }
                val big = timed.filter { it.size > 200L * 1024 * 1024 }
                fun bucket(name: String, l: List<TransferEntity>) =
                    if (l.isEmpty()) null else "$name: ${Format.mbps(l.map { it.throughputMBps }.average())} (${l.size} run${if (l.size == 1) "" else "s"})"
                listOfNotNull(bucket("up to 20 MB", small), bucket("20-200 MB", mid), bucket("over 200 MB", big)).forEach {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

// ------------------------------------------------------------------------------------ 10. settings

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val settings = ctx.container().settings
    val name by settings.deviceName.collectAsState()
    val default by settings.defaultTransport.collectAsState()
    val folder by settings.saveTreeUri.collectAsState()
    val keepOn by settings.keepScreenOn.collectAsState()
    var nameField by remember(name) { mutableStateOf(name) }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                ctx.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                settings.setSaveTreeUri(uri.toString())
            } catch (e: Exception) {
                Toast.makeText(ctx, "Could not use that folder: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
    val version = remember {
        try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "1.0" } catch (_: Exception) { "1.0" }
    }

    AppScaffold("Settings", onBack) { padding ->
        Column(
            Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("Device name", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(value = nameField, onValueChange = { nameField = it.take(24) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                label = { Text("Shown to senders") })
            Button(onClick = { settings.setDeviceName(nameField) }, enabled = nameField.trim() != name) { Text("Save name") }

            Text("Preferred transport", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (t in TransportType.values()) {
                    if (t == default) Button(onClick = { settings.setDefaultTransport(t) }) { Text(t.label) }
                    else OutlinedButton(onClick = { settings.setDefaultTransport(t) }) { Text(t.label) }
                }
            }

            Text("Where received files are saved", style = MaterialTheme.typography.titleMedium)
            Text(folder?.let { Uri.decode(it) } ?: "Downloads/DropSecure (default)", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { folderPicker.launch(null) }) { Text("Choose folder") }
                if (folder != null) TextButton(onClick = { settings.setSaveTreeUri(null) }) { Text("Use default") }
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text("Keep screen on during transfers")
                Switch(checked = keepOn, onCheckedChange = { settings.setKeepScreenOn(it) })
            }

            Text("About DropSecure", style = MaterialTheme.typography.titleMedium)
            Text("Version $version", style = MaterialTheme.typography.bodyMedium)
            Text("Developed by B. Charan Kumar Reddy", style = MaterialTheme.typography.bodyMedium)
            Text("For queries and suggestions contact charankumarreddybantrothula@gmail.com or 9652233592", style = MaterialTheme.typography.bodyMedium)
        }
    }
}
