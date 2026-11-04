package com.tvstudio.app.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tvstudio.app.AppViewModel
import com.tvstudio.app.Screen

@Composable
fun SetupScreen(model: AppViewModel) {
    var showNotices by remember { mutableStateOf(false) }

    Page("Clean up your Android TV") {
        Text("TVStudio turns off a TV's preloaded tracking and promotional apps from your phone. Nothing is uninstalled, and you can undo it.")

        SectionTitle("One setup step on the TV")
        Text("1. Open Settings > Device Preferences > About.")
        Text("2. Select Build seven times, until it says you're a developer.")
        Text("3. Go back, then open Developer options.")
        Text("4. Turn on USB debugging, and Network or ADB debugging if the TV has it.")
        Text("5. Connect your phone to the same Wi-Fi as the TV.")
        Dim("Menu names vary by brand.")

        SectionTitle("Before you start")
        Text("This works on Android TV and Google TV. Samsung, LG, Roku and Vizio TVs don't support it.")
        Text("Disabling apps carries some risk. TVStudio keeps the apps a TV needs, but it can't test every TV or firmware, and a TV could stop working properly.")
        Text("Ads in the home screen may remain, because they're often part of the TV's own launcher, which this version never disables.")

        Button(onClick = { model.goTo(Screen.CONNECT) }, modifier = Modifier.fillMaxWidth()) {
            Text("I've done this. Find my TV")
        }
        TextButton(onClick = { showNotices = true }) { Text("Credits and licences") }
    }

    if (showNotices) {
        AlertDialog(
            onDismissRequest = { showNotices = false },
            confirmButton = { TextButton(onClick = { showNotices = false }) { Text("Close") } },
            title = { Text("Credits and licences") },
            text = {
                Text(
                    model.notices,
                    Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    style = MaterialTheme.typography.bodySmall,
                )
            },
        )
    }
}
