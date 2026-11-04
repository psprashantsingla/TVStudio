package com.tvstudio.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tvstudio.app.AppViewModel
import com.tvstudio.app.UiState
import com.tvstudio.core.ReviewItem

@Composable
fun ReviewScreen(state: UiState, model: AppViewModel) {
    var confirming by remember { mutableStateOf(false) }
    val count = state.checked.size
    val groups = state.reviewItems.filter { it.lockedBecause == null }.groupBy { it.category }
    val locked = state.reviewItems.filter { it.lockedBecause != null }

    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item {
                Text(state.reviewTitle, Modifier.padding(top = 20.dp), style = MaterialTheme.typography.headlineSmall)
            }
            if (state.reviewNotes.isNotEmpty()) {
                item { Notice(state.reviewNotes.joinToString("\n")) }
            }
            if (state.reviewItems.isEmpty()) {
                item { Notice("None of the apps on this list are switched on on this TV.") }
            }
            groups.forEach { (category, items) ->
                item { Column(Modifier.padding(top = 12.dp)) { SectionTitle(category.title) } }
                items(items, key = { it.pkg }) { row ->
                    PackageRow(row, row.pkg in state.checked) { model.toggle(row) }
                }
            }
            if (locked.isNotEmpty()) {
                item {
                    Column(Modifier.padding(top = 12.dp)) {
                        SectionTitle("Kept on")
                        Dim("TVStudio won't disable these on this TV.")
                    }
                }
                items(locked, key = { it.pkg }) { row ->
                    PackageRow(row, checked = false, onClick = {})
                }
            }
            item { Column(Modifier.padding(bottom = 8.dp)) {} }
        }
        Column(Modifier.padding(20.dp)) {
            ErrorNotice(state.error)
            Button(onClick = { confirming = true }, enabled = count > 0, modifier = Modifier.fillMaxWidth()) {
                Text(if (count == 1) "Disable 1 app" else "Disable $count apps")
            }
        }
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(if (count == 1) "Disable 1 app?" else "Disable $count apps?") },
            text = { Text("TVStudio saves their current state first, so you can undo this. Keep this screen open while it runs.") },
            confirmButton = {
                Button(onClick = { confirming = false; model.apply() }) { Text("Disable") }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun PackageRow(item: ReviewItem, checked: Boolean, onClick: () -> Unit) {
    val locked = item.lockedBecause
    val rowModifier = if (locked == null) Modifier.clickable(onClick = onClick) else Modifier
    Row(rowModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = if (locked == null) ({ onClick() }) else null, enabled = locked == null)
        Column(Modifier.padding(start = 8.dp)) {
            Text(item.pkg, style = MaterialTheme.typography.bodyMedium)
            val detail = locked?.message ?: item.note
            if (detail != null) Dim(detail)
        }
    }
}
