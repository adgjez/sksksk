package io.legado.app.ui.readinggoal

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.help.readinggoal.ReadingGoalManager
import io.legado.app.ui.theme.AppTheme

class ReadingGoalActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme {
                ReadingGoalScreen(onBack = { finish() })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReadingGoalScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var dailyGoal by remember { mutableIntStateOf(ReadingGoalManager.getDailyGoal(context)) }
    var todayMinutes by remember { mutableIntStateOf(ReadingGoalManager.getTodayMinutes(context)) }
    var streakDays by remember { mutableIntStateOf(ReadingGoalManager.getStreakDays(context)) }
    var reminderEnabled by remember {
        mutableStateOf(ReadingGoalManager.isReminderEnabled(context))
    }
    var reminderInterval by remember {
        mutableFloatStateOf(ReadingGoalManager.getReminderInterval(context).toFloat())
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("阅读目标") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // --- 今日进度卡片 ---
            ProgressCard(
                dailyGoal = dailyGoal,
                todayMinutes = todayMinutes,
                streakDays = streakDays
            )

            // --- 每日目标设置 ---
            GoalSettingCard(
                dailyGoal = dailyGoal,
                onGoalChange = { newGoal ->
                    dailyGoal = newGoal
                    ReadingGoalManager.setDailyGoal(context, newGoal)
                    todayMinutes = ReadingGoalManager.getTodayMinutes(context)
                }
            )

            // --- 阅读提醒设置 ---
            ReminderCard(
                enabled = reminderEnabled,
                interval = reminderInterval,
                onEnabledChange = { enabled ->
                    reminderEnabled = enabled
                    if (!enabled) {
                        ReadingGoalManager.setReminderInterval(context, 0)
                    } else if (reminderInterval <= 0f) {
                        reminderInterval = 30f
                        ReadingGoalManager.setReminderInterval(context, 30)
                    }
                },
                onIntervalChange = { interval ->
                    reminderInterval = interval
                    ReadingGoalManager.setReminderInterval(context, interval.toInt())
                }
            )

            // --- 统计摘要 ---
            SummaryCard(context = context)

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ProgressCard(
    dailyGoal: Int,
    todayMinutes: Int,
    streakDays: Int
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (dailyGoal > 0) {
                val percent = (todayMinutes * 100 / dailyGoal).coerceAtMost(100)
                Text(
                    text = "$todayMinutes / $dailyGoal 分钟",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                LinearProgressIndicator(
                    progress = { percent / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp),
                )
                Text(
                    text = if (percent >= 100) "已达成目标!" else "还差 ${dailyGoal - todayMinutes} 分钟",
                    fontSize = 14.sp,
                    color = if (percent >= 100)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    text = "$todayMinutes 分钟",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "未设置每日目标",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (streakDays > 0) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.tertiary
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "连续阅读 $streakDays 天",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
            }
        }
    }
}

@Composable
private fun GoalSettingCard(
    dailyGoal: Int,
    onGoalChange: (Int) -> Unit
) {
    val presetGoals = listOf(0, 15, 30, 45, 60, 90, 120)

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "每日阅读目标",
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold
            )

            Text(
                text = "选择每天希望阅读的时长:",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                presetGoals.forEach { minutes ->
                    FilterChip(
                        selected = dailyGoal == minutes,
                        onClick = { onGoalChange(minutes) },
                        label = {
                            Text(if (minutes == 0) "关闭" else "${minutes}分")
                        }
                    )
                }
            }

            if (dailyGoal > 0) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "自定义: ${dailyGoal} 分钟",
                        fontSize = 14.sp,
                        modifier = Modifier.weight(1f)
                    )
                }
                Slider(
                    value = dailyGoal.toFloat(),
                    onValueChange = { onGoalChange(it.toInt()) },
                    valueRange = 5f..300f,
                    steps = 58,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun ReminderCard(
    enabled: Boolean,
    interval: Float,
    onEnabledChange: (Boolean) -> Unit,
    onIntervalChange: (Float) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "阅读提醒",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "定时提醒你阅读，达成目标后停止",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = onEnabledChange
                )
            }

            AnimatedVisibility(
                visible = enabled,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "提醒间隔: ${interval.toInt()} 分钟",
                        fontSize = 14.sp
                    )
                    Slider(
                        value = interval,
                        onValueChange = onIntervalChange,
                        valueRange = 10f..180f,
                        steps = 33,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

@Composable
private fun SummaryCard(context: Context) {
    val summary = ReadingGoalManager.getSummary(context)

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "阅读概况",
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = summary,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
