package com.ankit.destination.ui.apps

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ankit.destination.policy.PolicyEngine
import com.ankit.destination.security.AppLockManager
import com.ankit.destination.ui.components.AdminSessionBanner
import com.ankit.destination.ui.components.AdminSessionDialog
import com.ankit.destination.ui.components.AnimatedNumberCounter
import com.ankit.destination.ui.components.InstalledAppIcon
import com.ankit.destination.ui.components.RadialProgressGauge
import com.ankit.destination.ui.components.bouncyClickable
import com.ankit.destination.ui.components.collectAsStateWithLifecycleCompat
import com.ankit.destination.ui.components.showShortToast

enum class AppFilter { All, Blocked, CustomRules }

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun IndividualAppsScreen(
    onNavigateToAppDetail: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val policyEngine = remember(context) { PolicyEngine(context.applicationContext) }
    val appLockManager = remember(context) { AppLockManager(context) }
    val viewModel: IndividualAppsViewModel = viewModel(
        factory = IndividualAppsViewModelFactory(
            context.applicationContext,
            policyEngine,
            appLockManager
        )
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycleCompat()
    var searchQuery by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf(AppFilter.All) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val toast = remember(context) { { message: String -> context.showShortToast(message) } }

    LaunchedEffect(Unit) {
        viewModel.refresh()
    }

    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refresh()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    if (uiState.showAuthDialog) {
        AdminSessionDialog(
            onDismiss = { viewModel.dismissAuthDialog() },
            onAuthenticated = { _ ->
                viewModel.onAuthenticated { pkg ->
                    onNavigateToAppDetail(pkg)
                }
            },
            appLockManager = appLockManager
        )
    }

    Scaffold { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            AdminSessionBanner(remainingMs = uiState.adminSessionRemainingMs)

            val filteredApps = uiState.apps.filter { app ->
                val matchesSearch = if (searchQuery.isBlank()) true else app.label.contains(searchQuery, ignoreCase = true)
                val matchesFilter = when (selectedFilter) {
                    AppFilter.All -> true
                    AppFilter.Blocked -> app.blockMessage != null
                    AppFilter.CustomRules -> app.hasCustomRules
                }
                matchesSearch && matchesFilter
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 24.dp, bottom = 80.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    val totalUsageMinutes = uiState.apps.sumOf { it.usageTimeMs } / 60_000
                    val customRulesCount = uiState.apps.count { it.hasCustomRules }
                    Text(
                        text = "App Usage Rules",
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                    
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                            .padding(bottom = 8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.extraLarge
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                RadialProgressGauge(
                                    progress = when {
                                        uiState.apps.isEmpty() -> 0f
                                        else -> (customRulesCount.toFloat() / uiState.apps.size.toFloat()).coerceIn(0f, 1f)
                                    },
                                    modifier = Modifier.size(80.dp),
                                    strokeWidth = 16f
                                ) {
                                    AnimatedNumberCounter(
                                        targetValue = customRulesCount,
                                        textStyle = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold)
                                    )
                                }
                                Spacer(modifier = Modifier.height(16.dp))
                                Text("Apps have custom rules", style = MaterialTheme.typography.labelMedium)
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    AnimatedNumberCounter(
                                        targetValue = totalUsageMinutes.toInt(),
                                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text(" minutes tracked today", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }

                item {
                    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("Search apps...") },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                            trailingIcon = {
                                AnimatedVisibility(
                                    visible = searchQuery.isNotEmpty(),
                                    enter = fadeIn(),
                                    exit = fadeOut()
                                ) {
                                    IconButton(onClick = { searchQuery = "" }) {
                                        Icon(Icons.Default.Clear, contentDescription = "Clear search")
                                    }
                                }
                            },
                            shape = MaterialTheme.shapes.extraLarge,
                            singleLine = true
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            FilterChip(
                                selected = selectedFilter == AppFilter.All,
                                onClick = {
                                    selectedFilter = AppFilter.All
                                    toast("Showing all apps.")
                                },
                                label = { Text("All") }
                            )
                            FilterChip(
                                selected = selectedFilter == AppFilter.Blocked,
                                onClick = {
                                    selectedFilter = AppFilter.Blocked
                                    toast("Showing blocked apps.")
                                },
                                label = { Text("Blocked") }
                            )
                            FilterChip(
                                selected = selectedFilter == AppFilter.CustomRules,
                                onClick = {
                                    selectedFilter = AppFilter.CustomRules
                                    toast("Showing apps with custom rules.")
                                },
                                label = { Text("Custom Rules") }
                            )
                        }
                    }
                }

                items(filteredApps, key = { it.packageName }) { app ->
                    val maxUsage = (filteredApps.maxOfOrNull { it.usageTimeMs } ?: 0L).coerceAtLeast(1L)
                    val interactionSource = remember { MutableInteractionSource() }

                    ElevatedCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .bouncyClickable(interactionSource = interactionSource) {
                                toast("Opening ${app.label}.")
                                viewModel.attemptEditApp(app.packageName) { onNavigateToAppDetail(it) }
                            },
                        shape = MaterialTheme.shapes.large,
                        colors = androidx.compose.material3.CardDefaults.elevatedCardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                        )
                    ) {
                        Column(modifier = Modifier.padding(20.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    val progressVal = (app.usageTimeMs.toFloat() / maxUsage.toFloat()).coerceIn(0f, 1f)
                                    RadialProgressGauge(
                                        progress = progressVal,
                                        modifier = Modifier.size(52.dp),
                                        strokeWidth = 14f,
                                        activeColor = if (app.blockMessage != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                                    ) {
                                        InstalledAppIcon(
                                            packageName = app.packageName,
                                            modifier = Modifier.size(24.dp),
                                            contentDescription = null
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(16.dp))
                                    Column {
                                        Text(app.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            AnimatedNumberCounter(
                                                targetValue = (app.usageTimeMs / 60000).toInt(),
                                                textStyle = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                                color = if (app.blockMessage != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                                suffix = " mins"
                                            )
                                            Text(
                                                " | ${app.opensToday} opens",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                                
                                if (app.hasCustomRules) {
                                    Icon(
                                        imageVector = Icons.Default.Info,
                                        contentDescription = "Custom Rules Active",
                                        tint = MaterialTheme.colorScheme.secondary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                            
                            app.blockMessage?.let { blockMessage ->
                                Spacer(modifier = Modifier.height(12.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.Warning,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        blockMessage,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }
                }
                
                if (filteredApps.isEmpty()) {
                    item {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(48.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                            Spacer(Modifier.height(16.dp))
                            Text("No apps matching filter", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}
