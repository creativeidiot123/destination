package com.ankit.destination.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ScheduleBlock::class,
        ScheduleBlockGroup::class,
        AppPolicy::class,
        GroupLimit::class,
        AppGroupMap::class,
        UsageSnapshot::class,
        GroupEmergencyConfig::class,
        EmergencyState::class,
        DomainRule::class,
        GlobalControls::class,
        AlwaysAllowedApp::class,
        AlwaysBlockedApp::class,
        UninstallProtectedApp::class,
        HiddenApp::class,
        EnforcementStateEntity::class
    ],
    version = 16,
    exportSchema = false
)
abstract class FocusDatabase : RoomDatabase() {
    abstract fun scheduleDao(): ScheduleDao
    abstract fun budgetDao(): BudgetDao
    abstract fun enforcementStateDao(): EnforcementStateDao

    companion object {
        @Volatile
        private var INSTANCE: FocusDatabase? = null

        fun get(context: Context): FocusDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: create(context.applicationContext).also { INSTANCE = it }
            }
        }

        private fun create(context: Context): FocusDatabase {
            val storageContext = context.createDeviceProtectedStorageContext()
            return Room.databaseBuilder(
                storageContext,
                FocusDatabase::class.java,
                "focus.db"
            ).addMigrations(
                MIGRATION_1_2,
                MIGRATION_2_3,
                MIGRATION_3_4,
                MIGRATION_4_5,
                MIGRATION_5_6,
                MIGRATION_6_7,
                MIGRATION_7_8,
                MIGRATION_8_9,
                MIGRATION_9_10,
                MIGRATION_10_11,
                MIGRATION_11_12,
                MIGRATION_12_13,
                MIGRATION_13_14,
                MIGRATION_14_15,
                MIGRATION_15_16
            )
                .build()
        }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE schedule_blocks ADD COLUMN immutable INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE schedule_blocks ADD COLUMN timezoneMode TEXT NOT NULL DEFAULT 'DEVICE_LOCAL'"
                )
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `app_limits` (
                        `packageName` TEXT NOT NULL,
                        `dailyLimitMs` INTEGER NOT NULL,
                        `enabled` INTEGER NOT NULL,
                        PRIMARY KEY(`packageName`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `group_limits` (
                        `groupId` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `priorityIndex` INTEGER NOT NULL DEFAULT 1000,
                        `dailyLimitMs` INTEGER NOT NULL,
                        `hourlyLimitMs` INTEGER NOT NULL,
                        `opensPerDay` INTEGER NOT NULL,
                        `enabled` INTEGER NOT NULL,
                        PRIMARY KEY(`groupId`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `app_group_map` (
                        `packageName` TEXT NOT NULL,
                        `groupId` TEXT NOT NULL,
                        PRIMARY KEY(`packageName`, `groupId`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `usage_snapshots` (
                        `windowKey` TEXT NOT NULL,
                        `packageName` TEXT NOT NULL,
                        `foregroundMs` INTEGER NOT NULL,
                        `opens` INTEGER NOT NULL,
                        PRIMARY KEY(`windowKey`, `packageName`)
                    )
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `group_emergency_config` (
                        `groupId` TEXT NOT NULL,
                        `enabled` INTEGER NOT NULL,
                        `unlocksPerDay` INTEGER NOT NULL,
                        `minutesPerUnlock` INTEGER NOT NULL,
                        PRIMARY KEY(`groupId`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `group_emergency_state` (
                        `dayKey` TEXT NOT NULL,
                        `groupId` TEXT NOT NULL,
                        `unlocksUsedToday` INTEGER NOT NULL,
                        `activeUntilEpochMs` INTEGER,
                        PRIMARY KEY(`dayKey`, `groupId`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `domain_rules` (
                        `domain` TEXT NOT NULL,
                        `scopeType` TEXT NOT NULL,
                        `scopeId` TEXT NOT NULL,
                        `blocked` INTEGER NOT NULL,
                        PRIMARY KEY(`domain`, `scopeType`, `scopeId`)
                    )
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE schedule_blocks ADD COLUMN strict INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE schedule_blocks ADD COLUMN kind TEXT NOT NULL DEFAULT 'GROUPS'"
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `schedule_block_groups` (
                        `blockId` INTEGER NOT NULL,
                        `groupId` TEXT NOT NULL,
                        PRIMARY KEY(`blockId`, `groupId`)
                    )
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE group_limits ADD COLUMN priorityIndex INTEGER NOT NULL DEFAULT 1000"
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `global_controls` (
                        `id` INTEGER NOT NULL,
                        `lockTime` INTEGER NOT NULL,
                        `lockVpnDns` INTEGER NOT NULL,
                        `lockDevOptions` INTEGER NOT NULL,
                        `lockUserCreation` INTEGER NOT NULL,
                        `lockWorkProfile` INTEGER NOT NULL,
                        `lockCloningBestEffort` INTEGER NOT NULL,
                        `dangerUnenrollEnabled` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT OR IGNORE INTO global_controls
                    (id, lockTime, lockVpnDns, lockDevOptions, lockUserCreation, lockWorkProfile, lockCloningBestEffort, dangerUnenrollEnabled)
                    VALUES (1, 0, 1, 0, 0, 0, 0, 0)
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `always_allowed_apps` (
                        `packageName` TEXT NOT NULL,
                        PRIMARY KEY(`packageName`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `always_blocked_apps` (
                        `packageName` TEXT NOT NULL,
                        PRIMARY KEY(`packageName`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `uninstall_protected_apps` (
                        `packageName` TEXT NOT NULL,
                        PRIMARY KEY(`packageName`)
                    )
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `app_group_map_new` (
                        `packageName` TEXT NOT NULL,
                        `groupId` TEXT NOT NULL,
                        PRIMARY KEY(`packageName`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT OR REPLACE INTO `app_group_map_new` (`packageName`, `groupId`)
                    SELECT pkg.`normalizedPackageName`,
                        (
                            SELECT TRIM(m2.`groupId`)
                            FROM `app_group_map` m2
                            LEFT JOIN `group_limits` g2
                                ON TRIM(m2.`groupId`) = TRIM(g2.`groupId`)
                            WHERE TRIM(m2.`packageName`) = pkg.`normalizedPackageName`
                                AND TRIM(m2.`packageName`) <> ''
                                AND TRIM(m2.`groupId`) <> ''
                            ORDER BY COALESCE(g2.`priorityIndex`, 1000) ASC, TRIM(m2.`groupId`) ASC
                            LIMIT 1
                        )
                    FROM (
                        SELECT DISTINCT TRIM(`packageName`) AS `normalizedPackageName`
                        FROM `app_group_map`
                        WHERE TRIM(`packageName`) <> '' AND TRIM(`groupId`) <> ''
                    ) pkg
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE `app_group_map`")
                db.execSQL("ALTER TABLE `app_group_map_new` RENAME TO `app_group_map`")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_app_group_map_groupId` ON `app_group_map` (`groupId`)"
                )
            }
        }

        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `app_policy` (
                        `packageName` TEXT NOT NULL,
                        `dailyLimitMs` INTEGER NOT NULL,
                        `hourlyLimitMs` INTEGER NOT NULL,
                        `opensPerDay` INTEGER NOT NULL,
                        `enabled` INTEGER NOT NULL,
                        `emergencyEnabled` INTEGER NOT NULL,
                        `unlocksPerDay` INTEGER NOT NULL,
                        `minutesPerUnlock` INTEGER NOT NULL,
                        PRIMARY KEY(`packageName`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT OR REPLACE INTO `app_policy`
                    (`packageName`, `dailyLimitMs`, `hourlyLimitMs`, `opensPerDay`, `enabled`, `emergencyEnabled`, `unlocksPerDay`, `minutesPerUnlock`)
                    SELECT `packageName`,
                        `dailyLimitMs`,
                        9223372036854775807,
                        2147483647,
                        `enabled`,
                        0,
                        0,
                        0
                    FROM `app_limits`
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE IF EXISTS `app_limits`")
                db.execSQL(
                    "ALTER TABLE `group_limits` ADD COLUMN `strictEnabled` INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    """
                    UPDATE `group_limits`
                    SET `strictEnabled` = 1
                    WHERE TRIM(`groupId`) IN (
                        SELECT DISTINCT TRIM(sbg.`groupId`)
                        FROM `schedule_block_groups` sbg
                        INNER JOIN `schedule_blocks` sb
                            ON sb.`id` = sbg.`blockId`
                        WHERE sb.`strict` = 1
                    )
                    """.trimIndent()
                )
                db.execSQL("ALTER TABLE `global_controls` ADD COLUMN `privateDnsMode` TEXT NOT NULL DEFAULT 'OPPORTUNISTIC'")
                db.execSQL("ALTER TABLE `global_controls` ADD COLUMN `privateDnsHost` TEXT")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `emergency_state` (
                        `dayKey` TEXT NOT NULL,
                        `targetType` TEXT NOT NULL,
                        `targetId` TEXT NOT NULL,
                        `unlocksUsedToday` INTEGER NOT NULL,
                        `activeUntilEpochMs` INTEGER,
                        PRIMARY KEY(`dayKey`, `targetType`, `targetId`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT OR REPLACE INTO `emergency_state`
                    (`dayKey`, `targetType`, `targetId`, `unlocksUsedToday`, `activeUntilEpochMs`)
                    SELECT `dayKey`, 'GROUP', `groupId`, `unlocksUsedToday`, `activeUntilEpochMs`
                    FROM `group_emergency_state`
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE IF EXISTS `group_emergency_state`")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `app_group_map_new` (
                        `packageName` TEXT NOT NULL,
                        `groupId` TEXT NOT NULL,
                        PRIMARY KEY(`packageName`, `groupId`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT OR REPLACE INTO `app_group_map_new` (`packageName`, `groupId`)
                    SELECT DISTINCT TRIM(`packageName`), TRIM(`groupId`)
                    FROM `app_group_map`
                    WHERE TRIM(`packageName`) <> '' AND TRIM(`groupId`) <> ''
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE `app_group_map`")
                db.execSQL("ALTER TABLE `app_group_map_new` RENAME TO `app_group_map`")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_app_group_map_groupId` ON `app_group_map` (`groupId`)"
                )
            }
        }

        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `global_controls` ADD COLUMN `managedNetworkMode` TEXT NOT NULL DEFAULT 'UNMANAGED'"
                )
                db.execSQL("ALTER TABLE `global_controls` ADD COLUMN `managedVpnPackage` TEXT")
                db.execSQL(
                    "ALTER TABLE `global_controls` ADD COLUMN `managedVpnLockdown` INTEGER NOT NULL DEFAULT 1"
                )
                db.execSQL(
                    """
                    UPDATE `global_controls`
                    SET `managedNetworkMode` = CASE
                        WHEN `lockVpnDns` = 1 THEN 'FORCED_VPN'
                        WHEN TRIM(COALESCE(`privateDnsHost`, '')) <> ''
                            AND `privateDnsMode` = 'PROVIDER_HOSTNAME' THEN 'FORCED_PRIVATE_DNS'
                        ELSE 'UNMANAGED'
                    END,
                    `managedVpnPackage` = CASE
                        WHEN `lockVpnDns` = 1 THEN 'com.ankit.destination'
                        ELSE `managedVpnPackage`
                    END,
                    `managedVpnLockdown` = CASE
                        WHEN `lockVpnDns` = 1 THEN 1
                        ELSE `managedVpnLockdown`
                    END
                    WHERE `id` = 1
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `global_controls` ADD COLUMN `disableSafeMode` INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    ALTER TABLE `group_limits`
                    ADD COLUMN `scheduleTargetMode` TEXT NOT NULL DEFAULT 'SELECTED_APPS'
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `hidden_apps` (
                        `packageName` TEXT NOT NULL,
                        `locked` INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY(`packageName`)
                    )
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `enforcement_state` (
                        `id` INTEGER NOT NULL,
                        `scheduleLockComputed` INTEGER NOT NULL,
                        `scheduleLockEnforced` INTEGER NOT NULL,
                        `scheduleStrictComputed` INTEGER NOT NULL,
                        `scheduleStrictEnforced` INTEGER NOT NULL,
                        `scheduleBlockedGroupsEncoded` TEXT NOT NULL,
                        `scheduleBlockedPackagesEncoded` TEXT NOT NULL,
                        `strictInstallSuspendedPackagesEncoded` TEXT NOT NULL,
                        `scheduleLockReason` TEXT,
                        `scheduleTargetWarning` TEXT,
                        `scheduleTargetDiagnosticCode` TEXT NOT NULL,
                        `scheduleNextTransitionAtMs` INTEGER,
                        `budgetBlockedPackagesEncoded` TEXT NOT NULL,
                        `budgetBlockedGroupIdsEncoded` TEXT NOT NULL,
                        `budgetReason` TEXT,
                        `budgetUsageAccessGranted` INTEGER NOT NULL,
                        `budgetNextCheckAtMs` INTEGER,
                        `nextPolicyWakeAtMs` INTEGER,
                        `nextPolicyWakeReason` TEXT,
                        `primaryReasonByPackageEncoded` TEXT NOT NULL,
                        `blockReasonsByPackageEncoded` TEXT NOT NULL,
                        `lastSuspendedPackagesEncoded` TEXT NOT NULL,
                        `lastUninstallProtectedPackagesEncoded` TEXT NOT NULL,
                        `lastAppliedAtMs` INTEGER NOT NULL,
                        `lastVerificationPassed` INTEGER NOT NULL,
                        `lastError` TEXT,
                        `lastSuccessfulApplyAtMs` INTEGER NOT NULL,
                        `computedSnapshotVersion` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    ALTER TABLE `enforcement_state`
                    ADD COLUMN `budgetUsageSnapshotStatus` TEXT NOT NULL DEFAULT 'ACCESS_MISSING'
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    UPDATE `enforcement_state`
                    SET `budgetUsageSnapshotStatus` =
                        CASE
                            WHEN `budgetUsageAccessGranted` = 1 THEN 'OK'
                            ELSE 'ACCESS_MISSING'
                        END
                    """.trimIndent()
                )
            }
        }
    }
}

