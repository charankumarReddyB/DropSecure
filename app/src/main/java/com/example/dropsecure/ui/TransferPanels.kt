package com.example.dropsecure.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.dropsecure.manager.Direction
import com.example.dropsecure.manager.JobUi
import com.example.dropsecure.manager.Stage
import com.example.dropsecure.manager.TransferManager
import com.example.dropsecure.util.Format

/** One transfer, drawn according to its stage. Used on the send flow and the receive screen. */
@Composable
fun JobCard(job: JobUi, manager: TransferManager) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val verb = if (job.direction == Direction.SEND) "To" else "From"
            Text(
                if (job.fileName.isNotEmpty()) job.fileName else if (job.direction == Direction.SEND) "Sending file" else "Incoming transfer",
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold
            )
            Text("$verb ${job.peerName} - ${job.transport.label}" + if (job.totalBytes > 0) " - ${Format.bytes(job.totalBytes)}" else "",
                style = MaterialTheme.typography.bodySmall)

            when {
                job.stage == Stage.VERIFY_CODE -> PairingPanel(job, manager)
                job.offerPending -> ReceiveRequestPanel(job, manager)
                job.stage.finished -> ResultPanel(job, manager)
                job.stage == Stage.TRANSFERRING -> ProgressPanel(job, manager)
                else -> WaitingPanel(job, manager)
            }
        }
    }
}

/** Screen: secure pairing. Both users must compare the code before any file data is sent. */
@Composable
fun PairingPanel(job: JobUi, manager: TransferManager) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        Text("Verify the connection", style = MaterialTheme.typography.titleSmall)
        Text(
            job.sas ?: "------",
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
            fontSize = 44.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            "Look at the other phone. It must show exactly the same code. If the codes are different, " +
                "someone may be intercepting the connection: tap \"Doesn't match\".",
            style = MaterialTheme.typography.bodyMedium
        )
        if (job.codeConfirmed) {
            Text("Code confirmed. Waiting for the other phone...", style = MaterialTheme.typography.bodyMedium)
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = { manager.confirmCode(job.jobId, true) }, modifier = Modifier.weight(1f)) { Text("Codes match") }
                OutlinedButton(
                    onClick = { manager.confirmCode(job.jobId, false) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("Doesn't match") }
            }
        }
    }
}

/** Screen: incoming file request with Accept / Reject. */
@Composable
fun ReceiveRequestPanel(job: JobUi, manager: TransferManager) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        Text("${job.peerName} wants to send you a file", style = MaterialTheme.typography.titleSmall)
        Text("Name: ${job.fileName}", style = MaterialTheme.typography.bodyMedium)
        Text("Size: ${Format.bytes(job.totalBytes)}", style = MaterialTheme.typography.bodyMedium)
        if (job.resuming) Text("This continues an interrupted transfer.", style = MaterialTheme.typography.bodyMedium)
        Text("Only accept files from people you know. The file is checked (SHA-256) before it is saved.", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = { manager.respondToOffer(job.jobId, true) }, modifier = Modifier.weight(1f)) { Text("Accept") }
            OutlinedButton(onClick = { manager.respondToOffer(job.jobId, false) }, modifier = Modifier.weight(1f)) { Text("Reject") }
        }
    }
}

/** Screen: transfer progress with speed and (only when reliable) remaining time. */
@Composable
fun ProgressPanel(job: JobUi, manager: TransferManager) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        LinearProgressIndicator(progress = { job.progress }, modifier = Modifier.fillMaxWidth())
        Text(
            "${Format.bytes(job.bytesDone)} of ${Format.bytes(job.totalBytes)}  (${(job.progress * 100).toInt()}%)",
            style = MaterialTheme.typography.bodyMedium
        )
        Text("Speed: ${Format.speed(job.speedBps)}   ${Format.eta(job.etaSeconds)}", style = MaterialTheme.typography.bodySmall)
        Text("Encrypted with AES-256-GCM", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { manager.cancel(job.jobId) }, modifier = Modifier.fillMaxWidth()) { Text("Cancel transfer") }
    }
}

@Composable
fun WaitingPanel(job: JobUi, manager: TransferManager) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Text(job.message ?: stageText(job.stage), style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = { manager.cancel(job.jobId) }) { Text("Cancel") }
    }
}

/** Screen: transfer completed / failed / cancelled. */
@Composable
fun ResultPanel(job: JobUi, manager: TransferManager) {
    val ok = job.stage == Stage.DONE
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        Text(
            when (job.stage) {
                Stage.DONE -> if (job.direction == Direction.SEND) "Sent and verified" else "Received and verified"
                Stage.CANCELLED -> "Cancelled"
                Stage.REJECTED -> "Declined"
                Stage.INTERRUPTED -> "Interrupted"
                else -> "Failed"
            },
            style = MaterialTheme.typography.titleMedium,
            color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
        )
        job.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        if (ok) {
            Text("The SHA-256 hash of the received file matches the sender's.", style = MaterialTheme.typography.bodySmall)
            job.savedTo?.let { Text("Saved to: $it", style = MaterialTheme.typography.bodySmall) }
            if (job.durationMs != null && job.throughputMBps != null) {
                Text("Transfer time ${Format.duration(job.durationMs)} - average ${Format.mbps(job.throughputMBps)}", style = MaterialTheme.typography.bodySmall)
            }
        } else if (job.stage == Stage.INTERRUPTED || job.stage == Stage.FAILED) {
            Text("Progress is saved. Open Transfer History to resume.", style = MaterialTheme.typography.bodySmall)
        }
        TextButton(onClick = { manager.dismiss(job.jobId) }) { Text("Dismiss") }
    }
}

private fun stageText(s: Stage) = when (s) {
    Stage.QUEUED -> "Waiting in queue..."
    Stage.CONNECTING -> "Connecting..."
    Stage.KEY_EXCHANGE -> "Exchanging keys..."
    Stage.AWAITING_ACCEPT -> "Waiting for the other phone..."
    else -> ""
}
