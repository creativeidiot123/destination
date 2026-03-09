package com.ankit.destination.ui.diagnostics

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
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
import androidx.compose.material3.Switch
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
import com.ankit.destination.policy.PackageDiagnostics
import com.ankit.destination.policy.PackageDiagnosticsDisposition
import com.ankit.destination.policy.PolicyEngine
import com.ankit.destination.policy.UsageSnapshotStatus
import com.ankit.destination.security.AppLockManager
import com.ankit.destination.ui.components.AdminSessionBanner
import com.ankit.destination.ui.components.AdminSessionDialog
import com.ankit.destination.ui.components.AppPickerDialog
import com.ankit.destination.ui.components.collectAsStateWithLifecycleCompat
import java.text.DateFormat
import java.util.Date

private enum class DangerConfirmAction {
    RESET_APP,
    REMOVE_DEVICE_OWNER
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(
    policyEngine: PolicyEngine,
    appLockManager: AppLockManager
) {
    val context = LocalContext.current
    val viewModel: DiagnosticsViewModel = viewModel(
        factory = DiagnosticsViewModelFactory(
            context.applicationContext,
            policyEngine,
            appLockManager
        )
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycleCompat()
    var confirmAction by remember { mutableStateOf<DangerConfirmAction?>(null) }
    var showHiddenPicker by remember { mutableStateOf(false) }
    val exportBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let(viewModel::exportBackup)
    }
    val importBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let(viewModel::importBackup)
    }

    LaunchedEffect(Unit) {
        viewModel.refresh(preserveStatusMessage = false)
    }

    if (uiState.showAuthDialog) {
        AdminSessionDialog(
            onDismiss = { viewModel.dismissAuthDialog() },
            onAuthenticated = { _ -> viewModel.onAuthenticated() },
            appLockManager = appLockManager
        )
    }

    if (showHiddenPicker) {
        AppPickerDialog(
            title = "Add hidden apps",
            options = uiState.availableHiddenAppOptions,
            selectedPackageNames = emptySet(),
            onDismiss = { showHiddenPicker = false },
            onConfirm = { selected ->
                showHiddenPicker = false
                viewModel.addHiddenApps(selected)
            }
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
                    IconButton(
                        onClick = { viewModel.refresh(preserveStatusMessage = false) },
                        enabled = !uiState.isLoading
                    ) {
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
                                icon = Icons.Default.Block,
                                label = "Suspend backend",
                                value = snapshot.packageSuspendBackend ?: "Unknown",
                                color = when (snapshot.packageSuspendBackend) {
                                    "HIDDEN" -> MaterialTheme.colorScheme.primary
                                    "DPM_FALLBACK" -> MaterialTheme.colorScheme.tertiary
                                    else -> MaterialTheme.colorScheme.secondary
                                }
                            )
                            DiagnosticTile(
                                icon = Icons.Default.Warning,
                                label = "Suspend prototype",
                                value = snapshot.packageSuspendPrototypeError ?: "No prototype error",
                                color = if (snapshot.packageSuspendPrototypeError != null) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.primary
                                }
                            )
                            DiagnosticTile(
                                icon = Icons.Default.Schedule,
                                label = "Schedule",
                                value = snapshot.scheduleTargetWarning ?: (snapshot.scheduleLockReason ?: "Inactive"),
                                color = if (snapshot.scheduleTargetWarning != null) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.tertiary
                                }
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
                                value = snapshot.usageSnapshotStatus.name,
                                color = when (snapshot.usageSnapshotStatus) {
                                    UsageSnapshotStatus.OK -> MaterialTheme.colorScheme.primary
                                    UsageSnapshotStatus.ACCESS_MISSING -> MaterialTheme.colorScheme.error
                                    UsageSnapshotStatus.INGESTION_FAILED -> MaterialTheme.colorScheme.tertiary
                                }
                            )
                            DiagnosticTile(
                                icon = Icons.Default.Security,
                                label = "Accessibility",
                                value = "${snapshot.accessibilityServiceEnabled}/${snapshot.accessibilityServiceRunning}",
                                color = if (snapshot.accessibilityServiceEnabled && snapshot.accessibilityServiceRunning) {
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

                uiState.snapshot?.takeIf { !it.accessibilityServiceEnabled || !it.accessibilityServiceRunning }?.let { snapshot ->
                    item {
                        ElevatedCard(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                            colors = CardDefaults.elevatedCardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer
                            )
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = "Accessibility Recovery",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = snapshot.accessibilityDegradedReason
                                        ?: "Accessibility is currently unavailable.",
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                                snapshot.nextPolicyWakeAtMs?.let { nextWakeAt ->
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = "Next wake: ${snapshot.nextPolicyWakeReason ?: "policy"} @ ${formatTime(nextWakeAt)}",
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                }
                            }
                        }
                    }
                }

                uiState.snapshot?.takeIf {
                    it.usageAccessRecoveryLockdownActive || it.usageSnapshotStatus != UsageSnapshotStatus.OK
                }?.let { snapshot ->
                    item {
                        ElevatedCard(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                            colors = CardDefaults.elevatedCardColors(
                                containerColor = if (snapshot.usageSnapshotStatus == UsageSnapshotStatus.INGESTION_FAILED) {
                                    MaterialTheme.colorScheme.tertiaryContainer
                                } else {
                                    MaterialTheme.colorScheme.errorContainer
                                }
                            )
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = if (snapshot.usageSnapshotStatus == UsageSnapshotStatus.INGESTION_FAILED) {
                                        "Usage Data Stale"
                                    } else {
                                        "Usage Access Recovery"
                                    },
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = if (snapshot.usageSnapshotStatus == UsageSnapshotStatus.INGESTION_FAILED) {
                                        MaterialTheme.colorScheme.onTertiaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onErrorContainer
                                    }
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = snapshot.usageAccessRecoveryReason
                                        ?: if (snapshot.usageSnapshotStatus == UsageSnapshotStatus.INGESTION_FAILED) {
                                            "Usage ingestion failed. Last known usage remains in effect until refresh succeeds."
                                        } else {
                                            "Usage Access is currently unavailable."
                                        },
                                    color = if (snapshot.usageSnapshotStatus == UsageSnapshotStatus.INGESTION_FAILED) {
                                        MaterialTheme.colorScheme.onTertiaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onErrorContainer
                                    }
                                )
                                if (snapshot.usageAccessRecoveryAllowlist.isNotEmpty()) {
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Text(
                                        text = "Recovery allowlist: ${snapshot.usageAccessRecoveryAllowlist.sorted().joinToString()}",
                                        color = if (snapshot.usageSnapshotStatus == UsageSnapshotStatus.INGESTION_FAILED) {
                                            MaterialTheme.colorScheme.onTertiaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.onErrorContainer
                                        }
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
                    ElevatedCard(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                        colors = CardDefaults.elevatedCardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                        )
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "Hidden Apps",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(onClick = { showHiddenPicker = true }) {
                                    Icon(Icons.Default.Add, contentDescription = null)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Add")
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            if (uiState.hiddenApps.isEmpty()) {
                                Text(
                                    "No hidden apps configured.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                uiState.hiddenApps.forEach { hiddenApp ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                hiddenApp.label,
                                                style = MaterialTheme.typography.titleSmall,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                            Text(
                                                hiddenApp.packageName,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            Text(
                                                if (hiddenApp.locked) {
                                                    "Locked default hidden app"
                                                } else {
                                                    "Excluded from normal policy targeting"
                                                },
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        TextButton(
                                            onClick = { viewModel.removeHiddenApp(hiddenApp.packageName) },
                                            enabled = !hiddenApp.locked
                                        ) {
                                            Text("Remove")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                uiState.snapshot?.let { snapshot ->
                    item {
                        ElevatedCard(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                            colors = CardDefaults.elevatedCardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                            )
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        "Suspend Prototype",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        if (snapshot.hiddenSuspendPrototypeEnabled) {
                                            "Hidden API path is enabled. Suspensions try the custom dialog before DPM fallback."
                                        } else {
                                            "Hidden API path is disabled. Suspensions go directly to the stable DPM path."
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Switch(
                                    checked = snapshot.hiddenSuspendPrototypeEnabled,
                                    onCheckedChange = viewModel::setHiddenSuspendPrototypeEnabled,
                                    enabled = !uiState.isLoading
                                )
                            }
                        }
                    }
                }

                item {
                    ElevatedCard(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                        colors = CardDefaults.elevatedCardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                        )
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                "Backup & Restore",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "Export or restore the full stored app state as a JSON file, including lists, mappings, per-app settings, network controls, and debug state.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Button(
                                    enabled = !uiState.isLoading,
                                    onClick = {
                                        exportBackupLauncher.launch(
                                            "destination-backup-${System.currentTimeMillis()}.json"
                                        )
                                    }
                                ) {
                                    Text("Export backup")
                                }
                                Button(
                                    enabled = !uiState.isLoading,
                                    onClick = {
                                        importBackupLauncher.launch(
                                            arrayOf("application/json", "text/*")
                                        )
                                    }
                                ) {
                                    Text("Import backup")
                                }
                            }
                        }
                    }
                }

                uiState.snapshot?.let { snapshot ->
                    item {
                        Text(
                            "Package Diagnostics",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }
                    items(snapshot.packageDiagnostics, key = { it.packageName }) { diagnostic ->
                        PackageDiagnosticCard(diagnostic = diagnostic)
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

@Composable
private fun PackageDiagnosticCard(diagnostic: PackageDiagnostics) {
    val dispositionColor = when (diagnostic.disposition) {
        PackageDiagnosticsDisposition.SUSPEND_TARGET -> MaterialTheme.colorScheme.error
        PackageDiagnosticsDisposition.ALLOWLIST_EXCLUDED -> MaterialTheme.colorScheme.secondary
        PackageDiagnosticsDisposition.HIDDEN -> MaterialTheme.colorScheme.tertiary
        PackageDiagnosticsDisposition.RUNTIME_EXEMPT -> MaterialTheme.colorScheme.secondary
        PackageDiagnosticsDisposition.PROTECTED -> MaterialTheme.colorScheme.secondary
        PackageDiagnosticsDisposition.ELIGIBLE_NOT_ACTIVE -> MaterialTheme.colorScheme.primary
        PackageDiagnosticsDisposition.NOT_INSTALLED -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    ElevatedCard(
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                diagnostic.packageName,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                diagnostic.disposition.name.replace('_', ' '),
                color = dispositionColor,
                style = MaterialTheme.typography.labelLarge
            )
            diagnostic.primaryReason?.takeIf { it.isNotBlank() }?.let { reason ->
                Spacer(modifier = Modifier.height(6.dp))
                Text("Primary reason: $reason")
            }
            diagnostic.allowlistReason?.takeIf { it.isNotBlank() }?.let { reason ->
                Spacer(modifier = Modifier.height(6.dp))
                Text("All-apps exclusion: $reason")
            }
            diagnostic.protectionReason?.takeIf { it.isNotBlank() }?.let { reason ->
                Spacer(modifier = Modifier.height(6.dp))
                Text("Policy eligibility: $reason")
            }
            if (diagnostic.activeReasons.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    "Active reasons: ${diagnostic.activeReasons.sorted().joinToString()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                diagnostic.nextPotentialClearEvent,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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
