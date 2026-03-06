package com.ankit.destination.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlin.math.max

@Dao
interface BudgetDao {
    @Query("SELECT * FROM global_controls WHERE id = 1 LIMIT 1")
    suspend fun getGlobalControls(): GlobalControls?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertGlobalControls(controls: GlobalControls)

    @Query("SELECT packageName FROM always_allowed_apps ORDER BY packageName ASC")
    suspend fun getAlwaysAllowedPackages(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAlwaysAllowed(app: AlwaysAllowedApp)

    @Query("DELETE FROM always_allowed_apps WHERE packageName = :packageName")
    suspend fun deleteAlwaysAllowed(packageName: String)

    @Query("SELECT packageName FROM always_blocked_apps ORDER BY packageName ASC")
    suspend fun getAlwaysBlockedPackages(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAlwaysBlocked(app: AlwaysBlockedApp)

    @Query("DELETE FROM always_blocked_apps WHERE packageName = :packageName")
    suspend fun deleteAlwaysBlocked(packageName: String)

    @Query("SELECT packageName FROM uninstall_protected_apps ORDER BY packageName ASC")
    suspend fun getUninstallProtectedPackages(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertUninstallProtected(app: UninstallProtectedApp)

    @Query("DELETE FROM uninstall_protected_apps WHERE packageName = :packageName")
    suspend fun deleteUninstallProtected(packageName: String)

    @Query("SELECT * FROM app_limits WHERE enabled = 1")
    suspend fun getEnabledAppLimits(): List<AppLimit>

    @Query("SELECT * FROM app_limits ORDER BY packageName ASC")
    suspend fun getAllAppLimits(): List<AppLimit>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAppLimit(limit: AppLimit)

    @Query("DELETE FROM app_limits WHERE packageName = :packageName")
    suspend fun deleteAppLimit(packageName: String)

    @Query("SELECT * FROM group_limits WHERE enabled = 1")
    suspend fun getEnabledGroupLimits(): List<GroupLimit>

    @Query("SELECT * FROM group_limits ORDER BY name ASC")
    suspend fun getAllGroupLimits(): List<GroupLimit>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertGroupLimit(limit: GroupLimit)

    @Query("DELETE FROM group_limits WHERE groupId = :groupId")
    suspend fun deleteGroupLimit(groupId: String)

    @Query("SELECT * FROM app_group_map")
    suspend fun getAllMappings(): List<AppGroupMap>

    @Query("SELECT packageName FROM app_group_map WHERE groupId = :groupId")
    suspend fun getPackagesForGroup(groupId: String): List<String>

    @Query("SELECT DISTINCT packageName FROM app_group_map WHERE groupId IN (:groupIds)")
    suspend fun getPackagesForGroups(groupIds: List<String>): List<String>

    @Query("SELECT groupId FROM app_group_map WHERE packageName = :packageName")
    suspend fun getGroupsForPackage(packageName: String): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMapping(mapping: AppGroupMap)

    @Query("DELETE FROM app_group_map WHERE packageName = :packageName")
    suspend fun deleteMappingsForPackage(packageName: String)

    @Transaction
    suspend fun upsertSingleGroupMapping(mapping: AppGroupMap) {
        deleteMappingsForPackage(mapping.packageName)
        upsertMapping(mapping)
    }

    @Query("DELETE FROM app_group_map WHERE packageName = :packageName AND groupId = :groupId")
    suspend fun deleteMapping(packageName: String, groupId: String)

    @Query("DELETE FROM app_group_map WHERE groupId = :groupId")
    suspend fun deleteMappingsForGroup(groupId: String)

    @Query("SELECT * FROM usage_snapshots WHERE windowKey = :windowKey")
    suspend fun getUsageSnapshots(windowKey: String): List<UsageSnapshot>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertUsageSnapshots(rows: List<UsageSnapshot>)

    @Query("DELETE FROM usage_snapshots WHERE windowKey = :windowKey")
    suspend fun deleteUsageSnapshots(windowKey: String)

    @Query("SELECT * FROM group_emergency_config")
    suspend fun getAllGroupEmergencyConfigs(): List<GroupEmergencyConfig>

    @Query("SELECT * FROM group_emergency_config WHERE groupId = :groupId LIMIT 1")
    suspend fun getGroupEmergencyConfig(groupId: String): GroupEmergencyConfig?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertGroupEmergencyConfig(config: GroupEmergencyConfig)

    @Query("DELETE FROM group_emergency_config WHERE groupId = :groupId")
    suspend fun deleteGroupEmergencyConfig(groupId: String)

    @Query("SELECT * FROM group_emergency_state WHERE dayKey = :dayKey")
    suspend fun getGroupEmergencyStatesForDay(dayKey: String): List<GroupEmergencyState>

    @Query("SELECT * FROM group_emergency_state WHERE dayKey = :dayKey AND groupId = :groupId LIMIT 1")
    suspend fun getGroupEmergencyState(dayKey: String, groupId: String): GroupEmergencyState?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertGroupEmergencyState(state: GroupEmergencyState)

    @Query("DELETE FROM group_emergency_state WHERE dayKey < :dayKey")
    suspend fun clearEmergencyStateBefore(dayKey: String)

    @Query("DELETE FROM group_emergency_state WHERE dayKey = :dayKey AND groupId = :groupId")
    suspend fun clearEmergencyState(dayKey: String, groupId: String)

    @Query("SELECT * FROM domain_rules ORDER BY scopeType ASC, scopeId ASC, domain ASC")
    suspend fun getAllDomainRules(): List<DomainRule>

    @Query("SELECT * FROM domain_rules WHERE scopeType = :scopeType AND scopeId = :scopeId ORDER BY domain ASC")
    suspend fun getDomainRulesForScope(scopeType: String, scopeId: String): List<DomainRule>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDomainRule(rule: DomainRule)

    @Query("DELETE FROM domain_rules WHERE domain = :domain AND scopeType = :scopeType AND scopeId = :scopeId")
    suspend fun deleteDomainRule(domain: String, scopeType: String, scopeId: String)

    @Query("DELETE FROM domain_rules WHERE scopeType = :scopeType AND scopeId = :scopeId")
    suspend fun deleteDomainRulesForScope(scopeType: String, scopeId: String)

    @Transaction
    suspend fun consumeGroupEmergencyUnlock(dayKey: String, groupId: String, nowMs: Long): GroupEmergencyState? {
        val config = getGroupEmergencyConfig(groupId) ?: return null
        if (!config.enabled || config.unlocksPerDay <= 0 || config.minutesPerUnlock <= 0) return null
        val current = getGroupEmergencyState(dayKey, groupId)
            ?: GroupEmergencyState(dayKey = dayKey, groupId = groupId, unlocksUsedToday = 0, activeUntilEpochMs = null)
        if (current.unlocksUsedToday >= config.unlocksPerDay) return null
        val nextUntil = nowMs + (config.minutesPerUnlock * 60_000L)
        val updated = current.copy(
            unlocksUsedToday = current.unlocksUsedToday + 1,
            activeUntilEpochMs = max(current.activeUntilEpochMs ?: 0L, nextUntil)
        )
        upsertGroupEmergencyState(updated)
        return updated
    }

    @Transaction
    suspend fun addAlwaysAllowedExclusive(packageName: String) {
        deleteAlwaysBlocked(packageName)
        upsertAlwaysAllowed(AlwaysAllowedApp(packageName))
    }

    @Transaction
    suspend fun addAlwaysBlockedExclusive(packageName: String) {
        deleteAlwaysAllowed(packageName)
        upsertAlwaysBlocked(AlwaysBlockedApp(packageName))
    }
}
