package io.legado.app.help.storage

import io.legado.app.constant.AppLog
import io.legado.app.constant.BookType
import io.legado.app.constant.EventBus
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookGroup
import io.legado.app.help.book.BookHelp
import io.legado.app.utils.LogUtils
import io.legado.app.utils.postEvent
import splitties.init.appCtx
import java.io.File

/**
 * 书籍缓存恢复逻辑。
 *
 * 从备份中恢复：
 * - 书架信息（bookCacheBooks.json）
 * - 章节目录（bookChapterCache.json）
 * - 章节缓存文件（book_cache/ 目录）
 */
internal object RestoreBookCache {

    private const val TAG = "Restore"

    /**
     * 恢复书籍缓存。
     *
     * 流程：
     * 1. 恢复章节目录（如果有）
     * 2. 读取缓存索引文件
     * 3. 遍历索引，匹配当前设备上的书籍
     * 4. 获取当前书籍的章节列表
     * 5. 根据章节标题匹配，重命名章节文件
     * 6. 复制缓存文件到对应位置
     */
    fun restoreBookCache(path: String) {
        LogUtils.d(TAG, "开始恢复书籍缓存，路径: $path")

        if (BackupConfig.ignoreBookCache) {
            LogUtils.d(TAG, "忽略书籍缓存恢复（配置项已禁用）")
            AppLog.put("书籍缓存恢复被忽略，请在恢复配置中启用")
            return
        }

        val indexFile = File(path, RestoreUtils.bookCacheIndexFileName)
        if (!indexFile.exists()) {
            LogUtils.d(TAG, "书籍缓存索引文件不存在: ${indexFile.absolutePath}")
            AppLog.put("书籍缓存索引文件不存在，无法恢复书籍缓存")

            // 尝试从 bookCacheBooks.json 直接恢复书籍信息
            val booksFile = File(path, RestoreUtils.bookCacheBooksFileName)
            if (booksFile.exists()) {
                LogUtils.d(TAG, "尝试从 bookCacheBooks.json 直接恢复书籍信息")
                try {
                    ensureDefaultBookGroups()
                    val books = RestoreUtils.fileToListT<Book>(path, RestoreUtils.bookCacheBooksFileName)
                        .orEmpty()
                        .mapNotNull { it.sanitizeForCacheRestore() }

                    if (books.isNotEmpty()) {
                        LogUtils.d(TAG, "从 bookCacheBooks.json 读取到 ${books.size} 本书")
                        val localBooks = appDb.bookDao.all
                        val missingBooks = books.filter { book ->
                            val exists = localBooks.any { it.bookUrl == book.bookUrl || it.name == book.name }
                            LogUtils.d(TAG, "书籍《${book.name}》${if (exists) "已存在" else "不存在"}")
                            !exists
                        }.map { book ->
                            book.copy(
                                group = 0,
                                type = book.type and BookType.notShelf.inv()
                            )
                        }

                        if (missingBooks.isNotEmpty()) {
                            appDb.bookDao.insert(*missingBooks.toTypedArray())
                            LogUtils.d(TAG, "从 bookCacheBooks.json 恢复书籍: ${missingBooks.size}")
                            AppLog.put("从书籍缓存恢复 ${missingBooks.size} 本书到书架")
                            postEvent(EventBus.BOOKSHELF_REFRESH, "")
                        } else {
                            LogUtils.d(TAG, "所有书籍已存在，无需恢复")
                        }
                    }
                } catch (e: Exception) {
                    LogUtils.d(TAG, "从 bookCacheBooks.json 恢复失败: ${e.message}")
                    AppLog.put("从 bookCacheBooks.json 恢复失败\n${e.localizedMessage}", e)
                }
            }
            return
        }

        LogUtils.d(TAG, "找到书籍缓存索引文件: ${indexFile.absolutePath}, 大小: ${indexFile.length()}")

        val cacheIndexList = runCatching {
            val json = indexFile.readText()
            LogUtils.d(TAG, "索引文件内容长度: ${json.length}")
            RestoreUtils.parseBookCacheIndexList(json)
        }.getOrNull() ?: run {
            LogUtils.d(TAG, "解析书籍缓存索引失败")
            AppLog.put("书籍缓存索引文件解析失败")
            return
        }

        if (cacheIndexList.isEmpty()) {
            LogUtils.d(TAG, "书籍缓存索引为空")
            AppLog.put("书籍缓存索引为空")
            return
        }

        LogUtils.d(TAG, "解析到 ${cacheIndexList.size} 个书籍缓存索引")
        cacheIndexList.forEach { index ->
            LogUtils.d(TAG, "  - 《${index.bookName}》作者: ${index.author}, 目录: ${index.folderName}, 章节数: ${index.chapters.size}")
        }

        restoreBookCacheBooks(path, cacheIndexList)
        restoreBookChapterCache(path)

        val backupCacheDir = resolveBackupCacheDir(path, cacheIndexList)
        if (backupCacheDir == null) {
            LogUtils.d(TAG, "备份缓存目录不存在")
            return
        }

        val targetCacheDir = File(BookHelp.cachePath)
        if (!targetCacheDir.exists()) {
            targetCacheDir.mkdirs()
        }

        val allBooks = appDb.bookDao.all
        var restoredCount = 0
        var chapterRestoredCount = 0

        cacheIndexList.forEach { cacheIndex ->
            val matchedBook = findMatchingBook(cacheIndex, allBooks)
            if (matchedBook == null) {
                LogUtils.d(TAG, "未找到匹配书籍: ${cacheIndex.bookName}")
                return@forEach
            }

            val sourceCacheDir = File(backupCacheDir, cacheIndex.folderName)
            if (!sourceCacheDir.exists()) {
                LogUtils.d(TAG, "备份缓存目录不存在: ${cacheIndex.folderName}")
                return@forEach
            }

            val targetFolderName = matchedBook.getFolderName()
            val targetBookDir = File(targetCacheDir, targetFolderName)
            if (!targetBookDir.exists()) {
                targetBookDir.mkdirs()
            }

            val currentChapters = appDb.bookChapterDao.getChapterList(matchedBook.bookUrl)
            val currentChapterByIndex = currentChapters.associateBy { it.index }
            val currentChapterByTitle = currentChapters.associateBy { it.title }

            val copiedSourceNames = hashSetOf<String>()
            cacheIndex.chapters.forEach { chapterInfo ->
                val sourceFile = File(sourceCacheDir, chapterInfo.fileName)
                if (!sourceFile.exists()) {
                    return@forEach
                }

                val targetChapter = currentChapterByIndex[chapterInfo.index]
                    ?: currentChapterByTitle[chapterInfo.title]

                if (targetChapter == null) {
                    LogUtils.d(TAG, "未找到匹配章节: ${chapterInfo.title}")
                    return@forEach
                }

                val targetFileName = targetChapter.getFileName()
                val targetFile = File(targetBookDir, targetFileName)

                sourceFile.copyTo(targetFile, overwrite = true)
                copiedSourceNames.add(sourceFile.name)
                chapterRestoredCount++
            }
            sourceCacheDir.listFiles()
                ?.filter { it.isFile && it.name.endsWith(".nb") && it.name !in copiedSourceNames }
                ?.forEach { sourceFile ->
                    sourceFile.copyTo(File(targetBookDir, sourceFile.name), overwrite = true)
                    chapterRestoredCount++
                }

            val sourceImageDir = File(sourceCacheDir, "images")
            if (sourceImageDir.exists()) {
                val targetImageDir = File(targetBookDir, "images")
                sourceImageDir.copyRecursively(targetImageDir, overwrite = true)
            }

            restoredCount++
            LogUtils.d(TAG, "恢复书籍缓存: ${matchedBook.name} -> $targetFolderName")
        }

        LogUtils.d(TAG, "书籍缓存恢复完成，共恢复 $restoredCount 本书，$chapterRestoredCount 个章节")
    }

    /**
     * 从缓存恢复缺失书籍到书架。
     */
    private fun restoreBookCacheBooks(path: String, cacheIndexList: List<BookCacheIndex>) {
        LogUtils.d(TAG, "开始恢复书籍缓存书架信息")

        ensureDefaultBookGroups()
        LogUtils.d(TAG, "已确保默认书籍分组存在")

        val backupBooks = RestoreUtils.fileToListT<Book>(path, RestoreUtils.bookCacheBooksFileName)
            .orEmpty()
            .mapNotNull { it.sanitizeForCacheRestore() }

        LogUtils.d(TAG, "从 ${RestoreUtils.bookCacheBooksFileName} 读取到 ${backupBooks.size} 本书")

        val books = backupBooks.ifEmpty {
            LogUtils.d(TAG, "使用缓存索引生成最小书籍记录")
            cacheIndexList.map {
                Book(
                    bookUrl = it.bookUrl,
                    name = it.bookName,
                    author = it.author,
                    originName = it.bookName
                )
            }
        }

        if (books.isEmpty()) {
            LogUtils.d(TAG, "没有需要恢复的书籍")
            return
        }

        val localBooks = appDb.bookDao.all
        LogUtils.d(TAG, "当前数据库中有 ${localBooks.size} 本书")

        val missingBooks = books
            .filter { book ->
                val matched = findMatchingBook(
                    BookCacheIndex(
                        bookUrl = book.bookUrl,
                        bookName = book.name,
                        author = book.author,
                        folderName = book.getFolderName()
                    ),
                    localBooks
                )
                val exists = matched != null
                LogUtils.d(TAG, "书籍《${book.name}》${if (exists) "已存在 (匹配: ${matched?.name})" else "不存在，将恢复"}")
                !exists
            }
            .map { book ->
                book.copy(
                    group = 0,
                    type = book.type and BookType.notShelf.inv()
                )
            }

        if (missingBooks.isNotEmpty()) {
            LogUtils.d(TAG, "准备插入 ${missingBooks.size} 本缺失书籍")
            missingBooks.forEach { book ->
                LogUtils.d(TAG, "  - 《${book.name}》作者: ${book.author}, bookUrl: ${book.bookUrl}, type: ${book.type}, group: ${book.group}")
            }

            appDb.bookDao.insert(*missingBooks.toTypedArray())
            LogUtils.d(TAG, "恢复书籍缓存书架信息: ${missingBooks.size}")
            AppLog.put("从书籍缓存恢复 ${missingBooks.size} 本书到书架")
            postEvent(EventBus.BOOKSHELF_REFRESH, "")
        } else {
            LogUtils.d(TAG, "所有书籍已存在，无需恢复")
        }
    }

    /**
     * 确保默认书籍分组存在。
     */
    private fun ensureDefaultBookGroups() {
        val defaults = arrayOf(
            BookGroup(BookGroup.IdAll, appCtx.getString(io.legado.app.R.string.all), order = -10, show = true),
            BookGroup(
                BookGroup.IdLocal,
                appCtx.getString(io.legado.app.R.string.local),
                order = -9,
                enableRefresh = false,
                show = true
            ),
            BookGroup(BookGroup.IdAudio, appCtx.getString(io.legado.app.R.string.audio), order = -8, show = true),
            BookGroup(
                BookGroup.IdNetNone,
                appCtx.getString(io.legado.app.R.string.net_no_group),
                order = -7,
                show = true
            ),
            BookGroup(
                BookGroup.IdLocalNone,
                appCtx.getString(io.legado.app.R.string.local_no_group),
                order = -6,
                show = false
            ),
            BookGroup(BookGroup.IdVideo, appCtx.getString(io.legado.app.R.string.video), order = -5, show = true),
            BookGroup(
                BookGroup.IdError,
                appCtx.getString(io.legado.app.R.string.update_book_fail),
                order = -1,
                show = true
            )
        ).filter { appDb.bookGroupDao.getByID(it.groupId) == null }

        if (defaults.isNotEmpty()) {
            appDb.bookGroupDao.insert(*defaults.toTypedArray())
        }
    }

    /**
     * 定位备份中的书籍缓存目录。
     */
    private fun resolveBackupCacheDir(path: String, cacheIndexList: List<BookCacheIndex>): File? {
        val cacheDir = File(path, RestoreUtils.bookCacheFolderName)
        if (cacheDir.exists()) {
            return cacheDir
        }
        return File(path).takeIf { rootDir ->
            cacheIndexList.any { File(rootDir, it.folderName).exists() }
        }
    }

    /**
     * 从 bookChapterCache.json 恢复章节目录数据。
     */
    private fun restoreBookChapterCache(path: String) {
        val chapterFile = File(path, "bookChapterCache.json")
        if (!chapterFile.exists()) {
            LogUtils.d(TAG, "章节目录文件不存在")
            return
        }

        val chapters = RestoreUtils.fileToListT<BookChapter>(path, "bookChapterCache.json")
        if (chapters.isNullOrEmpty()) {
            LogUtils.d(TAG, "章节目录为空")
            return
        }

        val chaptersByBook = chapters.groupBy { it.bookUrl }
        var restoredBookCount = 0
        var restoredChapterCount = 0

        chaptersByBook.forEach { (bookUrl, chapterList) ->
            val book = appDb.bookDao.getBook(bookUrl)
            if (book == null) {
                val cacheIndexFile = File(path, RestoreUtils.bookCacheIndexFileName)
                if (cacheIndexFile.exists()) {
                    val cacheIndexList = runCatching {
                        RestoreUtils.parseBookCacheIndexList(cacheIndexFile.readText())
                    }.getOrNull()

                    val cacheIndex = cacheIndexList?.find { it.bookUrl == bookUrl }
                    if (cacheIndex != null) {
                        val matchedBook = appDb.bookDao.all.find { it.name == cacheIndex.bookName }
                        if (matchedBook != null) {
                            val updatedChapters = chapterList.map { chapter ->
                                chapter.copy(bookUrl = matchedBook.bookUrl)
                            }
                            appDb.bookChapterDao.delByBook(matchedBook.bookUrl)
                            appDb.bookChapterDao.insert(*updatedChapters.toTypedArray())
                            restoredBookCount++
                            restoredChapterCount += updatedChapters.size
                            LogUtils.d(TAG, "恢复章节目录: ${matchedBook.name}, ${updatedChapters.size} 章")
                        }
                    }
                }
            } else {
                appDb.bookChapterDao.delByBook(bookUrl)
                appDb.bookChapterDao.insert(*chapterList.toTypedArray())
                restoredBookCount++
                restoredChapterCount += chapterList.size
                LogUtils.d(TAG, "恢复章节目录: ${book.name}, ${chapterList.size} 章")
            }
        }

        LogUtils.d(TAG, "章节目录恢复完成，共 $restoredBookCount 本书，$restoredChapterCount 章")
    }

    /**
     * 查找匹配的书籍。
     * 匹配策略：bookUrl 精确 > 书名+作者 > 书名模糊。
     */
    private fun findMatchingBook(
        cacheIndex: BookCacheIndex,
        allBooks: List<Book>
    ): Book? {
        allBooks.find { it.bookUrl == cacheIndex.bookUrl }?.let { return it }

        val normalizedAuthor = cacheIndex.author.trim()
        allBooks.filter {
            it.name == cacheIndex.bookName &&
                (it.author?.trim() ?: "") == normalizedAuthor
        }.firstOrNull()?.let { return it }

        allBooks.filter { it.name == cacheIndex.bookName }.firstOrNull()?.let { return it }

        return null
    }
}
