package com.ankit.destination.ui.apps

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
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.material3.Slider
import com.ankit.destination.budgets.MAX_EMERGENCY_MINUTES_PER_UNLOCK
import com.ankit.destination.budgets.MAX_EMERGENCY_UNLOCKS_PER_DAY
import com.ankit.destination.policy.PolicyEngine
import com.ankit.destination.security.AppLockManager
import com.ankit.destination.ui.components.AdminSessionBanner
import com.ankit.destination.ui.components.AdminSessionDialog
import com.ankit.destination.ui.components.AnimatedNumberCounter
import com.ankit.destination.ui.components.RadialProgressGauge
import com.ankit.destination.ui.components.collectAsStateWithLifecycleCompat
import com.ankit.destination.ui.components.showShortToast
import kotlinx.coroutines.flow.collectLatest

private enum class AppLimitDialogType {
    HOURLY,
    DAILY,
    OPENS,
    EMERGENCY_UNLOCKS,
    EMERGENCY_MINUTES
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
fun AppDetailScreen(
    packageName: String,
    onBack: () -> Unit = {}
) {
    val context = LocalContext.current
    val policyEngine = remember(context) { PolicyEngine(context.applicationContext) }
    val appLockManager = remember(context) { AppLockManager(context) }
    val viewModel: AppDetailViewModel = viewModel(
        factory = AppDetailViewModelFactory(
            context.applicationContext,
            policyEngine,
            appLockManager,
            packageName
        )
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycleCompat()
    var activeDialog by remember { mutableStateOf<AppLimitDialogType?>(null) }
    val toast = remember(context) { { message: String -> context.showShortToast(message) } }

    LaunchedEffect(Unit) {
        viewModel.refresh()
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collectLatest { event ->
            when (event) {
                is AppDetailEvent.Toast -> toast(event.message)
                is AppDetailEvent.Finish -> {
                    toast(event.message)
                    onBack()
                }
            }
        }
    }

    if (uiState.showAuthDialog) {
        AdminSessionDialog(
            onDismiss = { viewModel.dismissAuthDialog() },
            onAuthenticated = { _ -> viewModel.onAuthenticated() },
            appLockManager = appLockManager
        )
    }

    activeDialog?.let { dialogType ->
        AppNumberInputDialog(
            dialogType = dialogType,
            uiState = uiState,
            onDismiss = { activeDialog = null },
            onValueSelected = { value ->
                activeDialog = null
                when (dialogType) {
                    AppLimitDialogType.HOURLY -> viewModel.updateHourlyLimitMinutes(value)
                    AppLimitDialogType.DAILY -> viewModel.updateDailyLimitMinutes(value)
                    AppLimitDialogType.OPENS -> viewModel.updateOpensLimit(value)
                    AppLimitDialogType.EMERGENCY_UNLOCKS -> {
                        viewModel.updateEmergencyConfig(value, uiState.emergencyMinutesPerUnlock)
                    }
                    AppLimitDialogType.EMERGENCY_MINUTES -> {
                        viewModel.updateEmergencyConfig(uiState.emergencyUnlocksPerDay, value)
                    }
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(uiState.label) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(
                        onClick = { viewModel.requestSave() },
                        enabled = uiState.ineligibilityReason == null
                    ) {
                        Text("Save")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            AdminSessionBanner(remainingMs = uiState.adminSessionRemainingMs)

            LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item {
                    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(16.dp).fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("Today's Usage", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                            Spacer(modifier = Modifier.height(24.dp))
                            
                            val target = when {
                                uiState.restrictDaily && uiState.dailyLimitMs > 0L -> uiState.dailyLimitMs
                                else -> (uiState.usedTodayMs.coerceAtLeast(1L))
                            }
                            val progress = (uiState.usedTodayMs.toFloat() / target.toFloat()).coerceIn(0f, 1f)
                            
                            RadialProgressGauge(
                                progress = progress,
                                modifier = Modifier.padding(16.dp).fillMaxWidth(),
                                activeColor = if (progress >= 1f && uiState.restrictDaily) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                strokeWidth = 32f
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    AnimatedNumberCounter(
                                        targetValue = (uiState.usedTodayMs / 60_000).toInt(),
                                        textStyle = MaterialTheme.typography.displayMedium.copy(fontWeight = FontWeight.Bold),
                                        color = if (progress >= 1f && uiState.restrictDaily) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                                    )
                                    Text("minutes", style = MaterialTheme.typography.labelLarge)
                                }
                            }
                            
                            Spacer(modifier = Modifier.height(24.dp))
                            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                Text("This hour")
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    AnimatedNumberCounter(targetValue = (uiState.usedHourMs / 60_000).toInt(), textStyle = MaterialTheme.typography.titleMedium, suffix = " mins")
                                }
                            }
                            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                Text("Opens today")
                                AnimatedNumberCounter(targetValue = uiState.opensToday, textStyle = MaterialTheme.typography.titleMedium)
                            }
                        }
                    }
                }

                if (uiState.isAllowlisted && uiState.ineligibilityReason == null) {
                    item {
                        ElevatedCard(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.elevatedCardColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer
                            )
                        ) {
                            Text(
                                text = "This app is in Allowlist. It stays eligible for direct and group policies, but all-apps strict expansion will skip it.",
                                modifier = Modifier.padding(16.dp),
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                }

                uiState.ineligibilityReason?.let { reason ->
                    item {
                        ElevatedCard(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.elevatedCardColors(
                                containerColor = MaterialTheme.colorScheme.tertiaryContainer
                            )
                        ) {
                            Text(
                                text = reason,
                                modifier = Modifier.padding(16.dp),
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        }
                    }
                }

                item {
                    ElevatedCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .alpha(if (uiState.ineligibilityReason != null) 0.5f else 1f),
                        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Individual Limits", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                            Spacer(modifier = Modifier.height(12.dp))
                            AppSettingRow(
                                title = "Hourly limit",
                                value = if (uiState.restrictHourly) "${uiState.hourlyLimitMs / 60_000} min/hr" else "No cap",
                                enabled = uiState.restrictHourly,
                                controlsEnabled = uiState.ineligibilityReason == null,
                                onToggle = { enabled ->
                                    viewModel.toggleHourlyLimit(enabled)
                                    toast(if (enabled) "Hourly limit enabled." else "Hourly limit disabled.")
                                },
                                onEdit = {
                                    activeDialog = AppLimitDialogType.HOURLY
                                    toast("Editing hourly limit.")
                                }
                            )
                            AppSettingRow(
                                title = "Daily limit",
                                value = if (uiState.restrictDaily) "${uiState.dailyLimitMs / 60_000} min/day" else "No cap",
                                enabled = uiState.restrictDaily,
                                controlsEnabled = uiState.ineligibilityReason == null,
                                onToggle = { enabled ->
                                    viewModel.toggleDailyLimit(enabled)
                                    toast(if (enabled) "Daily limit enabled." else "Daily limit disabled.")
                                },
                                onEdit = {
                                    activeDialog = AppLimitDialogType.DAILY
                                    toast("Editing daily limit.")
                                }
                            )
                            AppSettingRow(
                                title = "Launch limit",
                                value = if (uiState.restrictOpens) "${uiState.opensLimit} opens/day" else "No cap",
                                enabled = uiState.restrictOpens,
                                controlsEnabled = uiState.ineligibilityReason == null,
                                onToggle = { enabled ->
                                    viewModel.toggleOpensLimit(enabled)
                                    toast(if (enabled) "Launch limit enabled." else "Launch limit disabled.")
                                },
                                onEdit = {
                                    activeDialog = AppLimitDialogType.OPENS
                                    toast("Editing launches per day.")
                                }
                            )
                        }
                    }
                }

                item {
                    ElevatedCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .alpha(if (uiState.ineligibilityReason != null) 0.5f else 1f),
                        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "Emergency Access",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f)
                                )
                                Switch(
                                    checked = uiState.isEmergencyEnabled,
                                    enabled = uiState.ineligibilityReason == null,
                                    onCheckedChange = { enabled ->
                                        viewModel.toggleEmergency(enabled)
                                        toast(if (enabled) "Emergency access enabled." else "Emergency access disabled.")
                                    }
                                )
                            }
                            if (uiState.isEmergencyEnabled) {
                                Spacer(modifier = Modifier.height(12.dp))
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("Unlocks/day")
                                    TextButton(enabled = uiState.ineligibilityReason == null, onClick = {
                                        activeDialog = AppLimitDialogType.EMERGENCY_UNLOCKS
                                        toast("Editing emergency unlocks per day.")
                                    }) {
                                        Text(uiState.emergencyUnlocksPerDay.toString())
                                    }
                                }
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("Minutes/unlock")
                                    TextButton(enabled = uiState.ineligibilityReason == null, onClick = {
                                        activeDialog = AppLimitDialogType.EMERGENCY_MINUTES
                                        toast("Editing emergency minutes per unlock.")
                                    }) {
                                        Text(uiState.emergencyMinutesPerUnlock.toString())
                                    }
                                }
                            }
                        }
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
            }
        }
    }
}

@Composable
private fun AppSettingRow(
    title: String,
    value: String,
    enabled: Boolean,
    controlsEnabled: Boolean,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(value) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (enabled) {
                    TextButton(onClick = onEdit, enabled = controlsEnabled) {
                        Text("Edit")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Switch(checked = enabled, onCheckedChange = onToggle, enabled = controlsEnabled)
            }
        }
    )
}

@Composable
private fun AppNumberInputDialog(
    dialogType: AppLimitDialogType,
    uiState: AppDetailUiState,
    onDismiss: () -> Unit,
    onValueSelected: (Int) -> Unit
) {
    val initialValue = when (dialogType) {
        AppLimitDialogType.HOURLY -> (uiState.hourlyLimitMs / 60_000L).toInt()
        AppLimitDialogType.DAILY -> (uiState.dailyLimitMs / 60_000L).toInt()
        AppLimitDialogType.OPENS -> uiState.opensLimit
        AppLimitDialogType.EMERGENCY_UNLOCKS -> uiState.emergencyUnlocksPerDay
        AppLimitDialogType.EMERGENCY_MINUTES -> uiState.emergencyMinutesPerUnlock
    }
    val title = when (dialogType) {
        AppLimitDialogType.HOURLY -> "Hourly limit (minutes)"
        AppLimitDialogType.DAILY -> "Daily limit (minutes)"
        AppLimitDialogType.OPENS -> "Launches per day"
        AppLimitDialogType.EMERGENCY_UNLOCKS -> "Emergency unlocks per day"
        AppLimitDialogType.EMERGENCY_MINUTES -> "Minutes per unlock"
    }
    var value by remember(dialogType, initialValue) { mutableStateOf(initialValue.toString()) }
    val maxValue = when (dialogType) {
        AppLimitDialogType.OPENS -> 100
        AppLimitDialogType.EMERGENCY_UNLOCKS -> MAX_EMERGENCY_UNLOCKS_PER_DAY
        AppLimitDialogType.HOURLY -> 60
        AppLimitDialogType.DAILY -> 1440
        AppLimitDialogType.EMERGENCY_MINUTES -> MAX_EMERGENCY_MINUTES_PER_UNLOCK
    }
    var isManualInputEnabled by remember(dialogType) { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(isManualInputEnabled) {
        if (isManualInputEnabled) {
            focusRequester.requestFocus()
            keyboardController?.show()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                TextButton(
                    onClick = { isManualInputEnabled = true },
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                ) {
                    AnimatedNumberCounter(
                        targetValue = value.toIntOrNull() ?: 0,
                        textStyle = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold)
                    )
                }
                Text(
                    text = "Tap the value to type with the number keyboard",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(bottom = 12.dp)
                )
                if (isManualInputEnabled) {
                    OutlinedTextField(
                        value = value,
                        onValueChange = { updated ->
                            val digitsOnly = updated.filter(Char::isDigit)
                            value = digitsOnly
                                .toIntOrNull()
                                ?.coerceIn(0, maxValue)
                                ?.toString()
                                ?: digitsOnly
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp)
                            .focusRequester(focusRequester),
                        singleLine = true,
                        label = { Text("Value") },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Done
                        )
                    )
                }
                Slider(
                    value = (value.toIntOrNull() ?: 0).toFloat(),
                    onValueChange = { value = it.toInt().toString() },
                    valueRange = 0f..maxValue.toFloat(),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onValueSelected((value.toIntOrNull() ?: 0).coerceIn(0, maxValue)) }) {
                Text("Apply")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
