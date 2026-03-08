package com.ankit.destination.ui.dashboard

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.platform.LocalContext
import com.ankit.destination.policy.PolicyEngine
import com.ankit.destination.security.AppLockManager
import com.ankit.destination.ui.components.AdminSessionBanner
import com.ankit.destination.ui.components.AnimatedNumberCounter
import com.ankit.destination.ui.components.bouncyClickable
import com.ankit.destination.ui.components.collectAsStateWithLifecycleCompat

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DashboardScreen() {
    val context = LocalContext.current
    val policyEngine = remember(context) { PolicyEngine(context.applicationContext) }
    val appLockManager = remember(context) { AppLockManager(context) }
    val viewModel: DashboardViewModel = viewModel(
        factory = DashboardViewModelFactory(
            context.applicationContext,
            policyEngine,
            appLockManager
        )
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycleCompat()

    LaunchedEffect(Unit) {
        viewModel.refresh()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        AdminSessionBanner(remainingMs = uiState.adminSessionRemainingMs)

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Text(
                    text = "Dashboard",
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }

            item {
                if (uiState.isDeviceOwner && !uiState.usageAccessGranted) {
                    ElevatedCard(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.elevatedCardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        ),
                        shape = MaterialTheme.shapes.large
                    ) {
                        Column(modifier = Modifier.padding(20.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onErrorContainer
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = "Usage Access Required",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = uiState.usageAccessRecoveryReason
                                    ?: if (uiState.usageAccessRecoveryLockdownActive) {
                                        "Destination lost Usage Access. Recovery lockdown is active until you turn it back on."
                                    } else {
                                        "Destination lost Usage Access. Usage-based enforcement is unavailable until you turn it back on."
                                    },
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Button(
                                onClick = {
                                    context.startActivity(
                                        Intent(context, com.ankit.destination.ui.UsageAccessGuideActivity::class.java)
                                    )
                                }
                            ) {
                                Text("Open Usage Access")
                            }
                        }
                    }
                }
            }

            item {
                if (uiState.isDeviceOwner && (!uiState.accessibilityServiceEnabled || !uiState.accessibilityServiceRunning)) {
                    ElevatedCard(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.elevatedCardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        ),
                        shape = MaterialTheme.shapes.large
                    ) {
                        Column(modifier = Modifier.padding(20.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onErrorContainer
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = "Accessibility Required",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = uiState.accessibilityDegradedReason
                                    ?: "Destination needs Accessibility for real-time blocking and service recovery.",
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Button(
                                onClick = {
                                    context.startActivity(
                                        Intent(context, com.ankit.destination.ui.AccessibilityGuideActivity::class.java)
                                    )
                                }
                            ) {
                                Text("Open Accessibility")
                            }
                        }
                    }
                }
            }

            item {
                AnimatedVisibility(
                    visible = true,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    val isProtected = uiState.protectionActive
                    ElevatedCard(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.elevatedCardColors(
                            containerColor = if (isProtected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer
                        ),
                        shape = MaterialTheme.shapes.extraLarge
                    ) {
                        Column(modifier = Modifier.padding(24.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = if (isProtected) Icons.Default.Security else Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = if (isProtected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.size(32.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = if (isProtected) "Protection Active" else "Protection Disabled",
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isProtected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                            Spacer(modifier = Modifier.height(24.dp))
                            
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceEvenly
                            ) {
                                StatItem(
                                    icon = Icons.Default.VpnKey,
                                    value = if (uiState.vpnLocked) "Locked" else "Off",
                                    label = "VPN",
                                    iconTint = if (uiState.vpnLocked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                StatItem(
                                    icon = Icons.Default.Group,
                                    value = uiState.activeSchedules,
                                    label = "Groups Blocked",
                                    iconTint = MaterialTheme.colorScheme.error
                                )
                                StatItem(
                                    icon = Icons.Default.Block,
                                    value = uiState.totalBlockedApps,
                                    label = "Apps Suspended",
                                    iconTint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }

            uiState.nextPolicyWake?.let { nextWake ->
                item {
                    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "Next Policy Wake",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(text = nextWake)
                        }
                    }
                }
            }

            item {
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .background(MaterialTheme.colorScheme.tertiaryContainer, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.ElectricBolt,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                "Active Summary",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            SummaryRow(Icons.Default.Schedule, "Active Schedules", uiState.activeSchedules)
                            SummaryRow(Icons.Default.Warning, "Emergency Sessions", uiState.activeEmergencySessions)
                            SummaryRow(Icons.Default.Lock, "Strict Mode Groups", uiState.strictActiveGroups)
                        }
                    }
                }
            }

            item {
                Button(
                    onClick = { viewModel.applyNow() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .padding(top = 8.dp),
                    shape = MaterialTheme.shapes.large,
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Apply Rules Now", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

@Composable
private fun StatItem(icon: ImageVector, value: Any, label: String, iconTint: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(28.dp))
        Spacer(modifier = Modifier.height(8.dp))
        if (value is Int) {
            AnimatedNumberCounter(
                targetValue = value,
                textStyle = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )
        } else {
            Text(value.toString(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SummaryRow(icon: ImageVector, label: String, value: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
        }
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = MaterialTheme.shapes.small
        ) {
            AnimatedNumberCounter(
                targetValue = value,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                textStyle = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}
