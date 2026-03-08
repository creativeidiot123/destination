package com.ankit.destination.ui.groups

import android.app.TimePickerDialog
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.material3.AlertDialog
import androidx.compose.ui.text.input.ImeAction
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ankit.destination.policy.PolicyEngine
import com.ankit.destination.security.AppLockManager
import com.ankit.destination.ui.minuteToTimeLabel
import com.ankit.destination.ui.components.AdminSessionBanner
import com.ankit.destination.ui.components.AdminSessionDialog
import com.ankit.destination.ui.components.AnimatedNumberCounter
import com.ankit.destination.ui.components.AppPickerDialog
import com.ankit.destination.ui.components.collectAsStateWithLifecycleCompat
import com.ankit.destination.ui.components.showShortToast
import androidx.compose.material3.Slider
import com.ankit.destination.budgets.MAX_EMERGENCY_MINUTES_PER_UNLOCK
import com.ankit.destination.budgets.MAX_EMERGENCY_UNLOCKS_PER_DAY
import kotlinx.coroutines.flow.collectLatest

private val scheduleDayLabels = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

private enum class GroupLimitDialogType {
    HOURLY,
    DAILY,
    OPENS,
    PRIORITY,
    EMERGENCY_UNLOCKS,
    EMERGENCY_MINUTES
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
fun GroupDetailScreen(
    groupId: String?,
    policyEngine: PolicyEngine,
    appLockManager: AppLockManager,
    onBack: () -> Unit = {}
) {
    val context = LocalContext.current
    val viewModel: GroupDetailViewModel = viewModel(
        factory = GroupDetailViewModelFactory(
            appContext = context.applicationContext,
            policyEngine = policyEngine,
            appLockManager = appLockManager,
            groupId = groupId
        )
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycleCompat()

    var showAppPicker by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var activeDialog by remember { mutableStateOf<GroupLimitDialogType?>(null) }
    val toast = remember(context) { { message: String -> context.showShortToast(message) } }
    val groupMemberPickerDisabled = shouldDisableGroupMemberPicker(
        strictEnabled = uiState.strictEnabled,
        allAppsEnabled = uiState.allAppsTargetingEnabled
    )

    LaunchedEffect(viewModel) {
        viewModel.events.collectLatest { event ->
            when (event) {
                is GroupDetailEvent.Toast -> toast(event.message)
                is GroupDetailEvent.Finish -> {
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

    if (showAppPicker) {
        AppPickerDialog(
            title = "Apps in this group",
            options = uiState.availableApps,
            selectedPackageNames = uiState.memberPackages.toSet(),
            onDismiss = { showAppPicker = false },
            onConfirm = { selected ->
                showAppPicker = false
                viewModel.updateMemberPackages(selected)
                toast("Group apps updated.")
            }
        )
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete group?") },
            text = { Text("This removes the group, its app mappings, emergency settings, and schedule.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        viewModel.requestDelete()
                    }
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    activeDialog?.let { dialogType ->
        NumberInputDialog(
            dialogType = dialogType,
            uiState = uiState,
            onDismiss = { activeDialog = null },
            onValueSelected = { value ->
                activeDialog = null
                when (dialogType) {
                    GroupLimitDialogType.HOURLY -> viewModel.updateHourlyLimitMinutes(value)
                    GroupLimitDialogType.DAILY -> viewModel.updateDailyLimitMinutes(value)
                    GroupLimitDialogType.OPENS -> viewModel.updateOpensPerDay(value)
                    GroupLimitDialogType.PRIORITY -> viewModel.updatePriority(value)
                    GroupLimitDialogType.EMERGENCY_UNLOCKS -> {
                        viewModel.updateEmergencyConfig(value, uiState.minutesPerUnlock)
                    }
                    GroupLimitDialogType.EMERGENCY_MINUTES -> {
                        viewModel.updateEmergencyConfig(uiState.unlocksPerDay, value)
                    }
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (uiState.isNewGroup) "New Group" else "Edit Group") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (!uiState.isNewGroup) {
                        IconButton(
                            onClick = {
                                showDeleteDialog = true
                                toast("Delete confirmation opened.")
                            }
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete group")
                        }
                    }
                    TextButton(onClick = { viewModel.requestSave() }) {
                        Text("Save")
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

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 24.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    ElevatedCard(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                    ) {
                        Column(modifier = Modifier.padding(20.dp)) {
                            OutlinedTextField(
                                value = uiState.name,
                                onValueChange = viewModel::updateName,
                                label = { Text("Group name") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            SettingRow(
                                title = "Priority",
                                value = uiState.priorityIndex.toString(),
                                buttonLabel = "Edit"
                            ) {
                                activeDialog = GroupLimitDialogType.PRIORITY
                            }
                        }
                    }
                }

                item {
                    val blockConfigured = uiState.hasScheduleBlock
                    val blockActive = uiState.scheduleEnabled
                    val scheduleControlsAlpha = if (blockActive) 1f else 0.55f
                    val scheduleStatusLabel = when {
                        blockActive -> "Active block"
                        blockConfigured -> "Inactive block"
                        else -> "No block configured"
                    }
                    ElevatedCard(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.elevatedCardColors(
                            containerColor = if (blockConfigured && !blockActive) {
                                MaterialTheme.colorScheme.surfaceVariant
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerLow
                            }
                        )
                    ) {
                        Column(modifier = Modifier.padding(20.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Schedule,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = "Group Schedule",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Spacer(modifier = Modifier.weight(1f))
                                Switch(
                                    checked = uiState.scheduleEnabled,
                                    onCheckedChange = { enabled ->
                                        viewModel.toggleScheduleOption(enabled)
                                        toast(if (enabled) "Group schedule enabled." else "Group schedule disabled.")
                                    }
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Each group now owns its own schedule window.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            AssistChip(
                                onClick = { },
                                label = { Text(scheduleStatusLabel) },
                                colors = AssistChipDefaults.assistChipColors(
                                    containerColor = if (blockActive) {
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surfaceContainerHighest
                                    },
                                    labelColor = if (blockActive) {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                            )
                            if (blockConfigured) {
                                Spacer(modifier = Modifier.height(16.dp))
                                Column(modifier = Modifier.alpha(scheduleControlsAlpha)) {
                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        scheduleDayLabels.forEachIndexed { index, label ->
                                            FilterChip(
                                                selected = uiState.scheduleDaysMask and (1 shl index) != 0,
                                                enabled = blockActive,
                                                onClick = {
                                                    val isSelected = uiState.scheduleDaysMask and (1 shl index) != 0
                                                    viewModel.updateScheduleDay(index, !isSelected)
                                                    toast(
                                                        if (isSelected) {
                                                            "$label removed from schedule."
                                                        } else {
                                                            "$label added to schedule."
                                                        }
                                                    )
                                                },
                                                label = { Text(label) }
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        Button(
                                            enabled = blockActive,
                                            onClick = {
                                                TimePickerDialog(
                                                    context,
                                                    { _, hour, minute ->
                                                        viewModel.updateScheduleStart(hour, minute)
                                                        toast("Start time set to ${minuteToTimeLabel(hour * 60 + minute)}.")
                                                    },
                                                    uiState.scheduleStartMinute / 60,
                                                    uiState.scheduleStartMinute % 60,
                                                    true
                                                ).show()
                                            }
                                        ) {
                                            Text("Start ${minuteToTimeLabel(uiState.scheduleStartMinute)}")
                                        }
                                        Button(
                                            enabled = blockActive,
                                            onClick = {
                                                TimePickerDialog(
                                                    context,
                                                    { _, hour, minute ->
                                                        viewModel.updateScheduleEnd(hour, minute)
                                                        toast("End time set to ${minuteToTimeLabel(hour * 60 + minute)}.")
                                                    },
                                                    uiState.scheduleEndMinute / 60,
                                                    uiState.scheduleEndMinute % 60,
                                                    true
                                                ).show()
                                            }
                                        ) {
                                            Text("End ${minuteToTimeLabel(uiState.scheduleEndMinute)}")
                                        }
                                    }
                                }
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
                                text = "Limits",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            SettingRow(
                                title = "Hourly limit",
                                value = minutesLabel(uiState.hourlyLimitMs),
                                buttonLabel = "Edit"
                            ) {
                                activeDialog = GroupLimitDialogType.HOURLY
                                toast("Editing hourly limit.")
                            }
                            SettingRow(
                                title = "Daily limit",
                                value = minutesLabel(uiState.dailyLimitMs),
                                buttonLabel = "Edit"
                            ) {
                                activeDialog = GroupLimitDialogType.DAILY
                                toast("Editing daily limit.")
                            }
                            SettingRow(
                                title = "Launches per day",
                                value = if (uiState.opensPerDay > 0) uiState.opensPerDay.toString() else "No cap",
                                buttonLabel = "Edit"
                            ) {
                                activeDialog = GroupLimitDialogType.OPENS
                                toast("Editing launches per day.")
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Strict schedule mode",
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.weight(1f)
                                )
                                Switch(
                                    checked = uiState.strictEnabled,
                                    onCheckedChange = { enabled ->
                                        viewModel.toggleStrictOption(enabled)
                                        toast(if (enabled) "Strict schedule mode enabled." else "Strict schedule mode disabled.")
                                    }
                                )
                            }
                            if (uiState.strictEnabled) {
                                Spacer(modifier = Modifier.height(12.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "All apps",
                                        style = MaterialTheme.typography.bodyLarge,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Switch(
                                        checked = uiState.allAppsTargetingEnabled,
                                        onCheckedChange = { enabled ->
                                            viewModel.toggleAllAppsTargeting(enabled)
                                            toast(
                                                if (enabled) {
                                                    "All-apps schedule targeting enabled."
                                                } else {
                                                    "All-apps schedule targeting disabled."
                                                }
                                            )
                                        }
                                    )
                                }
                            }
                            if (uiState.strictEnabled && uiState.allAppsTargetingEnabled) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "During this schedule, all non-system launchable apps except protected and always-allowed apps will be blocked, including newly installed apps.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "Selected group apps are preserved for limits and will be editable again after All apps is turned off.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
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
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "Emergency Access",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f)
                                )
                                Switch(
                                    checked = uiState.emergencyEnabled,
                                    onCheckedChange = { enabled ->
                                        viewModel.toggleEmergency(enabled)
                                        toast(if (enabled) "Emergency access enabled." else "Emergency access disabled.")
                                    }
                                )
                            }
                            if (uiState.emergencyEnabled) {
                                Spacer(modifier = Modifier.height(12.dp))
                                SettingRow(
                                    title = "Unlocks per day",
                                    value = uiState.unlocksPerDay.toString(),
                                    buttonLabel = "Edit"
                                ) {
                                    activeDialog = GroupLimitDialogType.EMERGENCY_UNLOCKS
                                    toast("Editing emergency unlocks per day.")
                                }
                                SettingRow(
                                    title = "Minutes per unlock",
                                    value = uiState.minutesPerUnlock.toString(),
                                    buttonLabel = "Edit"
                                ) {
                                    activeDialog = GroupLimitDialogType.EMERGENCY_MINUTES
                                    toast("Editing emergency minutes per unlock.")
                                }
                            }
                        }
                    }
                }

                item {
                    ElevatedCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .alpha(if (groupMemberPickerDisabled) 0.55f else 1f),
                        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                    ) {
                        Column(modifier = Modifier.padding(20.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "Apps in Group",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(
                                    enabled = !groupMemberPickerDisabled,
                                    onClick = {
                                        showAppPicker = true
                                        toast("Selecting apps for this group.")
                                    }
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = "Manage apps")
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            if (groupMemberPickerDisabled) {
                                Text(
                                    text = "App selection is disabled while strict All apps schedule targeting is enabled.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                            }
                            if (uiState.memberPackages.isEmpty()) {
                                Text(
                                    text = "No apps selected yet.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    uiState.memberPackages.forEach { packageName ->
                                        val label = uiState.availableApps.firstOrNull { it.packageName == packageName }?.label ?: packageName
                                        AssistChip(
                                            enabled = !groupMemberPickerDisabled,
                                            onClick = {
                                                showAppPicker = true
                                                toast("Selecting apps for this group.")
                                            },
                                            label = { Text(label) },
                                            leadingIcon = {
                                                Icon(Icons.Default.Edit, contentDescription = null)
                                            }
                                        )
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
private fun SettingRow(
    title: String,
    value: String,
    buttonLabel: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        TextButton(onClick = onClick) {
            Text(buttonLabel)
        }
    }
}

@Composable
private fun NumberInputDialog(
    dialogType: GroupLimitDialogType,
    uiState: GroupDetailUiState,
    onDismiss: () -> Unit,
    onValueSelected: (Int) -> Unit
) {
    val initialValue = when (dialogType) {
        GroupLimitDialogType.HOURLY -> (uiState.hourlyLimitMs / 60_000L).toInt()
        GroupLimitDialogType.DAILY -> (uiState.dailyLimitMs / 60_000L).toInt()
        GroupLimitDialogType.OPENS -> uiState.opensPerDay
        GroupLimitDialogType.PRIORITY -> uiState.priorityIndex
        GroupLimitDialogType.EMERGENCY_UNLOCKS -> uiState.unlocksPerDay
        GroupLimitDialogType.EMERGENCY_MINUTES -> uiState.minutesPerUnlock
    }
    val title = when (dialogType) {
        GroupLimitDialogType.HOURLY -> "Hourly limit (minutes)"
        GroupLimitDialogType.DAILY -> "Daily limit (minutes)"
        GroupLimitDialogType.OPENS -> "Launches per day"
        GroupLimitDialogType.PRIORITY -> "Group priority"
        GroupLimitDialogType.EMERGENCY_UNLOCKS -> "Emergency unlocks per day"
        GroupLimitDialogType.EMERGENCY_MINUTES -> "Minutes per emergency unlock"
    }
    var text by remember(dialogType, initialValue) { mutableStateOf(initialValue.toString()) }
    val maxValue = when (dialogType) {
        GroupLimitDialogType.OPENS, GroupLimitDialogType.PRIORITY -> 100
        GroupLimitDialogType.EMERGENCY_UNLOCKS -> MAX_EMERGENCY_UNLOCKS_PER_DAY
        GroupLimitDialogType.HOURLY -> 60
        GroupLimitDialogType.DAILY -> 1440
        GroupLimitDialogType.EMERGENCY_MINUTES -> MAX_EMERGENCY_MINUTES_PER_UNLOCK
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
                        targetValue = text.toIntOrNull() ?: 0,
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
                        value = text,
                        onValueChange = { updated ->
                            val digitsOnly = updated.filter(Char::isDigit)
                            text = digitsOnly
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
                    value = (text.toIntOrNull() ?: 0).toFloat(),
                    onValueChange = { text = it.toInt().toString() },
                    valueRange = 0f..maxValue.toFloat(),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onValueSelected((text.toIntOrNull() ?: 0).coerceIn(0, maxValue))
                }
            ) {
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

private fun minutesLabel(limitMs: Long): String {
    val minutes = (limitMs / 60_000L).toInt()
    return if (minutes > 0) "$minutes min" else "No cap"
}
