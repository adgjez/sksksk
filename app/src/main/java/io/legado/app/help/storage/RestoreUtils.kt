package io.legado.app.help.storage

import android.util.Xml
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.utils.GSON
import io.legado.app.utils.LogUtils
import io.legado.app.utils.isJsonArray
import splitties.init.appCtx
import java.io.File
import java.io.FileInputStream

/**
 * Restore 模块的共享工具函数。
 *
 * 包含：
 * - JSON 文件反序列化
 * - XML SharedPreferences 解析
 * - 书籍缓存索引解析与规范化
 */
internal object RestoreUtils {

    private const val TAG = "Restore"

    const val bookCacheIndexFileName = "bookCacheIndex.json"
    const val bookCacheBooksFileName = "bookCacheBooks.json"
    const val bookCacheFolderName = "book_cache"
    const val runtimeSourceCacheFileName = "runtimeSourceCache.json"

    // --- JSON 反序列化 ---

    /**
     * 从 JSON 文件读取列表数据。
     * @return 解析后的列表，文件不存在或解析失败返回 null
     */
    inline fun <reified T> fileToListT(path: String, fileName: String): List<T>? {
        try {
            val file = File(path, fileName)
            if (file.exists()) {
                LogUtils.d(TAG, "阅读恢复备份 $fileName 文件大小 ${file.length()}")
                FileInputStream(file).use {
                    return GSON.fromJsonArray<T>(it).getOrThrow().also { list ->
                        LogUtils.d(TAG, "阅读恢复备份 $fileName 列表大小 ${list.size}")
                    }
                }
            } else {
                LogUtils.d(TAG, "阅读恢复备份 $fileName 文件不存在")
            }
        } catch (e: Exception) {
            AppLog.put("$fileName\n读取解析出错\n${e.localizedMessage}", e)
            appCtx.toastOnUi("$fileName\n读取文件出错\n${e.localizedMessage}")
        }
        return null
    }

    // --- XML SharedPreferences 解析 ---

    /**
     * 解析 XML 格式的 SharedPreferences 备份文件为 Map。
     * @param path 备份目录路径
     * @param fileName 文件名（不含 .xml 后缀）
     * @return 解析后的键值对 Map，文件不存在或解析失败返回 null
     */
    fun readBackupPrefs(path: String, fileName: String): Map<String, Any>? {
        val file = File(path, "$fileName.xml")
        if (!file.exists()) return null
        return runCatching {
            val map = linkedMapOf<String, Any>()
            file.inputStream().use { input ->
                val parser = Xml.newPullParser()
                parser.setInput(input, "utf-8")
                var event = parser.eventType
                while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                    if (event == org.xmlpull.v1.XmlPullParser.START_TAG) {
                        val name = parser.getAttributeValue(null, "name")
                        if (!name.isNullOrBlank()) {
                            when (parser.name) {
                                "string" -> map[name] = parser.nextText()
                                "int" -> parser.getAttributeValue(null, "value")?.toIntOrNull()
                                    ?.let { map[name] = it }
                                "long" -> parser.getAttributeValue(null, "value")?.toLongOrNull()
                                    ?.let { map[name] = it }
                                "float" -> parser.getAttributeValue(null, "value")?.toFloatOrNull()
                                    ?.let { map[name] = it }
                                "boolean" -> parser.getAttributeValue(null, "value")?.toBooleanStrictOrNull()
                                    ?.let { map[name] = it }
                            }
                        }
                    }
                    event = parser.next()
                }
            }
            map
        }.onFailure {
            AppLog.put("$fileName.xml\n读取配置出错\n${it.localizedMessage}", it)
        }.getOrNull()
    }

    // --- 书籍缓存索引解析 ---

    fun parseBookCacheIndexList(json: String): List<BookCacheIndex>? {
        return runCatching {
            val root = JsonParser.parseString(json)
            if (!root.isJsonArray) {
                return@runCatching null
            }
            root.asJsonArray.mapNotNull { element ->
                val obj = element.asJsonObjectOrNull() ?: return@mapNotNull null
                val bookUrl = obj.stringOrBlank("bookUrl")
                val bookName = obj.stringOrBlank("bookName")
                val folderName = obj.stringOrBlank("folderName")
                if (folderName.isBlank() || (bookUrl.isBlank() && bookName.isBlank())) {
                    return@mapNotNull null
                }
                BookCacheIndex(
                    bookUrl = bookUrl,
                    bookName = bookName,
                    author = obj.stringOrBlank("author"),
                    folderName = folderName,
                    chapters = obj.arrayOrEmpty("chapters").mapNotNull { chapterElement ->
                        val chapter = chapterElement.asJsonObjectOrNull() ?: return@mapNotNull null
                        val fileName = chapter.stringOrBlank("fileName")
                        if (fileName.isBlank()) {
                            return@mapNotNull null
                        }
                        ChapterCacheInfo(
                            index = chapter.intOrZero("index"),
                            title = chapter.stringOrBlank("title"),
                            titleMD5 = chapter.stringOrBlank("titleMD5"),
                            fileName = fileName
                        )
                    }
                )
            }.sanitizeBookCacheIndexes()
        }.onFailure {
            AppLog.put("$bookCacheIndexFileName\n读取解析出错\n${it.localizedMessage}", it)
        }.getOrNull()
    }

    // --- JsonObject 安全扩展 ---

    private fun JsonElement.asJsonObjectOrNull(): JsonObject? {
        return takeIf { it.isJsonObject }?.asJsonObject
    }

    private fun JsonObject.stringOrBlank(name: String): String {
        val element = get(name) ?: return ""
        return runCatching {
            if (element.isJsonNull) "" else element.asString ?: ""
        }.getOrDefault("")
    }

    private fun JsonObject.intOrZero(name: String): Int {
        val element = get(name) ?: return 0
        return runCatching {
            if (element.isJsonNull) 0 else element.asInt
        }.getOrDefault(0)
    }

    private fun JsonObject.arrayOrEmpty(name: String): List<JsonElement> {
        val element = get(name) ?: return emptyList()
        return if (element.isJsonArray) element.asJsonArray.toList() else emptyList()
    }

    // --- 缓存索引规范化 ---

    /**
     * 清理/规范化书籍缓存索引列表。
     * 移除无效条目（folderName 为空且 bookUrl+bookName 均为空）。
     */
    private fun List<BookCacheIndex>.sanitizeBookCacheIndexes(): List<BookCacheIndex> {
        return mapNotNull { cacheIndex ->
            if (cacheIndex.folderName.isBlank() && cacheIndex.bookUrl.isBlank() && cacheIndex.bookName.isBlank()) {
                return@mapNotNull null
            }
            val chapters = cacheIndex.chapters.filter { it.fileName.isNotBlank() }
            cacheIndex.copy(chapters = chapters)
        }
    }

    /**
     * 规范化 Book 对象用于缓存恢复。
     * 补空字段，过滤无效数据。
     */
    fun Book.sanitizeForCacheRestore(): Book? {
        if (bookUrl.isBlank() && name.isBlank()) {
            LogUtils.d(TAG, "跳过无效缓存书籍信息")
            return null
        }
        return this
    }
}
