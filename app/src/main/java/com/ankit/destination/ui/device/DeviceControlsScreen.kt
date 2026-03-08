package com.ankit.destination.ui.device

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ankit.destination.data.ManagedNetworkModeSetting
import com.ankit.destination.policy.PolicyEngine
import com.ankit.destination.security.AppLockManager
import com.ankit.destination.ui.UiInvalidationBus
import com.ankit.destination.ui.components.AdminSessionBanner
import com.ankit.destination.ui.components.AdminSessionDialog
import com.ankit.destination.ui.components.collectAsStateWithLifecycleCompat

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DeviceControlsScreen(
    policyEngine: PolicyEngine,
    appLockManager: AppLockManager
) {
    val context = LocalContext.current
    val viewModel: DeviceControlsViewModel = viewModel(
        factory = DeviceControlsViewModelFactory(
            context.applicationContext,
            policyEngine,
            appLockManager
        )
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycleCompat()
    val invalidation by UiInvalidationBus.latest.collectAsStateWithLifecycleCompat()

    LaunchedEffect(viewModel, invalidation.version) {
        viewModel.onInvalidation(invalidation.version)
    }

    if (uiState.showAuthDialog) {
        AdminSessionDialog(
            onDismiss = { viewModel.dismissAuthDialog() },
            onAuthenticated = viewModel::onAuthenticated,
            appLockManager = appLockManager
        )
    }

    if (uiState.showSetPasswordDialog) {
        var newPass by remember { mutableStateOf("") }
        var confirmPass by remember { mutableStateOf("") }
        var error by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { viewModel.dismissSetPasswordDialog() },
            title = { Text("Set Password") },
            text = {
                Column {
                    OutlinedTextField(
                        value = newPass,
                        onValueChange = {
                            newPass = it
                            error = false
                        },
                        label = { Text("New password") },
                        visualTransformation = PasswordVisualTransformation()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = confirmPass,
                        onValueChange = {
                            confirmPass = it
                            error = false
                        },
                        label = { Text("Confirm password") },
                        visualTransformation = PasswordVisualTransformation(),
                        isError = error
                    )
                    if (error) {
                        Text("Passwords must match and be at least 4 characters.", color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newPass == confirmPass && newPass.length >= 4) {
                            viewModel.setPassword(newPass)
                        } else {
                            error = true
                        }
                    }
                ) {
                    Text("Enable")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissSetPasswordDialog() }) {
                    Text("Cancel")
                }
            }
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        AdminSessionBanner(remainingMs = uiState.adminSessionRemainingMs)

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 24.dp, bottom = 80.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Text(
                    text = "Overview",
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }

            item {
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    shape = MaterialTheme.shapes.extraLarge
                ) {
                    Column(modifier = Modifier.padding(24.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconWithTint(
                                icon = Icons.Default.Security,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = if (uiState.protectionActive) "Protection active" else "Protection inactive",
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                uiState.currentReason?.let { reason ->
                                    Text(
                                        text = reason,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.84f)
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(20.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            OverviewStat("Blocked groups", uiState.blockedGroups)
                            OverviewStat("Blocked apps", uiState.blockedApps)
                            OverviewStat("Strict groups", uiState.strictGroups)
                        }
                    }
                }
            }

            item {
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text(
                            text = "Managed Network",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "VPN and DNS now live directly on the overview tab.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = uiState.managedNetworkMode == ManagedNetworkModeSetting.UNMANAGED,
                                onClick = { viewModel.updateManagedNetworkMode(ManagedNetworkModeSetting.UNMANAGED) },
                                label = { Text("Unmanaged") }
                            )
                            FilterChip(
                                selected = uiState.managedNetworkMode == ManagedNetworkModeSetting.FORCED_VPN,
                                onClick = { viewModel.updateManagedNetworkMode(ManagedNetworkModeSetting.FORCED_VPN) },
                                label = { Text("Force VPN") }
                            )
                            FilterChip(
                                selected = uiState.managedNetworkMode == ManagedNetworkModeSetting.FORCED_PRIVATE_DNS,
                                onClick = { viewModel.updateManagedNetworkMode(ManagedNetworkModeSetting.FORCED_PRIVATE_DNS) },
                                label = { Text("Force DNS") }
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        when (uiState.managedNetworkMode) {
                            ManagedNetworkModeSetting.UNMANAGED -> {
                                Text(
                                    text = "No VPN or private DNS policy will be enforced.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            ManagedNetworkModeSetting.FORCED_VPN -> {
                                OutlinedTextField(
                                    value = uiState.managedVpnPackage,
                                    onValueChange = viewModel::updateManagedVpnPackage,
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    label = { Text("VPN package") },
                                    leadingIcon = { IconWithTint(Icons.Default.VpnKey, MaterialTheme.colorScheme.primary) }
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = "Enable lockdown",
                                        style = MaterialTheme.typography.bodyLarge,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Switch(
                                        checked = uiState.managedVpnLockdown,
                                        onCheckedChange = viewModel::updateManagedVpnLockdown
                                    )
                                }
                            }
                            ManagedNetworkModeSetting.FORCED_PRIVATE_DNS -> {
                                OutlinedTextField(
                                    value = uiState.privateDnsHost,
                                    onValueChange = viewModel::updatePrivateDnsHost,
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    label = { Text("DNS hostname") },
                                    leadingIcon = { IconWithTint(Icons.Default.Language, MaterialTheme.colorScheme.primary) }
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = { viewModel.saveNetworkSettings() }) {
                            Text("Save network policy")
                        }
                    }
                }
            }

            item {
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = if (uiState.protectionActive) {
                            MaterialTheme.colorScheme.secondaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerLow
                        }
                    )
                ) {
                    ListItem(
                        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                        headlineContent = {
                            Text("Password protection", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        },
                        supportingContent = {
                            Text(
                                if (uiState.protectionActive) {
                                    "Protected settings require an admin session."
                                } else {
                                    "Protection is currently turned off."
                                }
                            )
                        },
                        trailingContent = {
                            Switch(
                                checked = uiState.protectionActive,
                                onCheckedChange = { viewModel.requestProtectionToggle() },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = MaterialTheme.colorScheme.primary,
                                    checkedTrackColor = MaterialTheme.colorScheme.primaryContainer
                                )
                            )
                        }
                    )
                }
            }

            item {
                Text(
                    "Device Restrictions",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
            }

            item {
                DeviceToggleCard("Time Lock", "Prevent time or timezone changes", uiState.lockTimeEnabled) {
                    viewModel.requestSettingToggle("time", it)
                }
                DeviceToggleCard("VPN / DNS Lock", "Protect network policy from manual changes", uiState.lockDnsVpnEnabled) {
                    viewModel.requestSettingToggle("dns", it)
                }
                DeviceToggleCard("Developer Options Lock", "Keep debugging menus unavailable", uiState.lockDevOptionsEnabled) {
                    viewModel.requestSettingToggle("dev", it)
                }
                DeviceToggleCard("Disable Safe Mode", "Block booting into Safe Mode", uiState.disableSafeModeEnabled) {
                    viewModel.requestSettingToggle("safe", it)
                }
                DeviceToggleCard("User Creation Lock", "Block adding new users", uiState.lockUserCreationEnabled) {
                    viewModel.requestSettingToggle("user", it)
                }
                DeviceToggleCard("Work Profile Lock", "Block creating work profiles", uiState.lockWorkProfileEnabled) {
                    viewModel.requestSettingToggle("work", it)
                }
                DeviceToggleCard("Cloning Lock", "Block dual app and cloning features", uiState.lockCloningEnabled) {
                    viewModel.requestSettingToggle("clone", it)
                }
            }

            uiState.statusMessage?.let { message ->
                item {
                    ElevatedCard(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.elevatedCardColors(
                            containerColor = if (uiState.isError) {
                                MaterialTheme.colorScheme.errorContainer
                            } else {
                                MaterialTheme.colorScheme.secondaryContainer
                            }
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconWithTint(
                                icon = Icons.Default.CheckCircle,
                                tint = if (uiState.isError) {
                                    MaterialTheme.colorScheme.onErrorContainer
                                } else {
                                    MaterialTheme.colorScheme.onSecondaryContainer
                                }
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = message,
                                color = if (uiState.isError) {
                                    MaterialTheme.colorScheme.onErrorContainer
                                } else {
                                    MaterialTheme.colorScheme.onSecondaryContainer
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OverviewStat(label: String, value: Int) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = "$value",
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
        )
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun IconWithTint(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: androidx.compose.ui.graphics.Color
) {
    androidx.compose.material3.Icon(
        imageVector = icon,
        contentDescription = null,
        tint = tint
    )
}

@Composable
fun DeviceToggleCard(title: String, desc: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = MaterialTheme.shapes.large
    ) {
        ListItem(
            colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
            headlineContent = { Text(title, style = MaterialTheme.typography.titleMedium) },
            supportingContent = { Text(desc, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) },
            trailingContent = {
                Switch(checked = checked, onCheckedChange = onCheckedChange)
            }
        )
    }
}
