package com.ankit.destination.ui.apps

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ankit.destination.budgets.BudgetOrchestrator
import com.ankit.destination.data.FocusDatabase
import com.ankit.destination.policy.EffectiveBlockReason
import com.ankit.destination.policy.PolicyEngine
import com.ankit.destination.security.AppLockManager
import com.ankit.destination.ui.RefreshCoordinator
import com.ankit.destination.ui.UiInvalidationBus
import com.ankit.destination.ui.buildSearchText
import com.ankit.destination.ui.loadInstalledAppOptions
import com.ankit.destination.ui.normalizeSearchQuery
import com.ankit.destination.ui.runCatchingNonCancellation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AppUsageItem(
    val packageName: String,
    val label: String,
    val usageTimeMs: Long,
    val blockMessage: String?,
    val hasCustomRules: Boolean,
    val opensToday: Int,
    val normalizedSearchText: String = buildSearchText(label, packageName)
)

internal data class AppUsageSummary(
    val totalUsageMinutes: Int = 0,
    val customRulesCount: Int = 0
)

data class IndividualAppsUiState(
    val apps: List<AppUsageItem> = emptyList(),
    val filteredApps: List<AppUsageItem> = emptyList(),
    val totalUsageMinutes: Int = 0,
    val customRulesCount: Int = 0,
    val searchQuery: String = "",
    val selectedFilter: AppFilter = AppFilter.All,
    val isLoading: Boolean = true,
    val adminSessionActive: Boolean = false,
    val adminSessionRemainingMs: Long = 0,
    val showAuthDialog: Boolean = false,
    val pendingActionPkg: String? = null
)

class IndividualAppsViewModel(
    private val appContext: Context,
    private val policyEngine: PolicyEngine,
    private val appLockManager: AppLockManager
) : ViewModel() {
    private val db by lazy { FocusDatabase.get(appContext) }
    private val budgetOrchestrator by lazy { BudgetOrchestrator(appContext) }
    private val refreshCoordinator = RefreshCoordinator()
    
    private val _rawState = MutableStateFlow(IndividualAppsUiState())
    private val _searchQuery = MutableStateFlow("")
    private val _selectedFilter = MutableStateFlow(AppFilter.All)

    private val appsFlow = _rawState
        .mapLatest { it.apps }
        .distinctUntilChanged()

    private val summariesFlow = appsFlow
        .mapLatest(::summarizeAppUsageItems)
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)

    private val filteredAppsFlow = combine(
        appsFlow,
        _searchQuery,
        _selectedFilter
    ) { apps, query, filter ->
        FilteredAppsRequest(
            apps = apps,
            query = query,
            filter = filter
        )
    }.mapLatest { request ->
        filterAppUsageItems(
            apps = request.apps,
            query = request.query,
            filter = request.filter
        )
    }.distinctUntilChanged()
        .flowOn(Dispatchers.Default)
    
    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<IndividualAppsUiState> = combine(
        _rawState,
        _searchQuery,
        _selectedFilter
    ) { state, query, filter ->
        Triple(state, query, filter)
    }.combine(
        summariesFlow
    ) { (state, query, filter), summary ->
        StateAndSummary(state, query, filter, summary)
    }.combine(
        filteredAppsFlow
    ) { stateAndSummary, filteredApps ->
        stateAndSummary.state.copy(
            searchQuery = stateAndSummary.query,
            selectedFilter = stateAndSummary.filter,
            filteredApps = filteredApps,
            totalUsageMinutes = stateAndSummary.summary.totalUsageMinutes,
            customRulesCount = stateAndSummary.summary.customRulesCount
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = _rawState.value
    )

    private var hasLoadedOnce = false
    private var lastHandledInvalidationVersion = UiInvalidationBus.latest.value.version

    init {
        refresh(force = true)
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }
    
    fun updateFilter(filter: AppFilter) {
        _selectedFilter.value = filter
    }

    fun refresh(force: Boolean = false) {
        if (!refreshCoordinator.tryStart(force)) return

        viewModelScope.launch {
            var shouldRerun: Boolean
            do {
                var loadSucceeded = false
                try {
                    if (!hasLoadedOnce) {
                        _rawState.update { it.copy(isLoading = true) }
                    }
                    val refreshed = withContext(Dispatchers.IO) {
                        runCatchingNonCancellation {
                            val adminActive = appLockManager.isAdminSessionActive()
                            val remainingMs = appLockManager.getSessionRemainingMs()
                            val policySnapshot = policyEngine.uiSnapshot()
                            val protectionSnapshot = policyEngine.getAppProtectionSnapshotAsync()
                            val appPolicies = db.budgetDao().getAllAppPolicies().associateBy { it.packageName }
                            val usageInputs = budgetOrchestrator.readUsageSnapshot().usageInputs
                            val launchableApps = loadInstalledAppOptions(
                                context = appContext,
                                includePackageNames = appPolicies.keys,
                                protectionSnapshot = protectionSnapshot
                            )
                            IndividualAppsUiState(
                                apps = launchableApps
                                    .map { option ->
                                        val policy = appPolicies[option.packageName]
                                        val usedToday = usageInputs.usedTodayMs[option.packageName] ?: 0L
                                        val opensToday = usageInputs.opensToday[option.packageName] ?: 0
                                        val rawReason = policySnapshot.primaryReasonByPackage[option.packageName]
                                        val customRules = policy != null && (
                                            policy.dailyLimitMs > 0L ||
                                                policy.hourlyLimitMs > 0L ||
                                                policy.opensPerDay > 0 ||
                                                policy.emergencyEnabled
                                            )
                                        AppUsageItem(
                                            packageName = option.packageName,
                                            label = option.label,
                                            usageTimeMs = usedToday,
                                            blockMessage = blockMessageFor(
                                                rawReason = rawReason,
                                                blocked = rawReason != null ||
                                                    policySnapshot.budgetBlockedPackages.contains(option.packageName),
                                                budgetBlocked = policySnapshot.budgetBlockedPackages.contains(option.packageName)
                                            ),
                                            hasCustomRules = customRules,
                                            opensToday = opensToday
                                        )
                                    }
                                    .sortedWith(
                                        compareByDescending<AppUsageItem> { it.hasCustomRules }
                                            .thenByDescending { it.blockMessage != null }
                                            .thenByDescending { it.usageTimeMs }
                                            .thenBy { it.label.lowercase() }
                                    ),
                                isLoading = false,
                                adminSessionActive = adminActive,
                                adminSessionRemainingMs = remainingMs
                            )
                        }
                    }

                    refreshed.onSuccess { state ->
                        _rawState.value = state
                        hasLoadedOnce = true
                        loadSucceeded = true
                    }.onFailure {
                        _rawState.update {
                            it.copy(
                                isLoading = false,
                                adminSessionActive = appLockManager.isAdminSessionActive(),
                                adminSessionRemainingMs = appLockManager.getSessionRemainingMs()
                            )
                        }
                    }
                } finally {
                    shouldRerun = refreshCoordinator.finish(loadSucceeded)
                }
            } while (shouldRerun)
        }
    }

    fun onInvalidation(version: Long) {
        if (version <= 0L || version <= lastHandledInvalidationVersion) return
        lastHandledInvalidationVersion = version
        refresh(force = true)
    }

    fun attemptEditApp(packageName: String, navigate: (String) -> Unit) {
        if (!_rawState.value.adminSessionActive && appLockManager.isProtectionEnabled()) {
            _rawState.update { it.copy(showAuthDialog = true, pendingActionPkg = packageName) }
            return
        }
        navigate(packageName)
    }

    fun dismissAuthDialog() {
        _rawState.update { it.copy(showAuthDialog = false, pendingActionPkg = null) }
    }

    fun onAuthenticated(navigate: (String) -> Unit) {
        val pendingPkg = _rawState.value.pendingActionPkg
        dismissAuthDialog()
        refresh(force = true)
        if (pendingPkg != null) {
            navigate(pendingPkg)
        }
    }
}

internal fun summarizeAppUsageItems(apps: List<AppUsageItem>): AppUsageSummary {
    return AppUsageSummary(
        totalUsageMinutes = (apps.sumOf { it.usageTimeMs } / 60_000L).toInt(),
        customRulesCount = apps.count { it.hasCustomRules }
    )
}

internal fun filterAppUsageItems(
    apps: List<AppUsageItem>,
    query: String,
    filter: AppFilter
): List<AppUsageItem> {
    val normalizedQuery = normalizeSearchQuery(query)
    return apps.filter { app ->
        val matchesSearch = normalizedQuery.isBlank() ||
            app.normalizedSearchText.contains(normalizedQuery)
        val matchesFilter = when (filter) {
            AppFilter.All -> true
            AppFilter.Blocked -> app.blockMessage != null
            AppFilter.CustomRules -> app.hasCustomRules
        }
        matchesSearch && matchesFilter
    }
}

private data class FilteredAppsRequest(
    val apps: List<AppUsageItem>,
    val query: String,
    val filter: AppFilter
)

private data class StateAndSummary(
    val state: IndividualAppsUiState,
    val query: String,
    val filter: AppFilter,
    val summary: AppUsageSummary
)

private fun blockMessageFor(rawReason: String?, blocked: Boolean, budgetBlocked: Boolean): String? {
    if (!blocked) return null

    val normalized = rawReason
        ?.trim()
        ?.uppercase()
        .orEmpty()

    return when {
        normalized == EffectiveBlockReason.ALWAYS_BLOCKED.name -> "Always blocked"
        normalized == EffectiveBlockReason.STRICT_INSTALL.name -> "Blocked during strict schedule"
        normalized == EffectiveBlockReason.ACCESSIBILITY_RECOVERY_LOCKDOWN.name ->
            "Blocked - Accessibility recovery required"
        normalized == EffectiveBlockReason.USAGE_ACCESS_RECOVERY_LOCKDOWN.name ->
            "Blocked - Usage Access recovery required"
        normalized.isBlank() && budgetBlocked -> "Usage limit reached"
        normalized == "SCHEDULE_GROUP" ||
            normalized.contains("SCHEDULED_BLOCK") ||
            normalized.endsWith("_SCHEDULED_BLOCK") -> {
            if (budgetBlocked) {
                "Scheduled block active. Usage limit remains after the schedule ends."
            } else {
                "Scheduled block active"
            }
        }
        normalized == "BUDGET" ||
            normalized.contains("USAGE_BLOCK") ||
            normalized.contains("HOURLY_CAP") ||
            normalized.contains("DAILY_CAP") -> "Usage limit reached"
        normalized.contains("OPENS_CAP") -> "Launch limit reached"
        else -> "Blocked"
    }
}

class IndividualAppsViewModelFactory(
    private val appContext: Context,
    private val policyEngine: PolicyEngine,
    private val appLockManager: AppLockManager
) : androidx.lifecycle.ViewModelProvider.Factory {
    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
        return IndividualAppsViewModel(appContext, policyEngine, appLockManager) as T
    }
}
