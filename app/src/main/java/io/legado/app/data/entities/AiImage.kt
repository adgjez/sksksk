package io.legado.app.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "ai_images",
    indices = [Index("providerId"), Index("createdAt")]
)
data class AiImage(
    @PrimaryKey
    val id: String = "",
    @ColumnInfo(defaultValue = "")
    val providerId: String = "",
    @ColumnInfo(defaultValue = "")
    val prompt: String = "",
    @ColumnInfo(defaultValue = "")
    val model: String = "",
    @ColumnInfo(defaultValue = "")
    val localPath: String = "",
    @ColumnInfo(defaultValue = "0")
    val width: Int = 0,
    @ColumnInfo(defaultValue = "0")
    val height: Int = 0,
    @ColumnInfo(defaultValue = "0")
    val createdAt: Long = System.currentTimeMillis()
)
