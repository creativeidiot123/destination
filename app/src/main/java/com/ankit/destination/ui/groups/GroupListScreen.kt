package com.ankit.destination.ui.groups

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.FilterChip
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Schedule
import com.ankit.destination.policy.PolicyEngine
import com.ankit.destination.security.AppLockManager
import com.ankit.destination.ui.UiInvalidationBus
import com.ankit.destination.ui.components.AdminSessionBanner
import com.ankit.destination.ui.components.AdminSessionDialog
import com.ankit.destination.ui.components.collectAsStateWithLifecycleCompat
import com.ankit.destination.ui.components.showShortToast
import kotlinx.coroutines.flow.collectLatest

enum class GroupFilter { All, StrictActive, Emergency }

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun GroupListScreen(
    policyEngine: PolicyEngine,
    appLockManager: AppLockManager,
    onNavigateToGroupDetail: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val viewModel: GroupListViewModel = viewModel(
        factory = GroupListViewModelFactory(
            context.applicationContext,
            policyEngine,
            appLockManager
        )
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycleCompat()
    val invalidation by UiInvalidationBus.latest.collectAsStateWithLifecycleCompat()
    var searchQuery by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf(GroupFilter.All) }
    val toast = remember(context) { { message: String -> context.showShortToast(message) } }

    LaunchedEffect(viewModel) {
        viewModel.events.collectLatest { event ->
            when (event) {
                is GroupListEvent.Toast -> toast(event.message)
            }
        }
    }

    LaunchedEffect(viewModel, invalidation.version) {
        viewModel.onInvalidation(invalidation.version)
    }

    if (uiState.showAuthDialog) {
        AdminSessionDialog(
            onDismiss = { viewModel.dismissAuthDialog() },
            onAuthenticated = { _ ->
                viewModel.onAuthenticated { groupId ->
                    onNavigateToGroupDetail(groupId)
                }
            },
            appLockManager = appLockManager
        )
    }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    toast("Creating a new group.")
                    viewModel.attemptEditGroup("") { onNavigateToGroupDetail("") }
                }
            ) {
                Icon(Icons.Default.Add, contentDescription = "Create Group")
            }
        }
    ) { innerPadding ->
        val filteredGroups by remember(uiState.groups, searchQuery, selectedFilter) {
            derivedStateOf {
                val normalizedQuery = searchQuery.trim()
                uiState.groups.filter { group ->
                    val matchesSearch = if (normalizedQuery.isBlank()) {
                        true
                    } else {
                        group.matchesQuery(normalizedQuery)
                    }
                    val matchesFilter = when (selectedFilter) {
                        GroupFilter.All -> true
                        GroupFilter.StrictActive -> group.isStrictActive
                        GroupFilter.Emergency -> group.isEmergencyActive
                    }
                    matchesSearch && matchesFilter
                }
            }
        }

        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            AdminSessionBanner(remainingMs = uiState.adminSessionRemainingMs)

            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 24.dp, bottom = 80.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    Text(
                        text = "App Groups",
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }

                item {
                    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("Search groups...") },
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
                                selected = selectedFilter == GroupFilter.All,
                                onClick = {
                                    selectedFilter = GroupFilter.All
                                    toast("Showing all groups.")
                                },
                                label = { Text("All") }
                            )
                            FilterChip(
                                selected = selectedFilter == GroupFilter.StrictActive,
                                onClick = {
                                    selectedFilter = GroupFilter.StrictActive
                                    toast("Showing strict active groups.")
                                },
                                label = { Text("Strict Active") }
                            )
                            FilterChip(
                                selected = selectedFilter == GroupFilter.Emergency,
                                onClick = {
                                    selectedFilter = GroupFilter.Emergency
                                    toast("Showing emergency groups.")
                                },
                                label = { Text("Emergency") }
                            )
                        }
                    }
                }

                items(filteredGroups, key = { it.groupId }) { group ->
                    val showEmergencyButton = group.effectiveBlocked && group.emergencyEnabled
                    val emergencyActionInProgress = uiState.activatingEmergencyGroupId == group.groupId
                    val emergencyActionEnabled = showEmergencyButton &&
                        !emergencyActionInProgress &&
                        group.emergencyRemainingUnlocks > 0

                    ElevatedCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                toast("Opening ${group.name}.")
                                viewModel.attemptEditGroup(group.groupId) { onNavigateToGroupDetail(it) }
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
                                verticalAlignment = Alignment.Top
                            ) {
                                Text(
                                    group.name,
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(
                                    horizontalAlignment = Alignment.End,
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    if (showEmergencyButton) {
                                        Button(
                                            onClick = { viewModel.activateEmergency(group.groupId) },
                                            enabled = emergencyActionEnabled
                                        ) {
                                            Text(
                                                when {
                                                    emergencyActionInProgress -> "Starting..."
                                                    group.emergencyRemainingUnlocks <= 0 -> "No Emergency Left"
                                                    else -> "Use Emergency"
                                                }
                                            )
                                        }
                                    }
                                    androidx.compose.material3.Surface(
                                        color = MaterialTheme.colorScheme.surfaceVariant,
                                        shape = MaterialTheme.shapes.small
                                    ) {
                                        Text(
                                            "Priority #${group.priorityIndex}",
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Apps, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "${group.appCount} apps",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Schedule, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(group.scheduleSummary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (showEmergencyButton) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "${group.emergencyMinutesPerUnlock} min each - ${group.emergencyRemainingUnlocks}/${group.emergencyUnlocksPerDay} left today",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            
                            Spacer(modifier = Modifier.height(16.dp))
                            
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (group.effectiveBlocked) {
                                    AssistChip(
                                        onClick = { },
                                        label = { Text("Blocked") },
                                        leadingIcon = { Icon(Icons.Default.Block, contentDescription = null, Modifier.width(16.dp)) },
                                        colors = AssistChipDefaults.assistChipColors(
                                            containerColor = MaterialTheme.colorScheme.errorContainer,
                                            labelColor = MaterialTheme.colorScheme.onErrorContainer,
                                            leadingIconContentColor = MaterialTheme.colorScheme.onErrorContainer
                                        )
                                    )
                                } else {
                                    AssistChip(
                                        onClick = { },
                                        label = { Text("Available", color = MaterialTheme.colorScheme.primary) },
                                        leadingIcon = { Icon(Icons.Default.CheckCircle, contentDescription = null, Modifier.width(16.dp), tint = MaterialTheme.colorScheme.primary) }
                                    )
                                }
                                
                                if (group.isStrictActive) {
                                    SuggestionChip(
                                        onClick = {},
                                        label = { Text("Strict Active") },
                                        icon = { Icon(Icons.Default.Lock, contentDescription = null, Modifier.width(16.dp)) }
                                    )
                                }
                                
                                group.primaryReason?.let { reason ->
                                    if (reason != "Available") {
                                        SuggestionChip(
                                            onClick = {},
                                            label = { Text(reason) },
                                            colors = SuggestionChipDefaults.suggestionChipColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                                        )
                                    }
                                }
                            }
                            
                            if (group.isEmergencyActive) {
                                Spacer(modifier = Modifier.height(12.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Timer, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        "Emergency active until ${group.emergencyUntil}",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.tertiary,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }
                }
                
                if (filteredGroups.isEmpty()) {
                    item {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(48.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                            Spacer(Modifier.height(16.dp))
                            Text("No groups matching filter", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

private fun GroupCardState.matchesQuery(query: String): Boolean {
    val normalizedQuery = query.trim().lowercase()
    if (normalizedQuery.isBlank()) return true
    val searchableText = buildList {
        add(name.lowercase())
        add(scheduleSummary.lowercase())
        primaryReason?.lowercase()?.let(::add)
        if (isStrictActive) {
            add("strict")
            add("strict active")
        }
        if (isEmergencyActive) {
            add("emergency")
            add("emergency active")
        }
    }.joinToString(separator = " ")
    return normalizedQuery
        .split(Regex("\\s+"))
        .all { token -> searchableText.contains(token) }
}
