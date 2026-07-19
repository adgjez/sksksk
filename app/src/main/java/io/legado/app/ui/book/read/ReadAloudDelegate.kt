package io.legado.app.ui.book.read

import io.legado.app.help.config.AppConfig
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.service.BaseReadAloudService
import io.legado.app.ui.book.read.config.ReadAloudActivity
import io.legado.app.utils.startActivity

/**
 * Extracted from ReadBookActivity to reduce file size.
 *
 * Encapsulates the read-aloud (TTS) state machine:
 * - toggleReadAloud: start / pause / resume based on service state
 * - MiniBar callbacks
 * - Progress sync helpers
 *
 * The complex state machine in [toggleReadAloud] handles three main branches:
 * 1. Service not running -> start reading (with scroll-page special handling)
 * 2. Service paused -> resume (with page-change detection for scroll mode)
 * 3. Service running -> pause or open UI
 *
 * @param host The activity, providing access to binding and state
 */
class ReadAloudDelegate(
    private val host: Host
) {
    private var pageChanged = false
    private var readAloudBackPressedOnce = false

    fun onPageChanged() {
        pageChanged = true
    }

    fun onBackPressed(): Boolean {
        if (!readAloudBackPressedOnce) {
            readAloudBackPressedOnce = true
            return true  // host should show toast
        }
        readAloudBackPressedOnce = false
        return false
    }

    fun onClickReadAloud() {
        if (AppConfig.readAloudFloatingUi) {
            toggleReadAloud(launchUi = false, allowPauseWhenRunning = false)
        } else {
            toggleReadAloud(launchUi = false, allowPauseWhenRunning = false)
            host.showReadAloudDialog()
        }
    }

    fun toggleReadAloud(launchUi: Boolean, allowPauseWhenRunning: Boolean) {
        host.autoPageStop()
        readAloudBackPressedOnce = false
        when {
            !BaseReadAloudService.isRun -> {
                ReadAloud.upReadAloudClass()
                val scrollPageAnim = ReadBook.pageAnim() == 3
                if (scrollPageAnim) {
                    val pos = host.getReadAloudPos()
                    if (pos != null) {
                        val (index, line) = pos
                        if (ReadBook.durChapterIndex != index) {
                            ReadBook.openChapter(index, line.chapterPosition, false) {
                                ReadBook.readAloud(startPos = line.pagePosition)
                                if (launchUi) openReadAloudActivity()
                            }
                        } else {
                            ReadBook.durChapterPos = line.chapterPosition
                            ReadBook.readAloud(startPos = line.pagePosition)
                            if (launchUi) openReadAloudActivity()
                        }
                    } else {
                        ReadBook.readAloud()
                        if (launchUi) openReadAloudActivity()
                    }
                } else {
                    ReadBook.readAloud()
                    if (launchUi) openReadAloudActivity()
                }
            }

            BaseReadAloudService.pause -> {
                val scrollPageAnim = ReadBook.pageAnim() == 3
                if (scrollPageAnim && pageChanged) {
                    pageChanged = false
                    val pos = host.getReadAloudPos()
                    if (pos != null) {
                        val (index, line) = pos
                        if (ReadBook.durChapterIndex != index) {
                            ReadBook.openChapter(index, line.chapterPosition, false) {
                                ReadBook.readAloud(startPos = line.pagePosition)
                                if (launchUi) openReadAloudActivity()
                            }
                        } else {
                            ReadBook.durChapterPos = line.chapterPosition
                            ReadBook.readAloud(startPos = line.pagePosition)
                            if (launchUi) openReadAloudActivity()
                        }
                    } else {
                        ReadBook.readAloud()
                        if (launchUi) openReadAloudActivity()
                    }
                } else if (BaseReadAloudService.hasPendingChapterSwitch()) {
                    ReadBook.readAloud()
                    if (launchUi) openReadAloudActivity()
                } else {
                    ReadAloud.resume(host.activityContext)
                    if (launchUi) openReadAloudActivity()
                }
            }

            else -> {
                if (launchUi) {
                    openReadAloudActivity()
                } else if (allowPauseWhenRunning) {
                    ReadAloud.pause(host.activityContext)
                } else {
                    host.refreshReadAloudMiniBar()
                }
            }
        }
    }

    private fun openReadAloudActivity() {
        host.activityContext.startActivity<ReadAloudActivity>()
    }

    fun isCurrentBookReadAloudBook(): Boolean {
        return BaseReadAloudService.isActiveBook(ReadBook.book?.bookUrl)
    }

    fun onReadAloudMiniBarClick() {
        if (isCurrentBookReadAloudBook()) {
            openReadAloudActivity()
        } else {
            host.onMiniBarClickDefault()
        }
    }

    fun onReadAloudMiniBarLongClick(): Boolean {
        host.showReadAloudDialog()
        return true
    }

    /**
     * Interface providing the activity-level operations needed by this delegate.
     */
    interface Host {
        val activityContext: android.content.Context
        val backgroundColor: Int
        fun autoPageStop()
        fun getReadAloudPos(): Pair<Int, io.legado.app.ui.book.read.page.entities.TextLine>?
        fun showReadAloudDialog()
        fun refreshReadAloudMiniBar()
        fun onMiniBarClickDefault()
    }
}
