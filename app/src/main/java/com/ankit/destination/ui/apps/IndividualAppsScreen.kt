package com.ankit.destination.ui.apps

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ankit.destination.policy.PolicyEngine
import com.ankit.destination.security.AppLockManager
import com.ankit.destination.ui.UiInvalidationBus
import com.ankit.destination.ui.components.AdminSessionBanner
import com.ankit.destination.ui.components.AdminSessionDialog
import com.ankit.destination.ui.components.InstalledAppIcon
import com.ankit.destination.ui.components.SectionSurface
import com.ankit.destination.ui.components.collectAsStateWithLifecycleCompat

enum class AppFilter { All, Blocked, CustomRules }

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun IndividualAppsScreen(
    policyEngine: PolicyEngine,
    appLockManager: AppLockManager,
    onNavigateToAppDetail: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val viewModel: IndividualAppsViewModel = viewModel(
        factory = IndividualAppsViewModelFactory(
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
            onAuthenticated = { _ ->
                viewModel.onAuthenticated { pkg ->
                    onNavigateToAppDetail(pkg)
                }
            },
            appLockManager = appLockManager
        )
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
                contentPadding = PaddingValues(bottom = 80.dp)
            ) {
                item(contentType = "header") {
                    Column(
                        modifier = Modifier.padding(start = 16.dp, top = 24.dp, end = 16.dp)
                    ) {
                        Text(
                            text = "App Usage",
                            style = MaterialTheme.typography.displaySmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        SectionSurface(
                            modifier = Modifier.fillMaxWidth(),
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                        ) {
                            Text(
                                text = "${uiState.customRulesCount} custom rules",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "${uiState.totalUsageMinutes} mins tracked today",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Spacer(modifier = Modifier.height(24.dp))
                    }
                }

                item(contentType = "search_filter") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
                    ) {
                        OutlinedTextField(
                            value = uiState.searchQuery,
                            onValueChange = viewModel::updateSearchQuery,
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("Search apps...") },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                            trailingIcon = {
                                AnimatedVisibility(
                                    visible = uiState.searchQuery.isNotEmpty(),
                                    enter = fadeIn(),
                                    exit = fadeOut()
                                ) {
                                    IconButton(onClick = { viewModel.updateSearchQuery("") }) {
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
                                selected = uiState.selectedFilter == AppFilter.All,
                                onClick = { viewModel.updateFilter(AppFilter.All) },
                                label = { Text("All") }
                            )
                            FilterChip(
                                selected = uiState.selectedFilter == AppFilter.Blocked,
                                onClick = { viewModel.updateFilter(AppFilter.Blocked) },
                                label = { Text("Blocked") }
                            )
                            FilterChip(
                                selected = uiState.selectedFilter == AppFilter.CustomRules,
                                onClick = { viewModel.updateFilter(AppFilter.CustomRules) },
                                label = { Text("Rules") }
                            )
                        }
                    }
                }

                itemsIndexed(
                    items = uiState.filteredApps,
                    key = { _, item -> item.packageName },
                    contentType = { _, _ -> "app_row" }
                ) { index, app ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                viewModel.attemptEditApp(app.packageName) { onNavigateToAppDetail(it) }
                            },
                        color = MaterialTheme.colorScheme.background
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    modifier = Modifier.weight(1f),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(48.dp)
                                            .clip(CircleShape)
                                            .background(
                                                color = if (app.blockMessage != null) {
                                                    MaterialTheme.colorScheme.errorContainer
                                                } else {
                                                    MaterialTheme.colorScheme.surfaceContainerLow
                                                },
                                                shape = CircleShape
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        InstalledAppIcon(
                                            packageName = app.packageName,
                                            modifier = Modifier.size(24.dp),
                                            contentDescription = null
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(16.dp))
                                    Column {
                                        Text(
                                            text = app.label,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onBackground
                                        )
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = "${app.usageTimeMs / 60000}m",
                                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                                color = if (app.blockMessage != null) {
                                                    MaterialTheme.colorScheme.error
                                                } else {
                                                    MaterialTheme.colorScheme.primary
                                                }
                                            )
                                            Text(
                                                text = " | ${app.opensToday} opens",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.outline
                                            )
                                        }
                                    }
                                }

                                if (app.hasCustomRules) {
                                    Icon(
                                        imageVector = Icons.Default.Info,
                                        contentDescription = "Custom rules active",
                                        tint = MaterialTheme.colorScheme.secondary,
                                        modifier = Modifier
                                            .size(20.dp)
                                            .padding(start = 8.dp)
                                    )
                                }
                            }

                            app.blockMessage?.let { blockMessage ->
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier.padding(start = 64.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Warning,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = blockMessage,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.error,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }

                            if (index < uiState.filteredApps.lastIndex) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(start = 64.dp, top = 12.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant
                                )
                            }
                        }
                    }
                }

                if (uiState.filteredApps.isEmpty() && !uiState.isLoading) {
                    item(contentType = "empty_state") {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(48.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.outlineVariant
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "No apps found",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                }
            }
        }
    }
}
