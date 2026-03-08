package com.ankit.destination.ui.components

import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ankit.destination.ui.AppOption
import com.ankit.destination.ui.buildSearchText
import com.ankit.destination.ui.normalizeSearchQuery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun AppPickerDialog(
    title: String,
    options: List<AppOption>,
    selectedPackageNames: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var selected by remember(selectedPackageNames) { mutableStateOf(selectedPackageNames) }

    val searchableOptions = remember(options) {
        options.map { option ->
            SearchableAppOption(
                option = option,
                normalizedSearchText = buildSearchText(option.label, option.packageName)
            )
        }
    }

    val filtered by produceState(initialValue = options, searchableOptions, query) {
        val normalizedQuery = normalizeSearchQuery(query)
        if (normalizedQuery.isBlank()) {
            value = searchableOptions.map(SearchableAppOption::option)
        } else {
            value = withContext(Dispatchers.Default) {
                searchableOptions
                    .filter { option ->
                        option.normalizedSearchText.contains(normalizedQuery)
                    }
                    .map(SearchableAppOption::option)
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Search apps") }
                )
                Spacer(modifier = Modifier.height(16.dp))
                LazyColumn(
                    modifier = Modifier.height(360.dp)
                ) {
                    if (filtered.isEmpty()) {
                        item(contentType = "empty_state") {
                            Text(
                                text = "No matching apps",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.padding(top = 16.dp)
                            )
                        }
                    } else {
                        items(filtered, key = { it.packageName }, contentType = { "app_option" }) { option ->
                            val isSelectable = option.isSelectable
                            val isChecked = selected.contains(option.packageName)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .toggleable(
                                        value = isChecked,
                                        enabled = isSelectable,
                                        role = Role.Checkbox
                                    ) { checked ->
                                        selected = if (checked) {
                                            selected + option.packageName
                                        } else {
                                            selected - option.packageName
                                        }
                                    }
                                    .alpha(if (isSelectable) 1f else 0.45f)
                                    .padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = isChecked,
                                    onCheckedChange = null, // Handled by Row click
                                    enabled = isSelectable,
                                    modifier = Modifier.padding(end = 8.dp)
                                )
                                InstalledAppIcon(
                                    packageName = option.packageName,
                                    modifier = Modifier.size(36.dp),
                                    contentDescription = null
                                )
                                Spacer(modifier = Modifier.width(16.dp))
                                Column {
                                    Text(
                                        text = option.label,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = option.packageName,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                    option.supportingTag?.let { tag ->
                                        Text(
                                            text = tag,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selected) }) {
                Text("Apply")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

private data class SearchableAppOption(
    val option: AppOption,
    val normalizedSearchText: String
)
