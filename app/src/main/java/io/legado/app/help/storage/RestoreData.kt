package io.legado.app.help.storage

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.Bookmark
import io.legado.app.data.entities.BookGroup
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.Cache
import io.legado.app.data.entities.DictRule
import io.legado.app.data.entities.HttpTTS
import io.legado.app.data.entities.HomepageCustomSet
import io.legado.app.data.entities.HomepageModule
import io.legado.app.data.entities.KeyboardAssist
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.data.entities.RssSource
import io.legado.app.data.entities.RssStar
import io.legado.app.data.entities.RuleSub
import io.legado.app.data.entities.Server
import io.legado.app.data.entities.SearchKeyword
import io.legado.app.data.entities.TxtTocRule
import io.legado.app.data.entities.readRecord.ReadRecord
import io.legado.app.data.entities.readRecord.ReadRecordDetail
import io.legado.app.data.entities.readRecord.ReadRecordSession
import io.legado.app.data.repository.ReadRecordRepository
import io.legado.app.help.AppCacheManager
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.upType
import io.legado.app.help.storage.RestoreUtils.fileToListT
import io.legado.app.model.localBook.LocalBook
import io.legado.app.ui.book.read.websearch.SearchEngine
import io.legado.app.ui.book.read.websearch.SearchEngineHelper
import io.legado.app.utils.GSON
import io.legado.app.utils.LogUtils
import io.legado.app.utils.isJsonArray
import io.legado.app.utils.putPrefInt
import kotlinx.coroutines.runBlocking
import splitties.init.appCtx
import java.io.File

/**
 * 数据库恢复逻辑。
 *
 * 从备份 JSON 文件恢复各实体的数据库记录。
 * 每个方法都使用 runInTransaction 包裹"先删后读"操作，确保原子性。
 * 由于 Room 的 runInTransaction 不是 suspend，suspend DAO 调用用 runBlocking 包装。
 */
internal object RestoreData {

    private const val TAG = "Restore"

    // --- 书架数据 ---

    fun restoreBooks(path: String) {
        appDb.runInTransaction {
            appDb.bookDao.deleteAll()
            fileToListT<Book>(path, "bookshelf.json")?.let {
                it.forEach { book -> book.upType() }
                it.filter { book -> book.isLocal }
                    .forEach { book -> book.coverUrl = LocalBook.getCoverPath(book) }
                val ignoreLocalBook = BackupConfig.ignoreLocalBook
                val books = it.filterNot { book -> ignoreLocalBook && book.isLocal }
                appDb.bookDao.insert(*books.toTypedArray())
            } ?: run {
                val bookSourceFile = File(path, "bookSource.json")
                if (bookSourceFile.exists()) {
                    val json = bookSourceFile.readText()
                    ImportOldData.importOldSource(json)
                }
            }
        }
    }

    fun restoreBookmarks(path: String) {
        appDb.runInTransaction {
            appDb.bookmarkDao.deleteAll()
            fileToListT<Bookmark>(path, "bookmark.json")?.let {
                appDb.bookmarkDao.insert(*it.toTypedArray())
            }
        }
    }

    fun restoreBookGroups(path: String) {
        appDb.runInTransaction {
            appDb.bookGroupDao.deleteAll()
            fileToListT<BookGroup>(path, "bookGroup.json")?.let {
                appDb.bookGroupDao.insert(*it.toTypedArray())
            }
        }
    }

    fun restoreBookSources(path: String) {
        appDb.runInTransaction {
            appDb.bookSourceDao.deleteAll()
            fileToListT<BookSource>(path, "bookSource.json")?.let {
                appDb.bookSourceDao.insert(*it.toTypedArray())
            } ?: run {
                val bookSourceFile = File(path, "bookSource.json")
                if (bookSourceFile.exists()) {
                    val json = bookSourceFile.readText()
                    ImportOldData.importOldSource(json)
                }
            }
        }
    }

    fun restoreRssSources(path: String) {
        appDb.runInTransaction {
            appDb.rssSourceDao.deleteAll()
            fileToListT<RssSource>(path, "rssSources.json")?.let {
                appDb.rssSourceDao.insert(*it.toTypedArray())
            }
        }
    }

    fun restoreRssStars(path: String) {
        appDb.runInTransaction {
            appDb.rssStarDao.deleteAll()
            fileToListT<RssStar>(path, "rssStar.json")?.let {
                appDb.rssStarDao.insert(*it.toTypedArray())
            }
        }
    }

    fun restoreRuleSubs(path: String) {
        appDb.runInTransaction {
            appDb.ruleSubDao.deleteAll()
            fileToListT<RuleSub>(path, "sourceSub.json")?.let {
                appDb.ruleSubDao.insert(*it.toTypedArray())
            }
        }
    }

    fun restoreSearchEngines(path: String) {
        val enginesFile = File(path, "webSearchEngines.json")
        if (enginesFile.exists()) {
            try {
                val enginesJson = enginesFile.readText()
                val engines = GSON.fromJsonArray<SearchEngine>(enginesJson).getOrNull()
                if (engines != null) {
                    SearchEngineHelper.saveSearchEngines(appCtx, engines)
                }
            } catch (e: Exception) {
                AppLog.put("恢复搜索引擎规则出错\n${e.localizedMessage}", e)
            }
        }
    }

    fun restoreHomepage(path: String) {
        val file = File(path, "homepage.json")
        if (!file.exists()) return
        val json = file.readText()
        val obj = GSON.fromJsonObject<Map<String, JsonElement>>(json).getOrNull() ?: return
        appDb.runInTransaction {
            runBlocking {
                appDb.homepageModuleDao.deleteAll()
                (obj["modules"] as? JsonArray)?.let { array ->
                    val modules = GSON.fromJsonArray<HomepageModule>(array.toString()).getOrNull()
                    modules?.let { appDb.homepageModuleDao.upsertAll(it) }
                }
                appDb.homepageCustomSetDao.deleteAll()
                (obj["customSets"] as? JsonArray)?.let { array ->
                    val sets = GSON.fromJsonArray<HomepageCustomSet>(array.toString()).getOrNull()
                    sets?.forEach { set -> appDb.homepageCustomSetDao.upsert(set) }
                }
            }
        }
    }

    fun restoreReplaceRules(path: String) {
        appDb.runInTransaction {
            appDb.replaceRuleDao.deleteAll()
            fileToListT<ReplaceRule>(path, "replaceRule.json")?.let {
                appDb.replaceRuleDao.insert(*it.toTypedArray())
            }
        }
    }

    fun restoreHighlightRules(path: String) {
        io.legado.app.ui.book.read.config.highlight.HighlightRuleStore.backupFileName.let { fileName ->
            File(path, fileName).takeIf { it.exists() }?.runCatching {
                GSON.fromJsonObject<io.legado.app.ui.book.read.config.highlight.HighlightRuleStore.BackupData>(readText())
                    .getOrNull()?.let {
                    io.legado.app.ui.book.read.config.highlight.HighlightRuleStore.restoreBackupData(appCtx, it, path)
                }
            }?.onFailure {
                AppLog.put("恢复高亮规则出错\n${it.localizedMessage}", it)
            }
        }
    }

    fun restoreSearchHistory(path: String) {
        appDb.runInTransaction {
            appDb.searchKeywordDao.deleteAll()
            fileToListT<SearchKeyword>(path, "searchHistory.json")?.let {
                appDb.searchKeywordDao.insert(*it.toTypedArray())
            }
        }
    }

    fun restoreTxtTocRules(path: String) {
        appDb.runInTransaction {
            appDb.txtTocRuleDao.deleteAll()
            fileToListT<TxtTocRule>(path, "txtTocRule.json")?.let {
                appDb.txtTocRuleDao.insert(*it.toTypedArray())
            }
        }
    }

    fun restoreHttpTTS(path: String) {
        appDb.runInTransaction {
            appDb.httpTTSDao.deleteAll()
            fileToListT<HttpTTS>(path, "httpTTS.json")?.let {
                appDb.httpTTSDao.insert(*it.toTypedArray())
            }
        }
    }

    fun restoreDictRules(path: String) {
        appDb.runInTransaction {
            appDb.dictRuleDao.deleteAll()
            fileToListT<DictRule>(path, "dictRule.json")?.let {
                appDb.dictRuleDao.insert(*it.toTypedArray())
            }
        }
    }

    fun restoreKeyboardAssists(path: String) {
        appDb.runInTransaction {
            runBlocking {
                appDb.keyboardAssistsDao.deleteAll()
                fileToListT<KeyboardAssist>(path, "keyboardAssists.json")?.let {
                    appDb.keyboardAssistsDao.insert(*it.toTypedArray())
                }
            }
        }
    }

    // --- 阅读记录 ---

    fun restoreReadRecords(path: String) {
        appDb.runInTransaction {
            runBlocking {
                appDb.readRecordDao.clear()
                appDb.readRecordDao.clearDetails()
                appDb.readRecordDao.clearSessions()
                val readRecords = fileToListT<ReadRecord>(path, "readRecord.json").orEmpty()
                val readRecordDetails = fileToListT<ReadRecordDetail>(path, "readRecordDetail.json").orEmpty()
                val readRecordSessions = fileToListT<ReadRecordSession>(path, "readRecordSession.json").orEmpty()
                if (readRecords.isNotEmpty() || readRecordDetails.isNotEmpty() || readRecordSessions.isNotEmpty()) {
                    ReadRecordRepository(appDb.readRecordDao).apply {
                        importRecords(readRecords, readRecordDetails, readRecordSessions)
                        repairRecords { bookName -> appDb.bookDao.getBookByName(bookName)?.author?.trim()?.ifBlank { null } }
                    }
                    appCtx.putPrefInt(PreferKey.readRecordRepairVersion, ReadRecordRepository.CURRENT_REPAIR_VERSION)
                }
            }
        }
    }

    fun restoreReadRecordsSelective(path: String, selectedSet: Set<String>) {
        appDb.runInTransaction {
            runBlocking {
                appDb.readRecordDao.clear()
                appDb.readRecordDao.clearDetails()
                appDb.readRecordDao.clearSessions()
                val readRecords = if ("readRecord.json" in selectedSet)
                    fileToListT<ReadRecord>(path, "readRecord.json").orEmpty() else emptyList()
                val readRecordDetails = if ("readRecordDetail.json" in selectedSet)
                    fileToListT<ReadRecordDetail>(path, "readRecordDetail.json").orEmpty() else emptyList()
                val readRecordSessions = if ("readRecordSession.json" in selectedSet)
                    fileToListT<ReadRecordSession>(path, "readRecordSession.json").orEmpty() else emptyList()
                if (readRecords.isNotEmpty() || readRecordDetails.isNotEmpty() || readRecordSessions.isNotEmpty()) {
                    ReadRecordRepository(appDb.readRecordDao).apply {
                        importRecords(readRecords, readRecordDetails, readRecordSessions)
                        repairRecords { bookName -> appDb.bookDao.getBookByName(bookName)?.author?.trim()?.ifBlank { null } }
                    }
                    appCtx.putPrefInt(PreferKey.readRecordRepairVersion, ReadRecordRepository.CURRENT_REPAIR_VERSION)
                }
            }
        }
    }

    // --- 服务器配置（需解密） ---

    fun restoreServers(path: String, aes: BackupAES) {
        appDb.runInTransaction {
            appDb.serverDao.deleteAll()
            File(path, "servers.json").takeIf { it.exists() }?.runCatching {
                var json = readText()
                if (!json.isJsonArray()) {
                    json = aes.decryptStr(json)
                }
                GSON.fromJsonArray<Server>(json).getOrNull()?.let {
                    appDb.serverDao.insert(*it.toTypedArray())
                }
            }?.onFailure {
                AppLog.put("恢复服务器配置出错\n${it.localizedMessage}", it)
            }
        }
    }

    // --- 运行时书源缓存 ---

    fun restoreRuntimeSourceCaches(path: String) {
        val runtimeCacheFile = File(path, RestoreUtils.runtimeSourceCacheFileName)
        if (!runtimeCacheFile.exists()) return
        val caches = fileToListT<Cache>(path, RestoreUtils.runtimeSourceCacheFileName).orEmpty()
        appDb.cacheDao.deleteAllRuntimeSourceCaches()
        AppCacheManager.clearSourceVariables()
        if (caches.isNotEmpty()) {
            appDb.cacheDao.insert(*caches.toTypedArray())
        }
    }
}
