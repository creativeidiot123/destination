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
        AppLimit::class,
        GroupLimit::class,
        AppGroupMap::class,
        UsageSnapshot::class,
        GroupEmergencyConfig::class,
        GroupEmergencyState::class,
        DomainRule::class,
        GlobalControls::class,
        AlwaysAllowedApp::class,
        AlwaysBlockedApp::class,
        UninstallProtectedApp::class
    ],
    version = 8,
    exportSchema = false
)
abstract class FocusDatabase : RoomDatabase() {
    abstract fun scheduleDao(): ScheduleDao
    abstract fun budgetDao(): BudgetDao

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
                MIGRATION_7_8
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
                    "ALTER TABLE schedule_blocks ADD COLUMN kind TEXT NOT NULL DEFAULT 'NUCLEAR'"
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
    }
}
