package com.ankit.destination.ui.apprules

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ankit.destination.policy.PolicyEngine
import com.ankit.destination.security.AppLockManager
import com.ankit.destination.ui.components.AdminSessionBanner
import com.ankit.destination.ui.components.AdminSessionDialog
import com.ankit.destination.ui.components.AppPickerDialog
import com.ankit.destination.ui.components.BulkPackageInputDialog
import com.ankit.destination.ui.components.collectAsStateWithLifecycleCompat

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AppRulesScreen() {
    val context = LocalContext.current
    val policyEngine = remember(context) { PolicyEngine(context.applicationContext) }
    val appLockManager = remember(context) { AppLockManager(context) }
    val viewModel: AppRulesViewModel = viewModel(
        factory = AppRulesViewModelFactory(
            context.applicationContext,
            policyEngine,
            appLockManager
        )
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycleCompat()
    var showPicker by remember { mutableStateOf(false) }
    var showBulkInput by remember { mutableStateOf(false) }
    var showAddMenu by remember { mutableStateOf(false) }

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

    var selectedTabIndex by remember { mutableIntStateOf(0) }
    val tabs = listOf(
        "Allowlist" to AppRuleCategory.ALLOWLIST,
        "Blocklist" to AppRuleCategory.BLOCKLIST,
        "Uninstall Protection" to AppRuleCategory.UNINSTALL_PROTECTION
    )
    val selectedCategory = tabs[selectedTabIndex].second
    val displayRules by remember(uiState.rules, selectedCategory, context.packageName) {
        derivedStateOf {
            uiState.rules.filter { rule ->
                rule.belongsTo(selectedCategory) &&
                    !(selectedCategory == AppRuleCategory.UNINSTALL_PROTECTION && rule.packageName == context.packageName)
            }
        }
    }

    if (showPicker) {
        val pickerOptions = uiState.availableApps
            .filterNot {
                selectedCategory == AppRuleCategory.UNINSTALL_PROTECTION &&
                    it.packageName == context.packageName
            }
        AppPickerDialog(
            title = "Select apps",
            options = pickerOptions,
            selectedPackageNames = emptySet(),
            onDismiss = { showPicker = false },
            onConfirm = { selected ->
                showPicker = false
                viewModel.addRules(selectedCategory, selected)
            }
        )
    }

    if (showBulkInput) {
        BulkPackageInputDialog(
            onDismiss = { showBulkInput = false },
            onConfirm = { packages ->
                showBulkInput = false
                viewModel.addRules(selectedCategory, packages)
            }
        )
    }

    Scaffold(
        floatingActionButton = {
            Box {
                FloatingActionButton(
                    onClick = {
                        if (selectedCategory == AppRuleCategory.BLOCKLIST) {
                            showAddMenu = true
                        } else {
                            showPicker = true
                        }
                    }
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Add app rule")
                }
                DropdownMenu(
                    expanded = showAddMenu,
                    onDismissRequest = { showAddMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Select apps") },
                        onClick = {
                            showAddMenu = false
                            showPicker = true
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Paste package list") },
                        onClick = {
                            showAddMenu = false
                            showBulkInput = true
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            AdminSessionBanner(remainingMs = uiState.adminSessionRemainingMs)

            PrimaryTabRow(
                selectedTabIndex = selectedTabIndex,
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                contentColor = MaterialTheme.colorScheme.primary
            ) {
                tabs.forEachIndexed { index, (title, _) ->
                    Tab(
                        selected = selectedTabIndex == index,
                        onClick = { selectedTabIndex = index },
                        text = { Text(title, fontWeight = if (selectedTabIndex == index) FontWeight.Bold else FontWeight.Normal) },
                        selectedContentColor = MaterialTheme.colorScheme.primary,
                        unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 24.dp, bottom = 80.dp)
            ) {
                item {
                    Text(
                        text = "App Rules",
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                }

                uiState.statusMessage?.let { message ->
                    item {
                        ElevatedCard(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                            colors = androidx.compose.material3.CardDefaults.elevatedCardColors(
                                containerColor = if (uiState.isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer
                            )
                        ) {
                            Text(
                                text = message,
                                modifier = Modifier.padding(16.dp),
                                color = if (uiState.isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                }

                if (displayRules.isEmpty()) {
                    item {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(48.dp),
                            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
                        ) {
                            Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                            Spacer(Modifier.height(16.dp))
                            Text("No apps added to this rule.", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } else {
                    items(displayRules, key = { it.packageName }) { rule ->
                        val dismissState = rememberSwipeToDismissBoxState(
                            confirmValueChange = {
                                if (rule.isLocked) {
                                    false
                                } else if (it == SwipeToDismissBoxValue.EndToStart || it == SwipeToDismissBoxValue.StartToEnd) {
                                    viewModel.removeRule(selectedCategory, rule)
                                    true
                                } else false
                            }
                        )
                        
                        SwipeToDismissBox(
                            state = dismissState,
                            backgroundContent = {
                                val color by animateColorAsState(
                                    when (dismissState.targetValue) {
                                        SwipeToDismissBoxValue.Settled -> Color.Transparent
                                        else -> MaterialTheme.colorScheme.errorContainer
                                    }, label = "swipe_color"
                                )
                                Box(
                                    Modifier
                                        .fillMaxSize()
                                        .padding(bottom = 12.dp)
                                        .background(color, MaterialTheme.shapes.large)
                                        .padding(horizontal = 20.dp),
                                    contentAlignment = androidx.compose.ui.Alignment.CenterEnd
                                ) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = "Delete",
                                        tint = if (dismissState.targetValue == SwipeToDismissBoxValue.Settled) Color.Transparent else MaterialTheme.colorScheme.onErrorContainer
                                    )
                                }
                            }
                        ) {
                            ElevatedCard(
                                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                                colors = androidx.compose.material3.CardDefaults.elevatedCardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                                ),
                                shape = MaterialTheme.shapes.large
                            ) {
                                ListItem(
                                    colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                                    headlineContent = { Text(rule.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) },
                                    supportingContent = {
                                        Column {
                                            Text(
                                                rule.packageName,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    },
                                    trailingContent = {
                                        androidx.compose.material3.IconButton(
                                            onClick = { viewModel.removeRule(selectedCategory, rule) },
                                            enabled = !rule.isLocked
                                        ) {
                                            androidx.compose.material3.Icon(
                                                imageVector = androidx.compose.material.icons.Icons.Default.Delete,
                                                contentDescription = "Remove",
                                                tint = if (rule.isLocked) {
                                                    MaterialTheme.colorScheme.onSurfaceVariant
                                                } else {
                                                    MaterialTheme.colorScheme.error
                                                }
                                            )
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
