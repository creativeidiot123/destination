package com.ankit.destination.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun BulkPackageInputDialog(
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit
) {
    var text by remember { mutableStateOf("") }
    val parsed by remember(text) {
        derivedStateOf {
            text.lines()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .toSet()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Paste package list") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp),
                    label = { Text("Package names, one per line") },
                    placeholder = {
                        Text(
                            "com.example.app1\ncom.example.app2\ncom.example.app3",
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                    },
                    singleLine = false,
                    maxLines = 50
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "${parsed.size} package(s) detected",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(parsed) },
                enabled = parsed.isNotEmpty()
            ) {
                Text("Import")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
