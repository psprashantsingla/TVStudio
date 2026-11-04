package com.tvstudio.app.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import com.tvstudio.app.AppViewModel
import com.tvstudio.app.UiState

@Composable
fun ConnectScreen(state: UiState, model: AppViewModel) {
    var address by rememberSaveable { mutableStateOf("") }
    val idle = state.busy == null

    Page("Find your TV") {
        OutlinedButton(onClick = model::scanNetwork, enabled = idle && !state.scanning, modifier = Modifier.fillMaxWidth()) {
            Text(if (state.scanning) "Searching your Wi-Fi network" else "Search my Wi-Fi network")
        }
        state.scanResults?.let { results ->
            if (results.isEmpty()) {
                Notice("No device with debugging on was found. Check the TV's debugging setting, or enter its address below.")
            }
            results.forEach { host ->
                Button(onClick = { address = host; model.connect(host) }, enabled = idle, modifier = Modifier.fillMaxWidth()) {
                    Text("Connect to $host")
                }
            }
        }

        SectionTitle("Or enter the address")
        Dim("You'll find it in the TV's Settings, under Network.")
        OutlinedTextField(
            value = address,
            onValueChange = { address = it },
            label = { Text("TV address, like 192.168.1.50") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = { model.connect(address) }, enabled = idle && address.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
            Text("Connect")
        }
        BusyNotice(state.busy)
        ErrorNotice(state.error)
    }
}
