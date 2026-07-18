package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.legado.app.data.entities.AiProvider

@Dao
interface AiProviderDao {
    @Query("SELECT * FROM ai_providers ORDER BY sortOrder ASC, createdAt ASC")
    fun all(): List<AiProvider>

    @Query("SELECT * FROM ai_providers WHERE enabled = 1 ORDER BY sortOrder ASC, createdAt ASC")
    fun enabled(): List<AiProvider>

    @Query("SELECT * FROM ai_providers WHERE id = :id")
    fun get(id: String): AiProvider?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(provider: AiProvider)

    @Query("DELETE FROM ai_providers WHERE id = :id")
    fun delete(id: String)
}
