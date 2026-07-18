package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.legado.app.data.entities.AiImage

@Dao
interface AiImageDao {
    @Query("SELECT * FROM ai_images ORDER BY createdAt DESC")
    fun all(): List<AiImage>

    @Query("SELECT * FROM ai_images WHERE id = :id")
    fun get(id: String): AiImage?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(image: AiImage)

    @Query("DELETE FROM ai_images WHERE id = :id")
    fun delete(id: String)
}
