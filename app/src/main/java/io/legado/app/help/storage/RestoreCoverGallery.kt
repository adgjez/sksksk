package io.legado.app.help.storage

import io.legado.app.constant.EventBus
import io.legado.app.data.appDb
import io.legado.app.data.entities.CoverGalleryGroup
import io.legado.app.data.entities.CoverGalleryImage
import io.legado.app.data.repository.CoverGalleryRepository
import io.legado.app.help.CacheManager
import io.legado.app.model.BookCover
import io.legado.app.utils.LogUtils
import io.legado.app.utils.createFolderIfNotExist
import io.legado.app.utils.externalFiles
import io.legado.app.utils.getFile
import io.legado.app.utils.postEvent
import splitties.init.appCtx
import java.io.File

/**
 * 封面画廊恢复逻辑。
 *
 * 从备份的 CoverGallery 目录恢复：
 * - 删除旧数据和缓存
 * - 复制图片文件
 * - 重建分组和图片记录
 */
internal object RestoreCoverGallery {

    private const val TAG = "Restore"

    suspend fun restoreCoverGallery(path: String) {
        val galleryDir = File(path, CoverGalleryRepository.backupDirName)
        if (!galleryDir.exists() || !galleryDir.isDirectory) return
        val oldGroupIds = appDb.coverGalleryDao.allGroups.map { it.id }

        appDb.coverGalleryDao.deleteAllImages()
        appDb.coverGalleryDao.deleteAllGroups()

        appDb.cacheDao.deleteRuntimeSourceCachesByPrefix(CoverGalleryRepository.randomSeedKeyPrefix)
        oldGroupIds.forEach {
            CacheManager.deleteMemory(CoverGalleryRepository.randomSeedKeyPrefix + it)
        }

        val targetDir = appCtx.externalFiles.getFile("covers").createFolderIfNotExist()
        val usedImageNames = hashSetOf<String>()
        galleryDir.listFiles()
            ?.filter { it.isDirectory }
            ?.sortedBy { it.name }
            ?.forEachIndexed { groupIndex, groupDir ->
                val groupId = appDb.coverGalleryDao.insertGroup(
                    CoverGalleryGroup(
                        name = groupDir.name,
                        order = groupIndex
                    )
                )
                val images = groupDir.listFiles()
                    ?.filter { it.isFile && it.isCoverGalleryImageFile() }
                    ?.sortedBy { it.name }
                    ?.mapIndexed { imageIndex, imageFile ->
                        val targetFile = File(
                            targetDir,
                            uniqueCoverGalleryImageName(imageFile.name, usedImageNames)
                        )
                        imageFile.copyTo(targetFile, overwrite = true)
                        CoverGalleryImage(
                            groupId = groupId,
                            path = targetFile.absolutePath,
                            order = imageIndex
                        )
                    }
                    .orEmpty()
                if (images.isNotEmpty()) {
                    appDb.coverGalleryDao.insertImages(*images.toTypedArray())
                }
            }

        BookCover.upDefaultCover()
        postEvent(EventBus.BOOKSHELF_REFRESH, "")
    }

    private fun File.isCoverGalleryImageFile(): Boolean {
        return extension.lowercase() in setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif")
    }

    private fun uniqueCoverGalleryImageName(
        fileName: String,
        usedImageNames: MutableSet<String>
    ): String {
        val nameWithoutExtension = fileName.substringBeforeLast('.', fileName)
        val extension = fileName.substringAfterLast('.', "")
        var candidate = fileName
        var suffix = 2
        while (!usedImageNames.add(candidate)) {
            candidate = if (extension.isBlank()) {
                "$nameWithoutExtension-$suffix"
            } else {
                "$nameWithoutExtension-$suffix.$extension"
            }
            suffix++
        }
        return candidate
    }
}
