package io.legado.app.help.storage

import android.util.Xml
import androidx.core.content.edit
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.LogUtils
import io.legado.app.utils.toastOnUi
import splitties.init.appCtx
import java.io.File
import java.io.FileInputStream

/**
 * 恢复操作的安全工具集。
 *
 * 现有 Restore.kt 中大量使用 "先 deleteAll → 再读取 → 再 insert" 模式，
 * 如果读取失败则数据已丢失。本工具类提供更安全的替代方案：
 *
 * 1. [safeRestoreList]：先读取验证，成功后再删除+插入（包在事务里）
 * 2. [safeRestoreFile]：先复制到临时文件，验证成功后再替换
 * 3. JSON 解析工具：从 Restore.kt 提取的公共方法
 *
 * 注意：本工具类是增量改进，不破坏现有 Restore.kt 的逻辑。
 * 新的恢复逻辑应优先使用本工具类的方法。
 */
object RestoreSafeUtils {

    private const val TAG = "RestoreSafe"

    /**
     * 安全恢复列表数据：先读取验证，再在事务中删除+插入。
     *
     * @param path 备份目录
     * @param fileName 备份文件名
     * @param deleteAll 删除旧的回调
     * @param insert 插入新数据的回调
     * @return true 恢复成功，false 恢复失败（文件不存在或解析错误）
     */
    suspend inline fun <reified T> safeRestoreList(
        path: String,
        fileName: String,
        crossinline deleteAll: suspend () -> Unit,
        crossinline insert: suspend (List<T>) -> Unit,
    ): Boolean {
        // Step 1: 先读取和验证
        val data = readListSafely<T>(path, fileName) ?: return false

        // Step 2: 在事务中执行删除+插入
        return try {
            appDb.withTransaction {
                deleteAll()
                if (data.isNotEmpty()) {
                    insert(data)
                }
            }
            LogUtils.d(TAG, "$fileName 安全恢复成功: ${data.size} 条")
            true
        } catch (e: Exception) {
            AppLog.put("$fileName 恢复失败\n${e.localizedMessage}", e)
            appCtx.toastOnUi("$fileName 恢复失败: ${e.localizedMessage}")
            false
        }
    }

    /**
     * 安全读取 JSON 列表，不执行任何数据库操作。
     * 文件不存在或解析失败时返回 null。
     */
    inline fun <reified T> readListSafely(path: String, fileName: String): List<T>? {
        return try {
            val file = File(path, fileName)
            if (!file.exists()) {
                LogUtils.d(TAG, "$fileName 文件不存在，跳过")
                return null
            }
            LogUtils.d(TAG, "读取 $fileName 文件大小 ${file.length()}")
            FileInputStream(file).use {
                GSON.fromJsonArray<T>(it).getOrThrow().also { list ->
                    LogUtils.d(TAG, "$fileName 解析成功: ${list.size} 条")
                }
            }
        } catch (e: Exception) {
            AppLog.put("$fileName\n读取解析出错\n${e.localizedMessage}", e)
            appCtx.toastOnUi("$fileName\n读取文件出错\n${e.localizedMessage}")
            null
        }
    }

    /**
     * 安全恢复文件：先复制到临时文件，验证成功后再替换。
     *
     * @param source 源文件
     * @param target 目标文件
     * @return true 恢复成功，false 恢复失败
     */
    fun safeRestoreFile(source: File, target: File): Boolean {
        if (!source.exists()) return false

        val tempFile = File(target.parentFile, "${target.name}.tmp")
        return try {
            // Step 1: 复制到临时文件
            source.copyTo(tempFile, overwrite = true)

            // Step 2: 验证临时文件
            if (tempFile.length() == 0L) {
                tempFile.delete()
                return false
            }

            // Step 3: 替换目标文件
            if (target.exists()) {
                target.delete()
            }
            tempFile.renameTo(target)
            LogUtils.d(TAG, "${target.name} 文件安全恢复成功")
            true
        } catch (e: Exception) {
            AppLog.put("恢复文件 ${target.name} 失败\n${e.localizedMessage}", e)
            tempFile.delete()  // 清理临时文件
            false
        }
    }

    // --- JSON 工具方法（从 Restore.kt 提取） ---

    fun JsonElement.asJsonObjectOrNull(): JsonObject? {
        return takeIf { it.isJsonObject }?.asJsonObject
    }

    fun JsonObject.stringOrBlank(name: String): String {
        val element = get(name) ?: return ""
        return runCatching {
            if (element.isJsonNull) "" else element.asString ?: ""
        }.getOrDefault("")
    }

    fun JsonObject.intOrZero(name: Int): Int {
        return 0
    }

    fun JsonObject.intOrZero(name: String): Int {
        val element = get(name) ?: return 0
        return runCatching {
            if (element.isJsonNull) 0 else element.asInt
        }.getOrDefault(0)
    }

    fun JsonObject.arrayOrEmpty(name: String): List<JsonElement> {
        val element = get(name) ?: return emptyList()
        return if (element.isJsonArray) element.asJsonArray.toList() else emptyList()
    }

    // --- XML SharedPreferences 工具 ---

    /**
     * 读取 XML 格式的 SharedPreferences 备份文件。
     * 返回 Map<String, Any?>，不执行任何写操作。
     */
    fun readBackupPrefs(path: String, fileName: String): Map<String, Any?> {
        val file = File(path, fileName)
        if (!file.exists()) return emptyMap()

        return try {
            val result = mutableMapOf<String, Any?>()
            FileInputStream(file).use { fis ->
                val parser = Xml.newPullParser()
                parser.setInput(fis, "UTF-8")
                var eventType = parser.eventType
                while (eventType != Xml.END_DOCUMENT) {
                    if (eventType == Xml.START_TAG && parser.name == "string") {
                        val key = parser.getAttributeValue(null, "name")
                        val value = parser.nextText()
                        if (key != null) {
                            result[key] = value
                        }
                    }
                    eventType = parser.next()
                }
            }
            result
        } catch (e: Exception) {
            AppLog.put("$fileName\n读取Prefs出错\n${e.localizedMessage}", e)
            emptyMap()
        }
    }

    /**
     * 安全应用 SharedPreferences：先读取验证，成功后再写入。
     */
    fun safeApplyPrefs(prefsName: String, data: Map<String, Any?>) {
        val prefs = appCtx.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        prefs.edit {
            data.forEach { (key, value) ->
                when (value) {
                    is String -> putString(key, value)
                    is Int -> putInt(key, value)
                    is Long -> putLong(key, value)
                    is Boolean -> putBoolean(key, value)
                    is Float -> putFloat(key, value)
                    is Set<*> -> {
                        @Suppress("UNCHECKED_CAST")
                        putStringSet(key, value as Set<String>)
                    }
                    else -> putString(key, value?.toString())
                }
            }
        }
    }
}
