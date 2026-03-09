package com.ankit.destination.ui.diagnostics

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.ankit.destination.data.AlwaysAllowedApp
import com.ankit.destination.data.AlwaysBlockedApp
import com.ankit.destination.data.AppGroupMap
import com.ankit.destination.data.AppPolicy
import com.ankit.destination.data.DomainRule
import com.ankit.destination.data.EmergencyState
import com.ankit.destination.data.FocusDatabase
import com.ankit.destination.data.GlobalControls
import com.ankit.destination.data.GroupEmergencyConfig
import com.ankit.destination.data.GroupLimit
import com.ankit.destination.data.HiddenApp
import com.ankit.destination.data.ScheduleBlock
import com.ankit.destination.data.ScheduleBlockApp
import com.ankit.destination.data.ScheduleBlockGroup
import com.ankit.destination.data.UninstallProtectedApp
import com.ankit.destination.data.UsageSnapshot
import com.ankit.destination.policy.PolicyEngine
import com.ankit.destination.policy.PolicyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class DiagnosticsBackupManager(context: Context) {
    private val appContext = context.applicationContext
    private val db by lazy { FocusDatabase.get(appContext) }
    private val store by lazy { PolicyStore(appContext) }

    suspend fun exportToUri(uri: Uri) {
        withContext(Dispatchers.IO) {
            val payload = buildBackupJson().toString(2)
            val outputStream = appContext.contentResolver.openOutputStream(uri)
                ?: error("Unable to open backup destination.")
            outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.write(payload)
            }
        }
    }

    suspend fun importFromUri(uri: Uri, policyEngine: PolicyEngine) {
        withContext(Dispatchers.IO) {
            val inputStream = appContext.contentResolver.openInputStream(uri)
                ?: error("Unable to open backup file.")
            val snapshot = inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                parseBackupJson(reader.readText())
            }
            restoreSnapshot(snapshot)
            check(store.replaceAllPreferences(snapshot.preferences, commitSynchronously = true)) {
                "Unable to persist restored settings."
            }
            policyEngine.invalidatePolicyControlsForDiagnosticsImport()
        }
    }

    private suspend fun buildBackupJson(): JSONObject {
        val scheduleDao = db.scheduleDao()
        val budgetDao = db.budgetDao()
        return JSONObject().apply {
            put("version", BACKUP_VERSION)
            put("createdAtMs", System.currentTimeMillis())
            put("preferences", DiagnosticsPreferenceJsonCodec.toJson(store.exportAllPreferences()))
            put("globalControls", budgetDao.getGlobalControls()?.toJson() ?: JSONObject.NULL)
            put("scheduleBlocks", scheduleDao.getAllBlocks().toJsonArray { it.toJson() })
            put("scheduleBlockGroups", scheduleDao.getAllBlockGroups().toJsonArray { it.toJson() })
            put("scheduleBlockApps", scheduleDao.getAllBlockApps().toJsonArray { it.toJson() })
            put("appPolicies", budgetDao.getAllAppPolicies().toJsonArray { it.toJson() })
            put("groupLimits", budgetDao.getAllGroupLimits().toJsonArray { it.toJson() })
            put("appGroupMappings", budgetDao.getAllMappings().toJsonArray { it.toJson() })
            put("usageSnapshots", budgetDao.getAllUsageSnapshots().toJsonArray { it.toJson() })
            put(
                "groupEmergencyConfigs",
                budgetDao.getAllGroupEmergencyConfigs().toJsonArray { it.toJson() }
            )
            put("emergencyStates", budgetDao.getAllEmergencyStates().toJsonArray { it.toJson() })
            put("domainRules", budgetDao.getAllDomainRules().toJsonArray { it.toJson() })
            put("alwaysAllowedPackages", budgetDao.getAlwaysAllowedPackages().toStringArray())
            put("alwaysBlockedPackages", budgetDao.getAlwaysBlockedPackages().toStringArray())
            put(
                "uninstallProtectedPackages",
                budgetDao.getUninstallProtectedPackages().toStringArray()
            )
            put("hiddenApps", budgetDao.getHiddenApps().toJsonArray { it.toJson() })
        }
    }

    private fun parseBackupJson(raw: String): BackupSnapshot {
        val root = JSONObject(raw)
        val version = root.optInt("version", -1)
        require(version == 1 || version == BACKUP_VERSION) {
            "Unsupported backup version: $version"
        }
        val legacyScheduleGroups = root.optJSONArray("scheduleBlockGroups")
            .toList { it.toScheduleBlockGroup() }
        val scheduleBlockApps = if (version >= 2) {
            root.optJSONArray("scheduleBlockApps").toList { it.toScheduleBlockApp() }
        } else {
            legacyScheduleGroups
                .asSequence()
                .mapNotNull { row ->
                    decodeLegacyScheduleAppTarget(row.groupId)?.let { packageName ->
                        ScheduleBlockApp(blockId = row.blockId, packageName = packageName)
                    }
                }
                .toList()
        }
        return BackupSnapshot(
            preferences = DiagnosticsPreferenceJsonCodec.fromJson(root.getJSONObject("preferences")),
            globalControls = root.optJSONObject("globalControls")?.toGlobalControls(),
            scheduleBlocks = root.optJSONArray("scheduleBlocks").toList { it.toScheduleBlock() },
            scheduleBlockGroups = legacyScheduleGroups
                .filterNot { decodeLegacyScheduleAppTarget(it.groupId) != null },
            scheduleBlockApps = scheduleBlockApps,
            appPolicies = root.optJSONArray("appPolicies").toList { it.toAppPolicy() },
            groupLimits = root.optJSONArray("groupLimits").toList { it.toGroupLimit() },
            appGroupMappings = root.optJSONArray("appGroupMappings").toList { it.toAppGroupMap() },
            usageSnapshots = root.optJSONArray("usageSnapshots").toList { it.toUsageSnapshot() },
            groupEmergencyConfigs = root.optJSONArray("groupEmergencyConfigs")
                .toList { it.toGroupEmergencyConfig() },
            emergencyStates = root.optJSONArray("emergencyStates").toList { it.toEmergencyState() },
            domainRules = root.optJSONArray("domainRules").toList { it.toDomainRule() },
            alwaysAllowedPackages = root.optJSONArray("alwaysAllowedPackages").toStringList(),
            alwaysBlockedPackages = root.optJSONArray("alwaysBlockedPackages").toStringList(),
            uninstallProtectedPackages = root.optJSONArray("uninstallProtectedPackages")
                .toStringList(),
            hiddenApps = root.optJSONArray("hiddenApps").toList { it.toHiddenApp() }
        ).normalized()
    }

    private suspend fun restoreSnapshot(snapshot: BackupSnapshot) {
        val scheduleDao = db.scheduleDao()
        val budgetDao = db.budgetDao()
        val enforcementStateDao = db.enforcementStateDao()
        db.withTransaction {
            scheduleDao.clearAllSchedules()
            budgetDao.resetAllPolicyData(snapshot.globalControls ?: GlobalControls())
            enforcementStateDao.clear()

            snapshot.scheduleBlocks.forEach { scheduleDao.upsert(it) }
            if (snapshot.scheduleBlockGroups.isNotEmpty()) {
                scheduleDao.insertBlockGroups(snapshot.scheduleBlockGroups)
            }
            if (snapshot.scheduleBlockApps.isNotEmpty()) {
                scheduleDao.insertBlockApps(snapshot.scheduleBlockApps)
            }
            snapshot.appPolicies.forEach { budgetDao.upsertAppPolicy(it) }
            snapshot.groupLimits.forEach { budgetDao.upsertGroupLimit(it) }
            snapshot.appGroupMappings.forEach { budgetDao.upsertMapping(it) }
            if (snapshot.usageSnapshots.isNotEmpty()) {
                budgetDao.upsertUsageSnapshots(snapshot.usageSnapshots)
            }
            snapshot.groupEmergencyConfigs.forEach { budgetDao.upsertGroupEmergencyConfig(it) }
            snapshot.emergencyStates.forEach { budgetDao.upsertEmergencyState(it) }
            snapshot.domainRules.forEach { budgetDao.upsertDomainRule(it) }
            snapshot.alwaysAllowedPackages.forEach {
                budgetDao.upsertAlwaysAllowed(AlwaysAllowedApp(it))
            }
            snapshot.alwaysBlockedPackages.forEach {
                budgetDao.upsertAlwaysBlocked(AlwaysBlockedApp(it))
            }
            snapshot.uninstallProtectedPackages.forEach {
                budgetDao.upsertUninstallProtected(UninstallProtectedApp(it))
            }
            snapshot.hiddenApps.forEach { budgetDao.upsertHiddenApp(it) }
        }
    }

    private data class BackupSnapshot(
        val preferences: Map<String, Any?>,
        val globalControls: GlobalControls?,
        val scheduleBlocks: List<ScheduleBlock>,
        val scheduleBlockGroups: List<ScheduleBlockGroup>,
        val scheduleBlockApps: List<ScheduleBlockApp>,
        val appPolicies: List<AppPolicy>,
        val groupLimits: List<GroupLimit>,
        val appGroupMappings: List<AppGroupMap>,
        val usageSnapshots: List<UsageSnapshot>,
        val groupEmergencyConfigs: List<GroupEmergencyConfig>,
        val emergencyStates: List<EmergencyState>,
        val domainRules: List<DomainRule>,
        val alwaysAllowedPackages: List<String>,
        val alwaysBlockedPackages: List<String>,
        val uninstallProtectedPackages: List<String>,
        val hiddenApps: List<HiddenApp>
    ) {
        fun normalized(): BackupSnapshot {
            val cleanScheduleBlocks = scheduleBlocks
                .asSequence()
                .map {
                    it.copy(
                        name = it.name.trim(),
                        daysMask = it.daysMask.coerceIn(0, 0b1111111),
                        startMinute = it.startMinute.coerceIn(0, 1439),
                        endMinute = it.endMinute.coerceIn(0, 1439)
                    )
                }
                .filter { it.id > 0L && it.name.isNotBlank() }
                .distinctBy { it.id }
                .sortedBy { it.id }
                .toList()
            val validBlockIds = cleanScheduleBlocks.mapTo(linkedSetOf()) { it.id }
            return copy(
                preferences = preferences.toSortedMap(),
                scheduleBlocks = cleanScheduleBlocks,
                scheduleBlockGroups = scheduleBlockGroups
                    .asSequence()
                    .map { it.copy(groupId = it.groupId.trim()) }
                    .filter { it.groupId.isNotBlank() && it.blockId in validBlockIds }
                    .distinctBy { it.blockId to it.groupId }
                    .sortedWith(compareBy<ScheduleBlockGroup> { it.blockId }.thenBy { it.groupId })
                    .toList(),
                scheduleBlockApps = scheduleBlockApps
                    .asSequence()
                    .map { it.copy(packageName = it.packageName.trim()) }
                    .filter { it.packageName.isNotBlank() && it.blockId in validBlockIds }
                    .distinctBy { it.blockId to it.packageName }
                    .sortedWith(compareBy<ScheduleBlockApp> { it.blockId }.thenBy { it.packageName })
                    .toList(),
                appPolicies = appPolicies
                    .asSequence()
                    .map {
                        it.copy(
                            packageName = it.packageName.trim(),
                            dailyLimitMs = it.dailyLimitMs.coerceAtLeast(0L),
                            hourlyLimitMs = it.hourlyLimitMs.coerceAtLeast(0L),
                            opensPerDay = it.opensPerDay.coerceAtLeast(0),
                            unlocksPerDay = it.unlocksPerDay.coerceAtLeast(0),
                            minutesPerUnlock = it.minutesPerUnlock.coerceAtLeast(0)
                        )
                    }
                    .filter { it.packageName.isNotBlank() }
                    .distinctBy { it.packageName }
                    .sortedBy { it.packageName }
                    .toList(),
                groupLimits = groupLimits
                    .asSequence()
                    .map {
                        it.copy(
                            groupId = it.groupId.trim(),
                            name = it.name.trim(),
                            priorityIndex = it.priorityIndex.coerceAtLeast(0),
                            dailyLimitMs = it.dailyLimitMs.coerceAtLeast(0L),
                            hourlyLimitMs = it.hourlyLimitMs.coerceAtLeast(0L),
                            opensPerDay = it.opensPerDay.coerceAtLeast(0)
                        )
                    }
                    .filter { it.groupId.isNotBlank() && it.name.isNotBlank() }
                    .distinctBy { it.groupId }
                    .sortedBy { it.groupId }
                    .toList(),
                appGroupMappings = appGroupMappings
                    .asSequence()
                    .map { it.copy(packageName = it.packageName.trim(), groupId = it.groupId.trim()) }
                    .filter { it.packageName.isNotBlank() && it.groupId.isNotBlank() }
                    .distinctBy { it.packageName to it.groupId }
                    .sortedWith(compareBy<AppGroupMap> { it.packageName }.thenBy { it.groupId })
                    .toList(),
                usageSnapshots = usageSnapshots
                    .asSequence()
                    .map {
                        it.copy(
                            windowKey = it.windowKey.trim(),
                            packageName = it.packageName.trim(),
                            foregroundMs = it.foregroundMs.coerceAtLeast(0L),
                            opens = it.opens.coerceAtLeast(0)
                        )
                    }
                    .filter { it.windowKey.isNotBlank() && it.packageName.isNotBlank() }
                    .distinctBy { it.windowKey to it.packageName }
                    .sortedWith(compareBy<UsageSnapshot> { it.windowKey }.thenBy { it.packageName })
                    .toList(),
                groupEmergencyConfigs = groupEmergencyConfigs
                    .asSequence()
                    .map {
                        it.copy(
                            groupId = it.groupId.trim(),
                            unlocksPerDay = it.unlocksPerDay.coerceAtLeast(0),
                            minutesPerUnlock = it.minutesPerUnlock.coerceAtLeast(0)
                        )
                    }
                    .filter { it.groupId.isNotBlank() }
                    .distinctBy { it.groupId }
                    .sortedBy { it.groupId }
                    .toList(),
                emergencyStates = emergencyStates
                    .asSequence()
                    .map {
                        it.copy(
                            dayKey = it.dayKey.trim(),
                            targetType = it.targetType.trim(),
                            targetId = it.targetId.trim(),
                            unlocksUsedToday = it.unlocksUsedToday.coerceAtLeast(0)
                        )
                    }
                    .filter { it.dayKey.isNotBlank() && it.targetType.isNotBlank() && it.targetId.isNotBlank() }
                    .distinctBy { Triple(it.dayKey, it.targetType, it.targetId) }
                    .sortedWith(
                        compareBy<EmergencyState> { it.dayKey }
                            .thenBy { it.targetType }
                            .thenBy { it.targetId }
                    )
                    .toList(),
                domainRules = domainRules
                    .asSequence()
                    .map {
                        it.copy(
                            domain = it.domain.trim(),
                            scopeType = it.scopeType.trim(),
                            scopeId = it.scopeId.trim()
                        )
                    }
                    .filter { it.domain.isNotBlank() && it.scopeType.isNotBlank() && it.scopeId.isNotBlank() }
                    .distinctBy { Triple(it.domain, it.scopeType, it.scopeId) }
                    .sortedWith(
                        compareBy<DomainRule> { it.scopeType }
                            .thenBy { it.scopeId }
                            .thenBy { it.domain }
                    )
                    .toList(),
                alwaysAllowedPackages = sanitizePackages(alwaysAllowedPackages),
                alwaysBlockedPackages = sanitizePackages(alwaysBlockedPackages),
                uninstallProtectedPackages = sanitizePackages(uninstallProtectedPackages),
                hiddenApps = hiddenApps
                    .asSequence()
                    .map { it.copy(packageName = it.packageName.trim()) }
                    .filter { it.packageName.isNotBlank() }
                    .distinctBy { it.packageName }
                    .sortedBy { it.packageName }
                    .toList()
            )
        }
    }

    private fun GlobalControls.toJson(): JSONObject = JSONObject().apply {
        put("lockTime", lockTime)
        put("lockVpnDns", lockVpnDns)
        put("lockDevOptions", lockDevOptions)
        put("disableSafeMode", disableSafeMode)
        put("lockUserCreation", lockUserCreation)
        put("lockWorkProfile", lockWorkProfile)
        put("lockCloningBestEffort", lockCloningBestEffort)
        put("dangerUnenrollEnabled", dangerUnenrollEnabled)
        put("managedNetworkMode", managedNetworkMode)
        put("managedVpnPackage", managedVpnPackage ?: JSONObject.NULL)
        put("managedVpnLockdown", managedVpnLockdown)
        put("privateDnsMode", privateDnsMode)
        put("privateDnsHost", privateDnsHost ?: JSONObject.NULL)
    }

    private fun JSONObject.toGlobalControls(): GlobalControls {
        return GlobalControls(
            id = 1,
            lockTime = optBoolean("lockTime", false),
            lockVpnDns = optBoolean("lockVpnDns", true),
            lockDevOptions = optBoolean("lockDevOptions", false),
            disableSafeMode = optBoolean("disableSafeMode", false),
            lockUserCreation = optBoolean("lockUserCreation", false),
            lockWorkProfile = optBoolean("lockWorkProfile", false),
            lockCloningBestEffort = optBoolean("lockCloningBestEffort", false),
            dangerUnenrollEnabled = optBoolean("dangerUnenrollEnabled", false),
            managedNetworkMode = optString("managedNetworkMode", "UNMANAGED"),
            managedVpnPackage = optNullableString("managedVpnPackage"),
            managedVpnLockdown = optBoolean("managedVpnLockdown", true),
            privateDnsMode = optString("privateDnsMode", "OPPORTUNISTIC"),
            privateDnsHost = optNullableString("privateDnsHost")
        )
    }

    private fun ScheduleBlock.toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("daysMask", daysMask)
        put("startMinute", startMinute)
        put("endMinute", endMinute)
        put("enabled", enabled)
        put("kind", kind)
        put("strict", strict)
        put("immutable", immutable)
        put("timezoneMode", timezoneMode)
    }

    private fun JSONObject.toScheduleBlock(): ScheduleBlock {
        return ScheduleBlock(
            id = optLong("id", 0L),
            name = getString("name"),
            daysMask = getInt("daysMask"),
            startMinute = getInt("startMinute"),
            endMinute = getInt("endMinute"),
            enabled = optBoolean("enabled", true),
            kind = optString("kind", "GROUPS"),
            strict = optBoolean("strict", false),
            immutable = optBoolean("immutable", false),
            timezoneMode = optString("timezoneMode", "DEVICE_LOCAL")
        )
    }

    private fun ScheduleBlockGroup.toJson(): JSONObject = JSONObject().apply {
        put("blockId", blockId)
        put("groupId", groupId)
    }

    private fun JSONObject.toScheduleBlockGroup(): ScheduleBlockGroup {
        return ScheduleBlockGroup(
            blockId = getLong("blockId"),
            groupId = getString("groupId")
        )
    }

    private fun ScheduleBlockApp.toJson(): JSONObject = JSONObject().apply {
        put("blockId", blockId)
        put("packageName", packageName)
    }

    private fun JSONObject.toScheduleBlockApp(): ScheduleBlockApp {
        return ScheduleBlockApp(
            blockId = getLong("blockId"),
            packageName = getString("packageName")
        )
    }

    private fun AppPolicy.toJson(): JSONObject = JSONObject().apply {
        put("packageName", packageName)
        put("dailyLimitMs", dailyLimitMs)
        put("hourlyLimitMs", hourlyLimitMs)
        put("opensPerDay", opensPerDay)
        put("enabled", enabled)
        put("emergencyEnabled", emergencyEnabled)
        put("unlocksPerDay", unlocksPerDay)
        put("minutesPerUnlock", minutesPerUnlock)
    }

    private fun JSONObject.toAppPolicy(): AppPolicy {
        return AppPolicy(
            packageName = getString("packageName"),
            dailyLimitMs = getLong("dailyLimitMs"),
            hourlyLimitMs = optLong("hourlyLimitMs", Long.MAX_VALUE),
            opensPerDay = optInt("opensPerDay", Int.MAX_VALUE),
            enabled = optBoolean("enabled", true),
            emergencyEnabled = optBoolean("emergencyEnabled", false),
            unlocksPerDay = optInt("unlocksPerDay", 0),
            minutesPerUnlock = optInt("minutesPerUnlock", 0)
        )
    }

    private fun GroupLimit.toJson(): JSONObject = JSONObject().apply {
        put("groupId", groupId)
        put("name", name)
        put("priorityIndex", priorityIndex)
        put("dailyLimitMs", dailyLimitMs)
        put("hourlyLimitMs", hourlyLimitMs)
        put("opensPerDay", opensPerDay)
        put("strictEnabled", strictEnabled)
        put("scheduleTargetMode", scheduleTargetMode)
        put("enabled", enabled)
    }

    private fun JSONObject.toGroupLimit(): GroupLimit {
        return GroupLimit(
            groupId = getString("groupId"),
            name = getString("name"),
            priorityIndex = optInt("priorityIndex", 1000),
            dailyLimitMs = getLong("dailyLimitMs"),
            hourlyLimitMs = getLong("hourlyLimitMs"),
            opensPerDay = getInt("opensPerDay"),
            strictEnabled = optBoolean("strictEnabled", false),
            scheduleTargetMode = optString("scheduleTargetMode", "SELECTED_APPS"),
            enabled = optBoolean("enabled", true)
        )
    }

    private fun AppGroupMap.toJson(): JSONObject = JSONObject().apply {
        put("packageName", packageName)
        put("groupId", groupId)
    }

    private fun JSONObject.toAppGroupMap(): AppGroupMap {
        return AppGroupMap(
            packageName = getString("packageName"),
            groupId = getString("groupId")
        )
    }

    private fun UsageSnapshot.toJson(): JSONObject = JSONObject().apply {
        put("windowKey", windowKey)
        put("packageName", packageName)
        put("foregroundMs", foregroundMs)
        put("opens", opens)
    }

    private fun JSONObject.toUsageSnapshot(): UsageSnapshot {
        return UsageSnapshot(
            windowKey = getString("windowKey"),
            packageName = getString("packageName"),
            foregroundMs = getLong("foregroundMs"),
            opens = getInt("opens")
        )
    }

    private fun GroupEmergencyConfig.toJson(): JSONObject = JSONObject().apply {
        put("groupId", groupId)
        put("enabled", enabled)
        put("unlocksPerDay", unlocksPerDay)
        put("minutesPerUnlock", minutesPerUnlock)
    }

    private fun JSONObject.toGroupEmergencyConfig(): GroupEmergencyConfig {
        return GroupEmergencyConfig(
            groupId = getString("groupId"),
            enabled = optBoolean("enabled", false),
            unlocksPerDay = optInt("unlocksPerDay", 0),
            minutesPerUnlock = optInt("minutesPerUnlock", 0)
        )
    }

    private fun EmergencyState.toJson(): JSONObject = JSONObject().apply {
        put("dayKey", dayKey)
        put("targetType", targetType)
        put("targetId", targetId)
        put("unlocksUsedToday", unlocksUsedToday)
        put("activeUntilEpochMs", activeUntilEpochMs ?: JSONObject.NULL)
    }

    private fun JSONObject.toEmergencyState(): EmergencyState {
        return EmergencyState(
            dayKey = getString("dayKey"),
            targetType = getString("targetType"),
            targetId = getString("targetId"),
            unlocksUsedToday = getInt("unlocksUsedToday"),
            activeUntilEpochMs = optNullableLong("activeUntilEpochMs")
        )
    }

    private fun DomainRule.toJson(): JSONObject = JSONObject().apply {
        put("domain", domain)
        put("scopeType", scopeType)
        put("scopeId", scopeId)
        put("blocked", blocked)
    }

    private fun JSONObject.toDomainRule(): DomainRule {
        return DomainRule(
            domain = getString("domain"),
            scopeType = getString("scopeType"),
            scopeId = getString("scopeId"),
            blocked = getBoolean("blocked")
        )
    }

    private fun HiddenApp.toJson(): JSONObject = JSONObject().apply {
        put("packageName", packageName)
        put("locked", locked)
    }

    private fun JSONObject.toHiddenApp(): HiddenApp {
        return HiddenApp(
            packageName = getString("packageName"),
            locked = optBoolean("locked", false)
        )
    }

    private fun <T> List<T>.toJsonArray(serializer: (T) -> JSONObject): JSONArray {
        return JSONArray().apply {
            forEach { put(serializer(it)) }
        }
    }

    private fun Collection<String>.toStringArray(): JSONArray {
        return JSONArray().apply {
            this@toStringArray.forEach { put(it) }
        }
    }

    private fun <T> JSONArray?.toList(parser: (JSONObject) -> T): List<T> {
        if (this == null) return emptyList()
        return buildList(length()) {
            for (index in 0 until length()) {
                add(parser(getJSONObject(index)))
            }
        }
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return buildList(length()) {
            for (index in 0 until length()) {
                add(getString(index))
            }
        }
    }

    private fun JSONObject.optNullableLong(name: String): Long? {
        return if (isNull(name)) null else getLong(name)
    }

    private fun JSONObject.optNullableString(name: String): String? {
        return if (isNull(name)) null else getString(name)
    }

    companion object {
        private const val BACKUP_VERSION = 2

        private fun decodeLegacyScheduleAppTarget(raw: String): String? {
            val trimmed = raw.trim()
            return if (trimmed.startsWith("app:") && trimmed.length > 4) {
                trimmed.removePrefix("app:").trim().takeIf(String::isNotBlank)
            } else {
                null
            }
        }

        private fun sanitizePackages(values: Collection<String>): List<String> {
            return values.asSequence()
                .map(String::trim)
                .filter(String::isNotBlank)
                .distinct()
                .sorted()
                .toList()
            }
    }
}

internal data class DiagnosticsPreferenceJsonEntry(
    val type: String,
    val value: Any?
)

internal object DiagnosticsPreferenceJsonCodec {
    internal fun encode(values: Map<String, Any?>): Map<String, DiagnosticsPreferenceJsonEntry> {
        return values.entries
            .sortedBy { it.key }
            .associate { (key, value) ->
                key to when (value) {
                    is Boolean -> DiagnosticsPreferenceJsonEntry(type = "boolean", value = value)
                    is Float -> DiagnosticsPreferenceJsonEntry(type = "float", value = value.toDouble())
                    is Int -> DiagnosticsPreferenceJsonEntry(type = "int", value = value)
                    is Long -> DiagnosticsPreferenceJsonEntry(type = "long", value = value)
                    is String -> DiagnosticsPreferenceJsonEntry(type = "string", value = value)
                    is Set<*> -> {
                        require(value.all { it is String }) {
                            "Unsupported string set for preference $key"
                        }
                        DiagnosticsPreferenceJsonEntry(
                            type = "string_set",
                            value = value.filterIsInstance<String>().toSortedSet()
                        )
                    }
                    null -> DiagnosticsPreferenceJsonEntry(type = "null", value = null)
                    else -> error(
                        "Unsupported SharedPreferences type for key $key: ${value::class.java.name}"
                    )
                }
            }
    }

    internal fun decode(values: Map<String, DiagnosticsPreferenceJsonEntry>): Map<String, Any?> {
        val decoded = linkedMapOf<String, Any?>()
        values.toSortedMap().forEach { (key, entry) ->
            decoded[key] = when (entry.type) {
                "boolean" -> entry.value as Boolean
                "float" -> when (val value = entry.value) {
                    is Double -> value.toFloat()
                    is Float -> value
                    else -> error("Expected float value for preference $key")
                }
                "int" -> entry.value as Int
                "long" -> entry.value as Long
                "string" -> {
                    require(entry.value is String) {
                        "Expected string value for preference $key"
                    }
                    entry.value
                }
                "string_set" -> when (val value = entry.value) {
                    is Set<*> -> value.filterIsInstance<String>().toSet()
                    is List<*> -> value.filterIsInstance<String>().toSet()
                    else -> error("Expected string set value for preference $key")
                }
                "null" -> null
                else -> error("Unsupported preference type in backup for key $key: ${entry.type}")
            }
        }
        return decoded
    }

    fun toJson(values: Map<String, Any?>): JSONObject {
        return JSONObject().apply {
            encode(values).forEach { (key, entry) ->
                put(
                    key,
                    JSONObject().apply {
                        put("type", entry.type)
                        when (val value = entry.value) {
                            is Set<*> -> put(
                                "value",
                                JSONArray().apply {
                                    value.filterIsInstance<String>().forEach { put(it) }
                                }
                            )
                            null -> put("value", JSONObject.NULL)
                            else -> {
                                put("value", value)
                            }
                        }
                    }
                )
            }
        }
    }

    fun fromJson(json: JSONObject): Map<String, Any?> {
        val encoded = linkedMapOf<String, DiagnosticsPreferenceJsonEntry>()
        val keys = json.keys().asSequence().toList().sorted()
        keys.forEach { key ->
            val entry = json.getJSONObject(key)
            val type = entry.getString("type")
            val value: Any? = when (type) {
                "boolean" -> entry.getBoolean("value")
                "float" -> entry.getDouble("value")
                "int" -> entry.getInt("value")
                "long" -> entry.getLong("value")
                "string" -> {
                    if (entry.isNull("value")) null else entry.getString("value")
                }
                "string_set" -> buildList {
                    val array = entry.getJSONArray("value")
                    for (index in 0 until array.length()) {
                        add(array.getString(index))
                    }
                }
                "null" -> null
                else -> error("Unsupported preference type in backup for key $key: $type")
            }
            encoded[key] = DiagnosticsPreferenceJsonEntry(type = type, value = value)
        }
        return decode(encoded)
    }
}
