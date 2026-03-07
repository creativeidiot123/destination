package com.ankit.destination.ui.diagnostics

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ankit.destination.policy.DevicePolicyFacade
import com.ankit.destination.policy.PolicyEngine
import com.ankit.destination.security.AppLockManager
import com.ankit.destination.ui.components.AdminSessionBanner
import com.ankit.destination.ui.components.AdminSessionDialog
import com.ankit.destination.ui.components.collectAsStateWithLifecycleCompat
import java.text.DateFormat
import java.util.Date

private enum class DangerConfirmAction {
    RESET_APP,
    REMOVE_DEVICE_OWNER
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen() {
    val context = LocalContext.current
    val policyEngine = remember(context) { PolicyEngine(context.applicationContext) }
    val appLockManager = remember(context) { AppLockManager(context) }
    val viewModel: DiagnosticsViewModel = viewModel(
        factory = DiagnosticsViewModelFactory(
            context.applicationContext,
            policyEngine,
            appLockManager
        )
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycleCompat()
    var confirmAction by remember { mutableStateOf<DangerConfirmAction?>(null) }

    LaunchedEffect(Unit) {
        viewModel.refresh()
    }

    if (uiState.showAuthDialog) {
        AdminSessionDialog(
            onDismiss = { viewModel.dismissAuthDialog() },
            onAuthenticated = { _ -> viewModel.onAuthenticated() },
            appLockManager = appLockManager
        )
    }

    confirmAction?.let { action ->
        var confirmationText by remember(action) { mutableStateOf("") }
        var password by remember(action) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { confirmAction = null },
            title = {
                Text(if (action == DangerConfirmAction.RESET_APP) "Reset app state" else "Remove device owner")
            },
            text = {
                if (action == DangerConfirmAction.RESET_APP) {
                    Text(
                        "This clears Destination policies, schedules, mappings, emergency state, and stored diagnostics."
                    )
                } else {
                    Column {
                        Text(
                            "This removes Destination as device owner. On many consumer devices that can trigger or require a factory reset."
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            "Type: $REMOVE_DEVICE_OWNER_CONFIRMATION_TEXT",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(
                            value = confirmationText,
                            onValueChange = { confirmationText = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Confirmation phrase") }
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Password") },
                            visualTransformation = PasswordVisualTransformation()
                        )
                    }
                }
            },
            confirmButton = {
                if (action == DangerConfirmAction.RESET_APP) {
                    TextButton(
                        onClick = {
                            confirmAction = null
                            viewModel.requestResetApp()
                        }
                    ) {
                        Text("Continue")
                    }
                } else {
                    TextButton(
                        onClick = {
                            confirmAction = null
                            viewModel.confirmRemoveDeviceOwner(confirmationText, password)
                        },
                        enabled = confirmationText.trim() == REMOVE_DEVICE_OWNER_CONFIRMATION_TEXT && password.isNotBlank()
                    ) {
                        Text("Remove owner")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmAction = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Diagnostics") },
                actions = {
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            AdminSessionBanner(remainingMs = uiState.adminSessionRemainingMs)

            LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                item {
                    Text(
                        "Engine Snapshot",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    val snapshot = uiState.snapshot
                    if (uiState.isLoading && snapshot == null) {
                        Text("Loading diagnostics...", modifier = Modifier.padding(16.dp))
                    } else if (snapshot != null) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
                        ) {
                            DiagnosticTile(
                                icon = Icons.Default.Info,
                                label = "Last applied",
                                value = formatTime(snapshot.lastAppliedAtMs),
                                color = MaterialTheme.colorScheme.primary
                            )
                            DiagnosticTile(
                                icon = Icons.Default.Warning,
                                label = "Last error",
                                value = snapshot.lastError ?: "None",
                                color = if (snapshot.lastError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                            )
                            DiagnosticTile(
                                icon = Icons.Default.Schedule,
                                label = "Schedule",
                                value = snapshot.scheduleLockReason ?: "Inactive",
                                color = MaterialTheme.colorScheme.tertiary
                            )
                            DiagnosticTile(
                                icon = Icons.Default.VpnKey,
                                label = "VPN active",
                                value = snapshot.vpnActive.toString(),
                                color = if (snapshot.vpnActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                            )
                            DiagnosticTile(
                                icon = Icons.Default.Dns,
                                label = "Private DNS",
                                value = buildString {
                                    append(DevicePolicyFacade.privateDnsModeLabel(snapshot.privateDnsMode))
                                    if (!snapshot.privateDnsHost.isNullOrBlank()) {
                                        append("\n(${snapshot.privateDnsHost})")
                                    }
                                },
                                color = MaterialTheme.colorScheme.secondary
                            )
                            DiagnosticTile(
                                icon = Icons.Default.Block,
                                label = "Blocked items",
                                value = "${snapshot.scheduleBlockedGroups.size} G, ${snapshot.budgetBlockedPackages.size} A, ${snapshot.domainRuleCount} D",
                                color = MaterialTheme.colorScheme.error
                            )
                            DiagnosticTile(
                                icon = Icons.Default.Warning,
                                label = "Usage access",
                                value = snapshot.usageAccessGranted.toString(),
                                color = if (snapshot.usageAccessGranted) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.error
                                }
                            )
                            DiagnosticTile(
                                icon = Icons.Default.Security,
                                label = "Recovery lockdown",
                                value = snapshot.usageAccessRecoveryLockdownActive.toString(),
                                color = if (snapshot.usageAccessRecoveryLockdownActive) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.primary
                                }
                            )
                            DiagnosticTile(
                                icon = Icons.Default.Refresh,
                                label = "Usage check",
                                value = formatTime(snapshot.lastUsageAccessCheckAtMs),
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }
                }

                uiState.snapshot?.takeIf { it.usageAccessRecoveryLockdownActive || !it.usageAccessGranted }?.let { snapshot ->
                    item {
                        ElevatedCard(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                            colors = CardDefaults.elevatedCardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer
                            )
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = "Usage Access Recovery",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = snapshot.usageAccessRecoveryReason
                                        ?: "Usage Access is currently unavailable.",
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                                if (snapshot.usageAccessRecoveryAllowlist.isNotEmpty()) {
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Text(
                                        text = "Recovery allowlist: ${snapshot.usageAccessRecoveryAllowlist.sorted().joinToString()}",
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                }
                            }
                        }
                    }
                }

                uiState.statusMessage?.let { message ->
                    item {
                        ElevatedCard(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                            colors = CardDefaults.elevatedCardColors(
                                containerColor = if (uiState.isError) {
                                    MaterialTheme.colorScheme.errorContainer
                                } else {
                                    MaterialTheme.colorScheme.secondaryContainer
                                }
                            )
                        ) {
                            Text(
                                text = message,
                                modifier = Modifier.padding(16.dp),
                                color = if (uiState.isError) {
                                    MaterialTheme.colorScheme.onErrorContainer
                                } else {
                                    MaterialTheme.colorScheme.onSecondaryContainer
                                }
                            )
                        }
                    }
                }

                item {
                    Text(
                        "Danger Zone",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    ElevatedCard(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                    ) {
                        Column(modifier = Modifier.padding(20.dp)) {
                            Text(
                                "Recovery and destructive actions",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "Use these only when you need to wipe app policy state or fully unenroll the device.",
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Row(modifier = Modifier.fillMaxWidth()) {
                                Button(onClick = { confirmAction = DangerConfirmAction.RESET_APP }) {
                                    Icon(Icons.Default.DeleteForever, contentDescription = null)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Reset app")
                                }
                                Spacer(modifier = Modifier.weight(1f))
                                Button(onClick = { confirmAction = DangerConfirmAction.REMOVE_DEVICE_OWNER }) {
                                    Icon(Icons.Default.Warning, contentDescription = null)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Remove owner")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DiagnosticTile(icon: ImageVector, label: String, value: String, color: Color) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.width(160.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 12.dp)) {
                Box(modifier = Modifier.size(32.dp).background(color.copy(alpha = 0.2f), CircleShape), contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
                }
            }
            Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            Spacer(modifier = Modifier.height(4.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun formatTime(epochMs: Long): String {
    if (epochMs <= 0L) return "Never"
    return DateFormat.getDateTimeInstance().format(Date(epochMs))
}
