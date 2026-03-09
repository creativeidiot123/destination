package com.ankit.destination.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface EnforcementStateDao {
    @Query("SELECT * FROM enforcement_state WHERE id = 1 LIMIT 1")
    suspend fun get(): EnforcementStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: EnforcementStateEntity)

    @Query("DELETE FROM enforcement_state")
    suspend fun clear()
}
