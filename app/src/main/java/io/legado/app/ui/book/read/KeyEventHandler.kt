package io.legado.app.ui.book.read

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfig
import io.legado.app.service.BaseReadAloudService
import io.legado.app.ui.book.read.page.delegate.ScrollPageDelegate
import io.legado.app.ui.book.read.page.entities.PageDirection
import io.legado.app.utils.Debounce
import io.legado.app.utils.LogUtils

/**
 * Extracted from ReadBookActivity to reduce file size.
 *
 * Handles all key / motion events for the reading page:
 * - dispatchKeyEvent (MENU key interception)
 * - onGenericMotionEvent (mouse wheel)
 * - onKeyDown / onKeyUp (page keys, volume keys, space)
 * - Debounce logic for rapid key presses
 *
 * The activity delegates these callbacks here; this class operates
 * on the activity's binding and state through the [host] interface.
 */
class KeyEventHandler(
    private val host: Host
) {
    private val nextPageDebounce by lazy { Debounce { keyPage(PageDirection.NEXT) } }
    private val prevPageDebounce by lazy { Debounce { keyPage(PageDirection.PREV) } }

    fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        val action = event.action
        val isDown = action == 0

        if (keyCode == KeyEvent.KEYCODE_MENU) {
            if (isDown && !host.canShowMenu) {
                host.runMenuIn()
                return true
            }
            if (!isDown && !host.canShowMenu) {
                host.setCanShowMenu(true)
                return true
            }
        }
        return false  // let Activity handle via super
    }

    fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (0 != (event.source and InputDevice.SOURCE_CLASS_POINTER)) {
            if (event.action == MotionEvent.ACTION_SCROLL) {
                val axisValue = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
                LogUtils.d("onGenericMotionEvent", "axisValue = $axisValue")
                if (axisValue < 0.0f) {
                    mouseWheelPage(PageDirection.NEXT, axisValue)
                } else {
                    mouseWheelPage(PageDirection.PREV, axisValue)
                }
                return true
            }
        }
        return false
    }

    fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (host.menuLayoutIsVisible) {
            return false  // super should handle
        }
        val longPress = event.repeatCount > 0
        when {
            host.isPrevKey(keyCode) -> {
                handleKeyPage(PageDirection.PREV, longPress)
                return true
            }
            host.isNextKey(keyCode) -> {
                handleKeyPage(PageDirection.NEXT, longPress)
                return true
            }
        }
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> if (volumeKeyPage(PageDirection.PREV, longPress)) {
                return true
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> if (volumeKeyPage(PageDirection.NEXT, longPress)) {
                return true
            }
            KeyEvent.KEYCODE_PAGE_UP -> {
                handleKeyPage(PageDirection.PREV, longPress)
                return true
            }
            KeyEvent.KEYCODE_PAGE_DOWN -> {
                handleKeyPage(PageDirection.NEXT, longPress)
                return true
            }
            KeyEvent.KEYCODE_SPACE -> {
                handleKeyPage(PageDirection.NEXT, longPress)
                return true
            }
        }
        return false  // super should handle
    }

    fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN -> {
                if (volumeKeyPage(PageDirection.NONE, false)) {
                    return true
                }
            }
        }
        return false
    }

    private fun mouseWheelPage(direction: PageDirection, distance: Float) {
        if (host.menuLayoutIsVisible || !AppConfig.mouseWheelPage) {
            return
        }
        if (host.isScroll) {
            (host.pageDelegate as? ScrollPageDelegate)?.curPage?.scroll((distance * 50).toInt())
        } else {
            keyPageDebounce(direction, mouseWheel = true, longPress = false)
        }
    }

    private fun volumeKeyPage(direction: PageDirection, longPress: Boolean): Boolean {
        if (!AppConfig.volumeKeyPage) {
            return false
        }
        if (!AppConfig.volumeKeyPageOnPlay && BaseReadAloudService.isPlay()) {
            return false
        }
        handleKeyPage(direction, longPress)
        return true
    }

    private fun handleKeyPage(direction: PageDirection, longPress: Boolean) {
        if (AppConfig.keyPageOnLongPress || direction == PageDirection.NONE) {
            keyPage(direction)
        } else {
            keyPageDebounce(direction, longPress = longPress)
        }
    }

    private fun keyPageDebounce(
        direction: PageDirection,
        mouseWheel: Boolean = false,
        longPress: Boolean
    ) {
        if (longPress) {
            return
        }
        nextPageDebounce.apply {
            wait = if (mouseWheel) 200L else 600L
            leading = !mouseWheel
            trailing = mouseWheel
        }
        prevPageDebounce.apply {
            wait = if (mouseWheel) 200L else 600L
            leading = !mouseWheel
            trailing = mouseWheel
        }
        when (direction) {
            PageDirection.NEXT -> nextPageDebounce.invoke()
            PageDirection.PREV -> prevPageDebounce.invoke()
            else -> {}
        }
    }

    private fun keyPage(direction: PageDirection) {
        host.cancelSelect()
        host.pageDelegateIsCancel = false
        host.keyTurnPage(direction)
    }

    /**
     * Interface that the Activity implements to provide access to
     * the state and operations needed by this handler.
     */
    interface Host {
        val menuLayoutIsVisible: Boolean
        val canShowMenu: Boolean
        fun setCanShowMenu(value: Boolean)
        fun runMenuIn()
        fun cancelSelect()
        val isScroll: Boolean
        val pageDelegate: Any?  // PageDelegate, typed as Any to avoid circular dependency
        var pageDelegateIsCancel: Boolean
        fun keyTurnPage(direction: PageDirection)
        fun isPrevKey(keyCode: Int): Boolean
        fun isNextKey(keyCode: Int): Boolean
    }
}
