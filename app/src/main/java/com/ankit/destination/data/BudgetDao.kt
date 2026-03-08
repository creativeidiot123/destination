package com.ankit.destination.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

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

    @Query("DELETE FROM always_allowed_apps")
    suspend fun deleteAllAlwaysAllowed()

    @Query("SELECT packageName FROM always_blocked_apps ORDER BY packageName ASC")
    suspend fun getAlwaysBlockedPackages(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAlwaysBlocked(app: AlwaysBlockedApp)

    @Query("DELETE FROM always_blocked_apps WHERE packageName = :packageName")
    suspend fun deleteAlwaysBlocked(packageName: String)

    @Query("DELETE FROM always_blocked_apps")
    suspend fun deleteAllAlwaysBlocked()

    @Query("SELECT packageName FROM uninstall_protected_apps ORDER BY packageName ASC")
    suspend fun getUninstallProtectedPackages(): List<String>

    @Query("SELECT * FROM hidden_apps ORDER BY packageName ASC")
    suspend fun getHiddenApps(): List<HiddenApp>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertUninstallProtected(app: UninstallProtectedApp)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertHiddenApp(app: HiddenApp)

    @Query("DELETE FROM uninstall_protected_apps WHERE packageName = :packageName")
    suspend fun deleteUninstallProtected(packageName: String)

    @Query("DELETE FROM hidden_apps WHERE packageName = :packageName")
    suspend fun deleteHiddenApp(packageName: String)

    @Query("DELETE FROM uninstall_protected_apps")
    suspend fun deleteAllUninstallProtected()

    @Query("DELETE FROM hidden_apps")
    suspend fun deleteAllHiddenApps()

    @Query("SELECT * FROM app_policy WHERE enabled = 1")
    suspend fun getEnabledAppPolicies(): List<AppPolicy>

    @Query("SELECT * FROM app_policy ORDER BY packageName ASC")
    suspend fun getAllAppPolicies(): List<AppPolicy>

    @Query("SELECT * FROM app_policy WHERE packageName = :packageName LIMIT 1")
    suspend fun getAppPolicy(packageName: String): AppPolicy?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAppPolicy(policy: AppPolicy)

    @Query("DELETE FROM app_policy WHERE packageName = :packageName")
    suspend fun deleteAppPolicy(packageName: String)

    @Query("DELETE FROM app_policy")
    suspend fun deleteAllAppPolicies()

    @Query("SELECT * FROM group_limits WHERE enabled = 1")
    suspend fun getEnabledGroupLimits(): List<GroupLimit>

    @Query("SELECT * FROM group_limits ORDER BY name ASC")
    suspend fun getAllGroupLimits(): List<GroupLimit>

    @Query("SELECT * FROM group_limits WHERE groupId = :groupId LIMIT 1")
    suspend fun getGroupLimit(groupId: String): GroupLimit?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertGroupLimit(limit: GroupLimit)

    @Query("DELETE FROM group_limits WHERE groupId = :groupId")
    suspend fun deleteGroupLimit(groupId: String)

    @Query("DELETE FROM group_limits")
    suspend fun deleteAllGroupLimits()

    @Query("SELECT * FROM app_group_map ORDER BY packageName ASC")
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
        upsertMapping(mapping)
    }

    @Query("DELETE FROM app_group_map WHERE packageName = :packageName AND groupId = :groupId")
    suspend fun deleteMapping(packageName: String, groupId: String)

    @Query("DELETE FROM app_group_map WHERE groupId = :groupId")
    suspend fun deleteMappingsForGroup(groupId: String)

    @Query("DELETE FROM app_group_map")
    suspend fun deleteAllMappings()

    @Query("SELECT * FROM usage_snapshots WHERE windowKey = :windowKey")
    suspend fun getUsageSnapshots(windowKey: String): List<UsageSnapshot>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertUsageSnapshots(rows: List<UsageSnapshot>)

    @Query("DELETE FROM usage_snapshots WHERE windowKey = :windowKey")
    suspend fun deleteUsageSnapshots(windowKey: String)

    @Query("DELETE FROM usage_snapshots")
    suspend fun deleteAllUsageSnapshots()

    @Query("SELECT * FROM group_emergency_config")
    suspend fun getAllGroupEmergencyConfigs(): List<GroupEmergencyConfig>

    @Query("SELECT * FROM group_emergency_config WHERE groupId = :groupId LIMIT 1")
    suspend fun getGroupEmergencyConfig(groupId: String): GroupEmergencyConfig?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertGroupEmergencyConfig(config: GroupEmergencyConfig)

    @Query("DELETE FROM group_emergency_config WHERE groupId = :groupId")
    suspend fun deleteGroupEmergencyConfig(groupId: String)

    @Query("DELETE FROM group_emergency_config")
    suspend fun deleteAllGroupEmergencyConfigs()

    @Query("SELECT * FROM emergency_state WHERE dayKey = :dayKey")
    suspend fun getEmergencyStatesForDay(dayKey: String): List<EmergencyState>

    @Query(
        "SELECT * FROM emergency_state WHERE dayKey = :dayKey AND targetType = :targetType AND targetId = :targetId LIMIT 1"
    )
    suspend fun getEmergencyState(dayKey: String, targetType: String, targetId: String): EmergencyState?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEmergencyState(state: EmergencyState)

    @Query("DELETE FROM emergency_state WHERE dayKey < :dayKey")
    suspend fun clearEmergencyStateBefore(dayKey: String)

    @Query(
        """
        DELETE FROM emergency_state
        WHERE dayKey < :dayKey
          AND (activeUntilEpochMs IS NULL OR activeUntilEpochMs <= :nowMs)
        """
    )
    suspend fun clearExpiredEmergencyStateBefore(dayKey: String, nowMs: Long)

    @Query(
        """
        SELECT * FROM emergency_state
        WHERE dayKey = :dayKey
           OR (activeUntilEpochMs IS NOT NULL AND activeUntilEpochMs > :nowMs)
        """
    )
    suspend fun getCurrentOrActiveEmergencyStates(dayKey: String, nowMs: Long): List<EmergencyState>

    @Query(
        "DELETE FROM emergency_state WHERE dayKey = :dayKey AND targetType = :targetType AND targetId = :targetId"
    )
    suspend fun clearEmergencyState(dayKey: String, targetType: String, targetId: String)

    @Query("DELETE FROM emergency_state WHERE targetType = :targetType AND targetId = :targetId")
    suspend fun deleteAllEmergencyStateForTarget(targetType: String, targetId: String)

    @Query("DELETE FROM emergency_state")
    suspend fun deleteAllEmergencyStates()

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

    @Query("DELETE FROM domain_rules")
    suspend fun deleteAllDomainRules()

    @Query("DELETE FROM global_controls")
    suspend fun deleteAllGlobalControls()

    @Transaction
    suspend fun deleteGroupLimitCascade(groupId: String) {
        deleteMappingsForGroup(groupId)
        deleteGroupEmergencyConfig(groupId)
        deleteAllEmergencyStateForTarget(EmergencyTargetType.GROUP.name, groupId)
        deleteDomainRulesForScope("GROUP", groupId)
        deleteGroupLimit(groupId)
    }

    @Transaction
    suspend fun resetAllPolicyData(defaultControls: GlobalControls) {
        deleteAllMappings()
        deleteAllAppPolicies()
        deleteAllGroupEmergencyConfigs()
        deleteAllEmergencyStates()
        deleteAllDomainRules()
        deleteAllUsageSnapshots()
        deleteAllGroupLimits()
        deleteAllAlwaysAllowed()
        deleteAllAlwaysBlocked()
        deleteAllUninstallProtected()
        deleteAllHiddenApps()
        deleteAllGlobalControls()
        upsertGlobalControls(defaultControls.copy(id = 1))
    }
}
