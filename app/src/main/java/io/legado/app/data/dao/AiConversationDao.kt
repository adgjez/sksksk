package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.legado.app.data.entities.AiConversation
import io.legado.app.data.entities.AiMessage

@Dao
interface AiConversationDao {
    @Query("SELECT * FROM ai_conversations ORDER BY updatedAt DESC")
    fun all(): List<AiConversation>

    @Query("SELECT * FROM ai_conversations WHERE id = :id")
    fun get(id: String): AiConversation?

    @Query("SELECT * FROM ai_conversations WHERE providerId = :providerId ORDER BY updatedAt DESC")
    fun byProvider(providerId: String): List<AiConversation>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(conversation: AiConversation)

    @Query("DELETE FROM ai_conversations WHERE id = :id")
    fun delete(id: String)

    @Query("SELECT * FROM ai_messages WHERE conversationId = :conversationId ORDER BY createdAt ASC")
    fun messages(conversationId: String): List<AiMessage>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertMessage(message: AiMessage)

    @Query("DELETE FROM ai_messages WHERE conversationId = :conversationId")
    fun deleteMessages(conversationId: String)
}
