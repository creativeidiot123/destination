package com.ankit.destination.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.ankit.destination.security.AppLockManager

@Composable
fun AdminSessionDialog(
    onDismiss: () -> Unit,
    onAuthenticated: (String) -> Unit,
    appLockManager: AppLockManager
) {
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Admin Session") },
        text = {
            Column {
                Text("Enter password to unlock protected settings for 5 minutes.")
                OutlinedTextField(
                    value = password,
                    onValueChange = { 
                        password = it
                        error = false
                    },
                    visualTransformation = PasswordVisualTransformation(),
                    isError = error,
                    modifier = Modifier.padding(top = 16.dp)
                )
                if (error) {
                    Text("Incorrect password", color = androidx.compose.material3.MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                if (appLockManager.verifyPassword(password)) {
                    appLockManager.startAdminSession()
                    onAuthenticated(password)
                } else {
                    error = true
                }
            }) {
                Text("Unlock")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
