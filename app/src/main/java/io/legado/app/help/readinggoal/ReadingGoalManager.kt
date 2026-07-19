package io.legado.app.help.readinggoal

import android.content.Context
import io.legado.app.constant.PreferKey
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.getPrefLong
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefInt
import io.legado.app.utils.putPrefLong
import io.legado.app.utils.putPrefString
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 阅读目标管理器。
 *
 * 功能：
 * - 每日阅读时长目标设定
 * - 当日阅读时长追踪（跨日自动重置）
 * - 连续打卡天数计算
 * - 阅读提醒间隔管理
 *
 * 数据持久化到 SharedPreferences，不依赖数据库。
 */
object ReadingGoalManager {

    private val dateFormatter = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    // --- 每日目标 ---

    /** 设置每日阅读目标（分钟）。0 表示关闭目标。 */
    fun setDailyGoal(context: Context, minutes: Int) {
        context.putPrefInt(PreferKey.dailyReadingGoalMin, minutes.coerceAtLeast(0))
    }

    /** 获取每日阅读目标（分钟）。0 表示未设置。 */
    fun getDailyGoal(context: Context): Int {
        return context.getPrefInt(PreferKey.dailyReadingGoalMin, 0)
    }

    /** 是否启用了阅读目标。 */
    fun isGoalEnabled(context: Context): Boolean {
        return getDailyGoal(context) > 0
    }

    // --- 今日阅读时长 ---

    /**
     * 记录本次阅读时长（分钟）。
     * 自动处理跨日重置：如果当前日期与记录日期不同，先重置再累加。
     */
    fun recordReading(context: Context, minutes: Int) {
        if (minutes <= 0) return
        ensureDailyReset(context)
        val current = context.getPrefInt(PreferKey.todayReadingMinutes, 0)
        val newTotal = current + minutes
        context.putPrefInt(PreferKey.todayReadingMinutes, newTotal)

        // 更新连续打卡
        updateStreak(context)
    }

    /** 获取今日已读时长（分钟）。自动处理跨日重置。 */
    fun getTodayMinutes(context: Context): Int {
        ensureDailyReset(context)
        return context.getPrefInt(PreferKey.todayReadingMinutes, 0)
    }

    /** 获取今日阅读进度百分比（0-100）。无目标时返回 0。 */
    fun getTodayProgressPercent(context: Context): Int {
        val goal = getDailyGoal(context)
        if (goal <= 0) return 0
        val today = getTodayMinutes(context)
        return (today * 100 / goal).coerceAtMost(100)
    }

    /** 今日目标是否已达成。 */
    fun isGoalAchieved(context: Context): Boolean {
        val goal = getDailyGoal(context)
        if (goal <= 0) return false
        return getTodayMinutes(context) >= goal
    }

    /** 距离目标还差多少分钟。负数或 0 表示已达成。 */
    fun remainingMinutes(context: Context): Int {
        val goal = getDailyGoal(context)
        if (goal <= 0) return 0
        return (goal - getTodayMinutes(context)).coerceAtLeast(0)
    }

    // --- 连续打卡 ---

    /** 获取连续阅读天数。 */
    fun getStreakDays(context: Context): Int {
        return context.getPrefInt(PreferKey.readingStreakDays, 0)
    }

    /**
     * 更新连续打卡。
     * 规则：
     * - 如果今天已经记录过，不重复增加
     * - 如果昨天有记录，连续天数 +1
     * - 如果间隔超过一天，重置为 1
     */
    private fun updateStreak(context: Context) {
        val today = todayString()
        val lastDate = context.getPrefString(PreferKey.lastReadingDate, "")

        if (lastDate == today) {
            // 今天已经记录过，不重复增加
            return
        }

        val yesterday = dateString(System.currentTimeMillis() - 24 * 3600 * 1000L)
        val currentStreak = context.getPrefInt(PreferKey.readingStreakDays, 0)

        val newStreak = when {
            lastDate == yesterday -> currentStreak + 1  // 连续
            lastDate.isBlank() -> 1  // 首次
            else -> 1  // 断了，重新计数
        }

        context.putPrefInt(PreferKey.readingStreakDays, newStreak)
        context.putPrefString(PreferKey.lastReadingDate, today)
    }

    // --- 跨日重置 ---

    /**
     * 检查是否跨日，如果是则重置今日阅读时长。
     * 在所有读取操作前调用。
     */
    private fun ensureDailyReset(context: Context) {
        val today = todayString()
        val storedDate = context.getPrefString(PreferKey.todayReadingDate, "")
        if (storedDate != today) {
            // 跨日了，重置
            context.putPrefInt(PreferKey.todayReadingMinutes, 0)
            context.putPrefString(PreferKey.todayReadingDate, today)
        }
    }

    // --- 阅读提醒 ---

    /** 设置阅读提醒间隔（分钟）。0 表示关闭。 */
    fun setReminderInterval(context: Context, minutes: Int) {
        context.putPrefInt(PreferKey.readingReminderInterval, minutes.coerceAtLeast(0))
    }

    /** 获取阅读提醒间隔（分钟）。0 表示关闭。 */
    fun getReminderInterval(context: Context): Int {
        return context.getPrefInt(PreferKey.readingReminderInterval, 0)
    }

    /** 是否启用了阅读提醒。 */
    fun isReminderEnabled(context: Context): Boolean {
        return getReminderInterval(context) > 0
    }

    /**
     * 检查是否应该提醒阅读。
     * 规则：距离上次提醒超过间隔时间，且今天尚未达成目标。
     */
    fun shouldRemind(context: Context): Boolean {
        if (!isReminderEnabled(context)) return false
        if (isGoalAchieved(context)) return false

        val interval = getReminderInterval(context)
        val lastReminder = context.getPrefLong(PreferKey.lastReminderTime, 0L)
        if (lastReminder == 0L) return true

        val elapsed = (System.currentTimeMillis() - lastReminder) / 60000
        return elapsed >= interval
    }

    /** 记录本次提醒时间。 */
    fun recordReminder(context: Context) {
        context.putPrefLong(PreferKey.lastReminderTime, System.currentTimeMillis())
    }

    // --- 统计摘要 ---

    /**
     * 获取阅读目标摘要文本，用于 UI 显示。
     * 例："今日已读 45/60 分钟 (75%) · 连续 5 天"
     */
    fun getSummary(context: Context): String {
        val goal = getDailyGoal(context)
        val today = getTodayMinutes(context)
        val streak = getStreakDays(context)

        return if (goal > 0) {
            val percent = (today * 100 / goal).coerceAtMost(100)
            val goalText = "$today/$goal 分钟 ($percent%)"
            if (streak > 0) {
                "$goalText · 连续 $streak 天"
            } else {
                goalText
            }
        } else if (today > 0) {
            "今日已读 $today 分钟" + if (streak > 0) " · 连续 $streak 天" else ""
        } else {
            "未设置阅读目标"
        }
    }

    // --- 日期工具 ---

    private fun todayString(): String = dateString(System.currentTimeMillis())

    private fun dateString(timestamp: Long): String {
        return dateFormatter.format(Date(timestamp))
    }
}
