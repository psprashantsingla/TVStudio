package com.tvstudio.app.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.tvstudio.app.AppViewModel
import com.tvstudio.app.UiState
import com.tvstudio.core.MatchStrength
import com.tvstudio.core.ModelEntry

@Composable
fun ChooseScreen(state: UiState, model: AppViewModel) {
    val tv = state.tv ?: return
    val idle = state.busy == null
    val exact = state.matches.filter { it.strength == MatchStrength.MODEL }.map { it.entry }
    val sameBrand = state.matches.filter { it.strength == MatchStrength.BRAND_ONLY }.map { it.entry }.take(8)

    Page("Connected to ${tv.label}") {
        Dim("Android ${tv.androidVersion}, firmware ${tv.firmware}")

        if (tv.adbIsOpenToAnyone) {
            ErrorNotice("This TV's debugging port accepts any device on your network without asking. Be sure to turn debugging off when you finish.")
        }
        BusyNotice(state.busy)
        ErrorNotice(state.error)

        if (state.hasRestoreRecord) {
            Notice("TVStudio has changed this TV before.")
            OutlinedButton(onClick = model::restore, enabled = idle, modifier = Modifier.fillMaxWidth()) {
                Text("Undo earlier changes")
            }
        }

        if (exact.isNotEmpty()) {
            SectionTitle("Lists for your model")
            Dim("Check that the name matches your TV. Lists were tested on specific firmware.")
            exact.forEach { ModelRow(it, idle, model) }
        } else {
            Notice("There's no tested list for this exact model, so check what's on the TV instead, or search below.")
        }

        SectionTitle("Check what's on this TV")
        Dim("Works on any model. You choose everything, and only clear tracking and ad names start ticked.")
        OutlinedButton(onClick = model::scanTv, enabled = idle, modifier = Modifier.fillMaxWidth()) {
            Text("Scan this TV")
        }

        SectionTitle("Search all lists")
        OutlinedTextField(
            value = state.searchQuery,
            onValueChange = model::search,
            label = { Text("Brand or model") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        state.searchResults.forEach { ModelRow(it, idle, model) }

        if (sameBrand.isNotEmpty() && state.searchQuery.isBlank()) {
            SectionTitle("Other ${tv.brand} models")
            Dim("These were tested on different TVs, and may not suit yours.")
            sameBrand.forEach { ModelRow(it, idle, model) }
        }
    }
}

@Composable
private fun ModelRow(entry: ModelEntry, enabled: Boolean, model: AppViewModel) {
    TextButton(onClick = { model.chooseModel(entry) }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Text("${entry.tv}, ${entry.os}".trim(' ', ','), Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyMedium)
    }
}
