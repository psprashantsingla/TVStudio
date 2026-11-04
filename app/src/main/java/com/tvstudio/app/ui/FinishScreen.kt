package com.tvstudio.app.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.tvstudio.app.AppViewModel
import com.tvstudio.app.UiState
import com.tvstudio.core.DebloatReport
import com.tvstudio.core.PortStatus
import com.tvstudio.core.RestoreReport

@Composable
fun FinishScreen(state: UiState, model: AppViewModel) {
    val idle = state.busy == null
    val fullWidth = Modifier.fillMaxWidth()

    Page(if (state.restoreReport != null) "Undo finished" else "Finish up") {
        state.report?.let { Results(it, state) }
        state.restoreReport?.let { RestoreResults(it) }
        BusyNotice(state.busy)
        ErrorNotice(state.error)

        if (state.report != null) {
            SectionTitle("1. Reboot and test")
            Dim("This saves the changes first. A reboot shows whether the TV's firmware switches anything back on.")
            if (!state.rebootSent) {
                Button(onClick = model::saveAndReboot, enabled = idle, modifier = fullWidth) { Text("Save and reboot TV") }
            } else {
                Text("Rebooting. When the TV is back, use the remote to test Home, an HDMI input, Settings, the remote's buttons and Wi-Fi.")
                OutlinedButton(onClick = model::recheck, enabled = idle, modifier = fullWidth) { Text("Reconnect and re-check") }
            }
            state.cameBack?.let { list ->
                if (list.isEmpty()) {
                    Notice("Everything TVStudio disabled is still off.")
                } else {
                    Notice("The TV switched these back on, so its firmware probably re-enables them at boot:\n" + list.joinToString("\n"))
                }
            }
        }

        if (state.hasRestoreRecord) {
            OutlinedButton(onClick = model::restore, enabled = idle, modifier = fullWidth) { Text("Undo everything") }
        }

        SectionTitle(if (state.report != null) "2. Turn debugging off" else "Turn debugging off")
        ErrorNotice(
            "While debugging is on, this TV's debugging port is unencrypted and open on your network. " +
                "Do this when you're happy with the result.",
        )
        Text("On the TV, open Developer options and turn off USB debugging and any Network, ADB or Wireless debugging option. Then reboot the TV.")
        OutlinedButton(onClick = model::checkPortClosed, enabled = idle, modifier = fullWidth) { Text("Check that the port is closed") }
        when (state.portStatus) {
            PortStatus.CLOSED -> Notice("The port is closed.")
            PortStatus.OPEN -> ErrorNotice("The port is still open. Check every debugging option, reboot the TV and check again.")
            PortStatus.UNKNOWN -> ErrorNotice("The TV didn't answer, so this doesn't show whether the port is closed. Make sure the TV is on and on the same Wi-Fi, then check again.")
            null -> {}
        }
        if (state.portStatus == PortStatus.CLOSED) {
            Button(onClick = model::startOver, enabled = idle, modifier = fullWidth) { Text("Done") }
        } else {
            TextButton(onClick = model::startOver, enabled = idle, modifier = fullWidth) { Text("Leave without checking (not recommended)") }
        }
    }
}

@Composable
private fun Results(report: DebloatReport, state: UiState) {
    val style = MaterialTheme.typography.titleMedium
    Text(if (report.disabled.size == 1) "1 app disabled" else "${report.disabled.size} apps disabled", style = style)
    report.stoppedBecause?.let { ErrorNotice(it) }
    if (report.failed.isNotEmpty()) {
        Notice("The TV kept these on:\n" + report.failed.keys.joinToString("\n"))
    }
    if (report.unverified.isNotEmpty()) {
        Notice("Couldn't confirm these before the connection dropped:\n" + report.unverified.joinToString("\n"))
    }
    if (report.notAttempted.isNotEmpty()) {
        Dim("Not attempted: ${report.notAttempted.size}")
    }
    if (state.skipped.isNotEmpty()) {
        Notice("Kept on after a final safety check:\n" + state.skipped.entries.joinToString("\n") { "${it.key}: ${it.value.message}" })
    }
    state.warnings.forEach { Notice(it) }
}

@Composable
private fun RestoreResults(report: RestoreReport) {
    if (report.failed.isEmpty()) {
        Notice(if (report.restored.size == 1) "1 app is back on." else "${report.restored.size} apps are back on.")
    } else {
        ErrorNotice("These are still off. Try Undo again:\n" + report.failed.joinToString("\n"))
    }
    if (report.leftAlone.isNotEmpty()) {
        Dim("${report.leftAlone.size} were already off before TVStudio ran, so they stay off.")
    }
}
