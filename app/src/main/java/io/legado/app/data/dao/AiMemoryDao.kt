package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.legado.app.data.entities.AiMemoryEntity

@Dao
interface AiMemoryDao {
    @Query("SELECT * FROM ai_memories ORDER BY importance DESC, updatedAt DESC")
    fun all(): List<AiMemoryEntity>

    @Query("SELECT * FROM ai_memories WHERE scope = :scope ORDER BY importance DESC")
    fun getByScope(scope: String): List<AiMemoryEntity>

    @Query("SELECT * FROM ai_memories WHERE `key` = :key AND scope = :scope AND bookKey = :bookKey LIMIT 1")
    fun get(key: String, scope: String, bookKey: String): AiMemoryEntity?

    @Query("""SELECT * FROM ai_memories
        WHERE (:bookKey = '' OR scope = 'global' OR bookKey = :bookKey)
        AND (key LIKE '%' || :query || '%' OR value LIKE '%' || :query || '%')
        ORDER BY importance DESC LIMIT :limit""")
    fun search(query: String, bookKey: String, limit: Int): List<AiMemoryEntity>

    @Query("""SELECT * FROM ai_memories
        WHERE (:bookKey = '' OR scope = 'global' OR bookKey = :bookKey)
        ORDER BY importance DESC LIMIT :limit""")
    fun topByImportance(bookKey: String, limit: Int): List<AiMemoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(entry: AiMemoryEntity)

    @Query("DELETE FROM ai_memories WHERE id = :id")
    fun delete(id: String)

    @Query("SELECT COUNT(*) FROM ai_memories")
    fun count(): Int

    @Query("DELETE FROM ai_memories WHERE scope = :scope AND id IN (SELECT id FROM ai_memories WHERE scope = :scope ORDER BY importance ASC LIMIT :count)")
    fun deleteLeastImportant(scope: String, count: Int)
}
