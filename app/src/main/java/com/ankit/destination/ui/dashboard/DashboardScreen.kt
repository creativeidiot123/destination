package com.ankit.destination.ui.dashboard

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ElectricBolt
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ankit.destination.policy.PolicyEngine
import com.ankit.destination.policy.UsageSnapshotStatus
import com.ankit.destination.security.AppLockManager
import com.ankit.destination.ui.UiInvalidationBus
import com.ankit.destination.ui.components.AdminSessionBanner
import com.ankit.destination.ui.components.AnimatedNumberCounter
import com.ankit.destination.ui.components.SectionSurface
import com.ankit.destination.ui.components.StatusBanner
import com.ankit.destination.ui.components.collectAsStateWithLifecycleCompat

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DashboardScreen(
    policyEngine: PolicyEngine,
    appLockManager: AppLockManager
) {
    val context = LocalContext.current
    val viewModel: DashboardViewModel = viewModel(
        factory = DashboardViewModelFactory(
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

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            AdminSessionBanner(remainingMs = uiState.adminSessionRemainingMs)

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 80.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item(contentType = "header") {
                    Text(
                        text = "Dashboard",
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.padding(start = 16.dp, top = 24.dp, end = 16.dp)
                    )
                }

                if (uiState.isDeviceOwner && uiState.usageSnapshotStatus == UsageSnapshotStatus.ACCESS_MISSING) {
                    item(contentType = "warning_banner") {
                        StatusBanner(
                            title = "Usage Access Required",
                            message = uiState.usageAccessRecoveryReason
                                ?: if (uiState.usageAccessRecoveryLockdownActive) {
                                    "Destination lost Usage Access. Recovery lockdown is active until you turn it back on."
                                } else {
                                    "Destination lost Usage Access. Usage-based enforcement is unavailable."
                                },
                            icon = Icons.Default.Warning,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                            actionLabel = "Open Usage Access",
                            onAction = {
                                context.startActivity(
                                    Intent(context, com.ankit.destination.ui.UsageAccessGuideActivity::class.java)
                                )
                            }
                        )
                    }
                }

                if (uiState.isDeviceOwner && uiState.usageSnapshotStatus == UsageSnapshotStatus.INGESTION_FAILED) {
                    item(contentType = "warning_banner") {
                        StatusBanner(
                            title = "Usage Data Stale",
                            message = uiState.usageAccessRecoveryReason
                                ?: "Destination could not refresh usage data. Last known usage remains in effect until the next successful refresh.",
                            icon = Icons.Default.Warning,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp)
                        )
                    }
                }

                if (uiState.isDeviceOwner && (!uiState.accessibilityServiceEnabled || !uiState.accessibilityServiceRunning)) {
                    item(contentType = "warning_banner") {
                        StatusBanner(
                            title = "Accessibility Required",
                            message = uiState.accessibilityDegradedReason
                                ?: "Destination needs Accessibility for real-time blocking and service recovery.",
                            icon = Icons.Default.Warning,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                            actionLabel = "Open Accessibility",
                            onAction = {
                                context.startActivity(
                                    Intent(context, com.ankit.destination.ui.AccessibilityGuideActivity::class.java)
                                )
                            }
                        )
                    }
                }

                item(contentType = "protection_status") {
                    val isProtected = uiState.protectionActive
                    SectionSurface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        containerColor = if (isProtected) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.errorContainer
                        }
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (isProtected) Icons.Default.Security else Icons.Default.Warning,
                                contentDescription = null,
                                tint = if (isProtected) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onErrorContainer
                                },
                                modifier = Modifier.size(28.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = if (isProtected) "Protection Active" else "Protection Disabled",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (isProtected) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onErrorContainer
                                }
                            )
                        }
                        Spacer(modifier = Modifier.height(20.dp))
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(24.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                            maxItemsInEachRow = 3
                        ) {
                            StatItem(
                                icon = Icons.Default.VpnKey,
                                value = if (uiState.vpnLocked) "Locked" else "Off",
                                label = "VPN",
                                iconTint = if (uiState.vpnLocked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                textColor = if (isProtected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer
                            )
                            StatItem(
                                icon = Icons.Default.Group,
                                value = uiState.activeSchedules,
                                label = "Blocked Groups",
                                iconTint = MaterialTheme.colorScheme.error,
                                textColor = if (isProtected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer
                            )
                            StatItem(
                                icon = Icons.Default.Block,
                                value = uiState.totalBlockedApps,
                                label = "Suspended Apps",
                                iconTint = MaterialTheme.colorScheme.error,
                                textColor = if (isProtected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }

                uiState.nextPolicyWake?.let { nextWake ->
                    item(contentType = "next_wake") {
                        SectionSurface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                        ) {
                            Text(
                                text = "Next Policy Wake",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = nextWake,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }

                item(contentType = "active_summary") {
                    SectionSurface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .background(MaterialTheme.colorScheme.tertiaryContainer, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ElectricBolt,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = "Active Summary",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Spacer(modifier = Modifier.height(20.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            SummaryRow(Icons.Default.Schedule, "Active Schedules", uiState.activeSchedules)
                            SummaryRow(Icons.Default.Warning, "Emergency Sessions", uiState.activeEmergencySessions)
                            SummaryRow(Icons.Default.Lock, "Strict Mode Groups", uiState.strictActiveGroups)
                        }
                    }
                }

                item(contentType = "apply_button") {
                    Button(
                        onClick = { viewModel.applyNow() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .padding(start = 16.dp, top = 8.dp, end = 16.dp),
                        shape = MaterialTheme.shapes.extraLarge,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Apply Rules Now", style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatItem(
    icon: ImageVector,
    value: Any,
    label: String,
    iconTint: Color,
    textColor: Color
) {
    Column(
        modifier = Modifier.widthIn(min = 88.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.height(8.dp))
        if (value is Int) {
            AnimatedNumberCounter(
                targetValue = value,
                textStyle = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = textColor
            )
        } else {
            Text(
                text = value.toString(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = textColor
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = textColor.copy(alpha = 0.72f)
        )
    }
}

@Composable
private fun SummaryRow(
    icon: ImageVector,
    label: String,
    value: Int
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = MaterialTheme.shapes.small
        ) {
            Text(
                text = value.toString(),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}
