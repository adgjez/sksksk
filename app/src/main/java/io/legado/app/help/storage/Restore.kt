package io.legado.app.help.storage

import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import androidx.documentfile.provider.DocumentFile
import io.legado.app.BuildConfig
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.help.DirectLinkUpload
import io.legado.app.help.LauncherIconHelp
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.config.ThemeConfig
import io.legado.app.model.BookCover
import io.legado.app.model.VideoPlay.VIDEO_PREF_NAME
import io.legado.app.ui.book.read.config.highlight.HighlightRuleStore
import io.legado.app.ui.widget.image.CoverImageView
import io.legado.app.utils.ACache
import io.legado.app.utils.FileUtils
import io.legado.app.utils.LogUtils
import io.legado.app.utils.compress.ZipUtils
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.getPrefString
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.openInputStream
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import java.io.File

/**
 * 恢复管理类
 *
 * 负责从备份文件恢复应用数据，包括：
 * - 解压备份ZIP文件
 * - 恢复数据库数据（书籍、书签、书源等）
 * - 恢复SharedPreferences配置
 * - 恢复自定义配置文件
 *
 * 恢复流程：
 * 1. 解压ZIP文件到临时目录
 * 2. 读取JSON文件并导入数据库
 * 3. 恢复SharedPreferences配置
 * 4. 应用主题和阅读配置
 * 5. 清理临时文件
 *
 * 特殊处理：
 * - 书籍数据：支持忽略本地书籍，更新已存在书籍
 * - 阅读记录：恢复前清空本地记录，再导入备份记录
 * - 服务器配置：需要解密
 * - WebDav密码：需要解密
 */
object Restore {
    private const val TAG = "Restore"

    /** 互斥锁，防止并发恢复操作 */
    private val mutex = Mutex()

    /**
     * 从URI恢复备份
     * 支持SAF（Storage Access Framework）和普通文件路径
     */
    suspend fun restore(
        context: Context,
        uri: Uri,
        onProgress: ((String) -> Unit)? = null
    ) {
        LogUtils.d(TAG, "开始恢复备份 uri:$uri")
        kotlin.runCatching {
            onProgress?.invoke(BackupInfoHelper.getDisplayName("unzipBackup"))
            FileUtils.delete(Backup.backupPath)
            if (uri.isContentScheme()) {
                DocumentFile.fromSingleUri(context, uri)!!.openInputStream()!!.use {
                    ZipUtils.unZipToPath(it, Backup.backupPath)
                }
            } else {
                ZipUtils.unZipToPath(File(uri.path!!), Backup.backupPath)
            }
        }.onFailure {
            AppLog.put("复制解压文件出错\n${it.localizedMessage}", it)
            return
        }
        kotlin.runCatching {
            restoreLocked(Backup.backupPath, onProgress)
            LocalConfig.lastBackup = System.currentTimeMillis()
            LocalConfig.lastRestore = System.currentTimeMillis()
        }.onFailure {
            appCtx.toastOnUi("恢复备份出错\n${it.localizedMessage}")
            AppLog.put("恢复备份出错\n${it.localizedMessage}", it)
        }
    }

    /**
     * 带锁的恢复方法
     */
    suspend fun restoreLocked(
        path: String,
        onProgress: ((String) -> Unit)? = null
    ) {
        mutex.withLock {
            restoreAll(path, onProgress)
        }
    }

    /**
     * 选择性恢复方法
     * 只恢复用户选中的文件
     */
    suspend fun restoreSelected(
        context: Context,
        path: String,
        selectedFiles: List<String>,
        onProgress: ((String) -> Unit)? = null
    ) {
        LogUtils.d(TAG, "开始选择性恢复备份 path:$path, files:${selectedFiles.joinToString()}")
        mutex.withLock {
            try {
                restoreSelectedFiles(path, selectedFiles, onProgress)
                LocalConfig.lastBackup = System.currentTimeMillis()
                LocalConfig.lastRestore = System.currentTimeMillis()
            } catch (e: Exception) {
                appCtx.toastOnUi("恢复备份出错\n${e.localizedMessage}")
                AppLog.put("选择性恢复备份出错\n${e.localizedMessage}", e)
            }
        }
    }

    // --- 全量恢复 ---

    private suspend fun restoreAll(
        path: String,
        onProgress: ((String) -> Unit)? = null
    ) {
        val aes = BackupAES()
        fun progress(fileName: String) {
            onProgress?.invoke(BackupInfoHelper.getDisplayName(fileName))
        }

        // 数据库恢复
        progress("bookshelf.json"); RestoreData.restoreBooks(path)
        progress("bookmark.json"); RestoreData.restoreBookmarks(path)
        progress("bookGroup.json"); RestoreData.restoreBookGroups(path)
        progress("bookSource.json"); RestoreData.restoreBookSources(path)
        progress("rssSources.json"); RestoreData.restoreRssSources(path)
        progress("rssStar.json"); RestoreData.restoreRssStars(path)
        progress("sourceSub.json"); RestoreData.restoreRuleSubs(path)
        progress("webSearchEngines.json"); RestoreData.restoreSearchEngines(path)
        progress("homepage.json"); RestoreData.restoreHomepage(path)
        progress("replaceRule.json"); RestoreData.restoreReplaceRules(path)
        progress(HighlightRuleStore.backupFileName); RestoreData.restoreHighlightRules(path)
        progress("searchHistory.json"); RestoreData.restoreSearchHistory(path)
        progress("txtTocRule.json"); RestoreData.restoreTxtTocRules(path)
        progress("httpTTS.json"); RestoreData.restoreHttpTTS(path)
        progress("dictRule.json"); RestoreData.restoreDictRules(path)
        progress("keyboardAssists.json"); RestoreData.restoreKeyboardAssists(path)

        progress(io.legado.app.data.repository.CoverGalleryRepository.backupDirName); RestoreCoverGallery.restoreCoverGallery(path)
        progress("readRecord.json"); RestoreData.restoreReadRecords(path)
        progress("servers.json"); RestoreData.restoreServers(path, aes)

        // 配置文件恢复
        progress(DirectLinkUpload.ruleFileName); restoreDirectLinkUpload(path)
        progress(ThemeConfig.configFileName); restoreThemeConfig(path)
        progress(BookCover.configFileName); restoreCoverRule(path)

        // 阅读界面配置
        if (!BackupConfig.ignoreReadConfig) {
            progress("backgroundImages"); RestoreThemeBackground.restoreReadConfigBackgrounds(path)
            progress(ReadBookConfig.configFileName); restoreReadBookConfigFile(path)
            progress(ReadBookConfig.shareConfigFileName); restoreShareConfigFile(path)
        }
        RestoreThemeBackground.fixReadConfigBackgroundPaths()

        // SharedPreferences 恢复
        progress("config.xml"); restoreSharedPreferences(path, aes, allowHighlightKeys = !File(path, HighlightRuleStore.backupFileName).exists())

        // 主题背景恢复
        progress("themeBackgroundImages")
        RestoreThemeBackground.restoreThemeBackgrounds(path, clearExisting = true)
        RestoreThemeBackground.fixThemeBackgroundPaths()
        RestoreThemeBackground.fixThemeConfigBackgroundPaths()

        // 运行时书源缓存 + 书籍缓存
        progress(RestoreUtils.runtimeSourceCacheFileName); RestoreData.restoreRuntimeSourceCaches(path)
        progress(RestoreUtils.bookCacheFolderName); RestoreBookCache.restoreBookCache(path)

        // 视频播放配置
        progress("videoConfig.xml"); restoreVideoConfig(path)

        // 应用配置
        applyRestoreConfig(progress)
    }

    // --- 选择性恢复 ---

    private suspend fun restoreSelectedFiles(
        path: String,
        selectedFiles: List<String>,
        onProgress: ((String) -> Unit)? = null
    ) {
        val aes = BackupAES()
        val selectedSet = selectedFiles.toSet()
        fun progress(fileName: String) {
            onProgress?.invoke(BackupInfoHelper.getDisplayName(fileName))
        }

        // 数据库恢复（按选中条件）
        if ("bookshelf.json" in selectedSet) { progress("bookshelf.json"); RestoreData.restoreBooks(path) }
        if ("bookmark.json" in selectedSet) { progress("bookmark.json"); RestoreData.restoreBookmarks(path) }
        if ("bookGroup.json" in selectedSet) { progress("bookGroup.json"); RestoreData.restoreBookGroups(path) }
        if ("bookSource.json" in selectedSet) { progress("bookSource.json"); RestoreData.restoreBookSources(path) }
        if ("rssSources.json" in selectedSet) { progress("rssSources.json"); RestoreData.restoreRssSources(path) }
        if ("rssStar.json" in selectedSet) { progress("rssStar.json"); RestoreData.restoreRssStars(path) }
        if ("sourceSub.json" in selectedSet) { progress("sourceSub.json"); RestoreData.restoreRuleSubs(path) }
        if ("webSearchEngines.json" in selectedSet) { progress("webSearchEngines.json"); RestoreData.restoreSearchEngines(path) }
        if ("homepage.json" in selectedSet) { progress("homepage.json"); RestoreData.restoreHomepage(path) }
        if ("replaceRule.json" in selectedSet) { progress("replaceRule.json"); RestoreData.restoreReplaceRules(path) }
        if (HighlightRuleStore.backupFileName in selectedSet) { progress(HighlightRuleStore.backupFileName); RestoreData.restoreHighlightRules(path) }
        if ("searchHistory.json" in selectedSet) { progress("searchHistory.json"); RestoreData.restoreSearchHistory(path) }
        if ("txtTocRule.json" in selectedSet) { progress("txtTocRule.json"); RestoreData.restoreTxtTocRules(path) }
        if ("httpTTS.json" in selectedSet) { progress("httpTTS.json"); RestoreData.restoreHttpTTS(path) }
        if ("dictRule.json" in selectedSet) { progress("dictRule.json"); RestoreData.restoreDictRules(path) }
        if ("keyboardAssists.json" in selectedSet) { progress("keyboardAssists.json"); RestoreData.restoreKeyboardAssists(path) }

        if (io.legado.app.data.repository.CoverGalleryRepository.backupDirName in selectedSet) {
            progress(io.legado.app.data.repository.CoverGalleryRepository.backupDirName)
            RestoreCoverGallery.restoreCoverGallery(path)
        }

        if ("readRecord.json" in selectedSet || "readRecordDetail.json" in selectedSet || "readRecordSession.json" in selectedSet) {
            progress("readRecord.json")
            RestoreData.restoreReadRecordsSelective(path, selectedSet)
        }

        if ("servers.json" in selectedSet) { progress("servers.json"); RestoreData.restoreServers(path, aes) }

        // 配置文件恢复
        if (DirectLinkUpload.ruleFileName in selectedSet) { progress(DirectLinkUpload.ruleFileName); restoreDirectLinkUpload(path) }
        if (ThemeConfig.configFileName in selectedSet) { progress(ThemeConfig.configFileName); restoreThemeConfig(path) }
        if (BookCover.configFileName in selectedSet) { progress(BookCover.configFileName); restoreCoverRule(path) }

        // 阅读界面配置
        if (!BackupConfig.ignoreReadConfig && (ReadBookConfig.configFileName in selectedSet || ReadBookConfig.shareConfigFileName in selectedSet)) {
            progress("backgroundImages"); RestoreThemeBackground.restoreReadConfigBackgrounds(path)
            if (ReadBookConfig.configFileName in selectedSet) { progress(ReadBookConfig.configFileName); restoreReadBookConfigFile(path) }
            if (ReadBookConfig.shareConfigFileName in selectedSet) { progress(ReadBookConfig.shareConfigFileName); restoreShareConfigFile(path) }
        }
        RestoreThemeBackground.fixReadConfigBackgroundPaths()

        // SharedPreferences 恢复
        if ("config.xml" in selectedSet) {
            progress("config.xml")
            restoreSharedPreferences(path, aes, allowHighlightKeys = !File(path, HighlightRuleStore.backupFileName).exists())
        }

        // 主题背景恢复
        progress("themeBackgroundImages")
        RestoreThemeBackground.restoreThemeBackgrounds(
            backupPath = path,
            clearExisting = "config.xml" in selectedSet || ThemeConfig.configFileName in selectedSet
        )
        RestoreThemeBackground.fixThemeBackgroundPaths()
        RestoreThemeBackground.fixThemeConfigBackgroundPaths()

        // 运行时书源缓存 + 书籍缓存
        if (RestoreUtils.runtimeSourceCacheFileName in selectedSet) {
            progress(RestoreUtils.runtimeSourceCacheFileName)
            RestoreData.restoreRuntimeSourceCaches(path)
        }

        if (RestoreUtils.bookCacheFolderName in selectedSet ||
            RestoreUtils.bookCacheIndexFileName in selectedSet ||
            RestoreUtils.bookCacheBooksFileName in selectedSet ||
            "bookChapterCache.json" in selectedSet
        ) {
            progress(RestoreUtils.bookCacheFolderName)
            RestoreBookCache.restoreBookCache(path)
        }

        // 视频播放配置
        if ("videoConfig.xml" in selectedSet) { progress("videoConfig.xml"); restoreVideoConfig(path) }

        // 应用配置
        applyRestoreConfig(progress)
    }

    // --- 共享配置恢复方法 ---

    private fun restoreDirectLinkUpload(path: String) {
        File(path, DirectLinkUpload.ruleFileName).takeIf { it.exists() }?.runCatching {
            val json = readText()
            ACache.get(cacheDir = false).put(DirectLinkUpload.ruleFileName, json)
        }?.onFailure { AppLog.put("恢复直链上传出错\n${it.localizedMessage}", it) }
    }

    private fun restoreThemeConfig(path: String) {
        File(path, ThemeConfig.configFileName).takeIf { it.exists() }?.runCatching {
            val configs = io.legado.app.utils.GSON.fromJsonArray<ThemeConfig.Config>(readText()).getOrNull()
            FileUtils.delete(ThemeConfig.configFilePath)
            copyTo(File(ThemeConfig.configFilePath))
            ThemeConfig.replaceConfigs(configs)
        }?.onFailure { AppLog.put("恢复主题出错\n${it.localizedMessage}", it) }
    }

    private fun restoreCoverRule(path: String) {
        File(path, BookCover.configFileName).takeIf { it.exists() }?.runCatching {
            val json = readText()
            BookCover.saveCoverRule(json)
            CoverImageView.clearAllCache()
        }?.onFailure { AppLog.put("恢复封面规则出错\n${it.localizedMessage}", it) }
    }

    private fun restoreReadBookConfigFile(path: String) {
        File(path, ReadBookConfig.configFileName).takeIf { it.exists() }?.runCatching {
            FileUtils.delete(ReadBookConfig.configFilePath)
            copyTo(File(ReadBookConfig.configFilePath))
            ReadBookConfig.initConfigs()
        }?.onFailure { AppLog.put("恢复阅读界面出错\n${it.localizedMessage}", it) }
    }

    private fun restoreShareConfigFile(path: String) {
        File(path, ReadBookConfig.shareConfigFileName).takeIf { it.exists() }?.runCatching {
            FileUtils.delete(ReadBookConfig.shareConfigFilePath)
            copyTo(File(ReadBookConfig.shareConfigFilePath))
            ReadBookConfig.initShareConfig()
        }?.onFailure { AppLog.put("恢复阅读界面出错\n${it.localizedMessage}", it) }
    }

    private fun restoreSharedPreferences(path: String, aes: BackupAES, allowHighlightKeys: Boolean) {
        RestoreUtils.readBackupPrefs(path, "config")?.let { map ->
            RestoreThemeBackground.clearThemeRestorePrefs()
            val edit = appCtx.defaultSharedPreferences.edit()
            map.forEach { (key, value) ->
                if (BackupConfig.keyIsNotIgnore(key, allowHighlightKeys) || key in RestoreThemeBackground.themeRestorePrefKeys) {
                    when (key) {
                        PreferKey.webDavPassword -> {
                            kotlin.runCatching { aes.decryptStr(value.toString()) }.getOrNull()?.let {
                                edit.putString(key, it)
                            } ?: let {
                                if (appCtx.getPrefString(PreferKey.webDavPassword).isNullOrBlank()) {
                                    edit.putString(key, value.toString())
                                }
                            }
                        }
                        else -> when (value) {
                            is Int -> edit.putInt(key, value)
                            is Boolean -> edit.putBoolean(key, value)
                            is Long -> edit.putLong(key, value)
                            is Float -> edit.putFloat(key, value)
                            is String -> edit.putString(key, value)
                        }
                    }
                }
            }
            edit.apply()
        }
        if (allowHighlightKeys) {
            HighlightRuleStore.clearCache()
        }
    }

    private fun restoreVideoConfig(path: String) {
        RestoreUtils.readBackupPrefs(path, "videoConfig")?.let { map ->
            appCtx.getSharedPreferences(VIDEO_PREF_NAME, Context.MODE_PRIVATE).edit().apply {
                clear()
                map.forEach { (key, value) ->
                    when (value) {
                        is Int -> putInt(key, value)
                        is Boolean -> putBoolean(key, value)
                        is Long -> putLong(key, value)
                        is Float -> putFloat(key, value)
                        is String -> putString(key, value)
                    }
                }
                apply()
            }
        }
    }

    private suspend fun applyRestoreConfig(progress: (String) -> Unit) {
        progress("applyRestoreConfig")
        ReadBookConfig.apply {
            comicStyleSelect = appCtx.getPrefInt(PreferKey.comicStyleSelect)
            readStyleSelect = appCtx.getPrefInt(PreferKey.readStyleSelect)
            shareLayout = appCtx.getPrefBoolean(PreferKey.shareLayout)
            hideStatusBar = appCtx.getPrefBoolean(PreferKey.hideStatusBar)
            hideNavigationBar = appCtx.getPrefBoolean(PreferKey.hideNavigationBar)
            autoReadSpeed = appCtx.getPrefInt(PreferKey.autoReadSpeed, 46)
        }

        appCtx.toastOnUi(R.string.restore_success)

        withContext(Main) {
            delay(100)
            if (!BuildConfig.DEBUG) {
                LauncherIconHelp.changeIcon(appCtx.getPrefString(PreferKey.launcherIcon))
            }
            ThemeConfig.applyDayNight(appCtx)
        }
    }
}
