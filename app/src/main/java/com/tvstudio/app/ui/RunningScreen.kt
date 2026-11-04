package com.tvstudio.app.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.tvstudio.app.UiState

@Composable
fun RunningScreen(state: UiState) {
    Page("Working on your TV") {
        BusyNotice(state.busy)
        if (state.progressTotal > 0) {
            LinearProgressIndicator(
                progress = { state.progressDone.toFloat() / state.progressTotal },
                modifier = Modifier.fillMaxWidth(),
            )
            Text("${state.progressDone} of ${state.progressTotal}")
        }
        Dim("Keep this screen open and don't turn off the TV.")
    }
}
