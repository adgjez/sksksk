# ArcReel Android 设计文档 v2（修复版）

> 日期：2026-07-02
> 基于：Legado (阅读Sigma) + ArcReel 全流程深度集成
> 版本：v2.0（修复版）

---

## 一、项目概述

以 Legado（阅读Sigma）为 Android 主体框架，新增 `arcreel` 模块，将 ArcReel 的 AI 视频生成全流程以 Jetpack Compose 原生实现，零外部服务依赖，所有 AI 能力通过 Android 端直接调用供应商 API。

**核心目标**：用户上传小说 → 一键自动生成短视频 → 导出，全流程在手机上完成。

---

## 二、技术栈

| 层 | 技术 |
|---|---|
| UI | Jetpack Compose + Material 3 + Navigation Compose |
| 架构 | MVVM + Clean Architecture（data/domain/usecase/ui 四层） |
| 后台任务 | WorkManager + Foreground Service |
| 数据库 | Room (KSP) + 外键约束 + 索引 |
| 网络 | OkHttp + Retrofit + Kotlinx Serialization |
| 异步 | Kotlin Coroutines + Flow |
| 编排引擎 | PipelineExecutor（WorkManager 内执行） |
| 视频合成 | ffmpeg-kit-min（~5 MB） |
| 图片加载 | Glide（复用现有） |
| 安全存储 | EncryptedSharedPreferences（Jetpack Security） |
| DI | Hilt |
| 最低 SDK | 23 (Android 6.0) |
| 编译 SDK | 36 |
| 语言 | Kotlin |

---

## 三、架构分层（修复后）

```
domain/                          # 纯 Kotlin，不依赖 Android
├── model/                       # 领域模型
├── usecase/
│   └── PipelineUseCase.kt       # 仅接口定义
└── orchestrator/
    ├── PipelineStep.kt          # 步骤接口
    ├── PipelineContext.kt       # 上下文
    └── PipelineProgress.kt      # 进度模型

data/                            # 实现层
├── local/                       # Room 数据库
├── remote/                      # 供应商 API（submit/poll/download）
├── repository/                  # 数据仓库
├── executor/
│   ├── PipelineExecutor.kt      # 具体编排实现
│   ├── PipelineWorker.kt        # WorkManager CoroutineWorker
│   └── steps/                   # 各步骤实现
└── security/
    └── ApiKeyStore.kt           # EncryptedSharedPreferences

ui/                              # Compose UI
├── generation/
│   └── GenerationScreen.kt     # 订阅 WorkInfo 进度
└── ...
```

### 关键变化

1. **domain 层无 Android 依赖**：`PipelineUseCase` 仅定义接口，具体实现在 `data/executor/`
2. **Orchestrator → PipelineExecutor**：从 `domain/orchestrator/` 移到 `data/executor/`
3. **WorkManager 承载**：所有生成任务通过 `PipelineWorker` 在后台执行，`Foreground Service` 保活

---

## 四、模块结构

```
legado/
├── app/                            # 原有（不改动）
├── modules/
│   ├── book/                       # 原有
│   ├── rhino/                      # 原有
│   └── arcreel/                    # 新增模块
│       ├── build.gradle.kts
│       └── src/main/java/io/legado/arcreel/
│           ├── data/
│           │   ├── local/
│           │   │   ├── ArcreelDatabase.kt
│           │   │   ├── dao/
│           │   │   │   ├── ProjectDao.kt
│           │   │   │   ├── ScriptDao.kt
│           │   │   │   ├── ShotDao.kt              # 新增：独立 Shot 表
│           │   │   │   ├── SegmentDao.kt           # 新增：独立 Segment 表
│           │   │   │   ├── CharacterDao.kt
│           │   │   │   ├── PropDao.kt
│           │   │   │   ├── AssetDao.kt
│           │   │   │   ├── TaskDao.kt
│           │   │   │   └── ReferenceVideoDao.kt
│           │   │   └── entity/
│           │   │       ├── ProjectEntity.kt        # 自增 ID 主键
│           │   │       ├── ScriptEntity.kt
│           │   │       ├── SegmentEntity.kt        # 新增：独立段表
│           │   │       ├── ShotEntity.kt           # 新增：独立镜头表
│           │   │       ├── CharacterEntity.kt
│           │   │       ├── PropEntity.kt
│           │   │       ├── AssetEntity.kt
│           │   │       ├── TaskEntity.kt
│           │   │       └── ReferenceVideoEntity.kt
│           │   ├── remote/
│           │   │   ├── provider/
│           │   │   │   ├── ProviderApi.kt          # 拆分：submit/poll/download
│           │   │   │   ├── KlingApi.kt             # 可灵（异步轮询）
│           │   │   │   ├── ViduApi.kt              # Vidu（异步轮询）
│           │   │   │   ├── GeminiApi.kt
│           │   │   │   ├── ArkApi.kt
│           │   │   │   ├── GrokApi.kt
│           │   │   │   ├── OpenAIApi.kt
│           │   │   │   ├── DashScopeApi.kt
│           │   │   │   ├── MiniMaxApi.kt
│           │   │   │   └── CustomApi.kt
│           │   │   └── model/
│           │   │       ├── ApiModels.kt
│           │   │       └── ProviderConfig.kt
│           │   ├── repository/
│           │   │   ├── ProjectRepository.kt
│           │   │   ├── ScriptRepository.kt
│           │   │   ├── ShotRepository.kt           # 新增
│           │   │   ├── GenerationRepository.kt
│           │   │   ├── ProviderRepository.kt
│           │   │   └── ConfigRepository.kt
│           │   ├── executor/
│           │   │   ├── PipelineExecutor.kt         # 编排实现
│           │   │   ├── PipelineWorker.kt           # WorkManager
│           │   │   ├── ForegroundService.kt        # 前台服务保活
│           │   │   └── steps/
│           │   │       ├── ParseSourceStep.kt
│           │   │       ├── ExtractCharactersStep.kt
│           │   │       ├── GenerateScriptStep.kt
│           │   │       ├── ReviewScriptStep.kt
│           │   │       ├── PlanStoryboardStep.kt
│           │   │       ├── GenerateImagesStep.kt
│           │   │       ├── GenerateVideosStep.kt
│           │   │       ├── GenerateTtsStep.kt
│           │   │       └── ComposeVideoStep.kt
│           │   └── security/
│           │       └── ApiKeyStore.kt              # 加密存储
│           ├── domain/
│           │   ├── model/
│           │   │   ├── Project.kt
│           │   │   ├── Script.kt
│           │   │   ├── Shot.kt
│           │   │   ├── Character.kt
│           │   │   ├── Prop.kt
│           │   │   ├── GenerationTask.kt
│           │   │   └── ReferenceVideo.kt
│           │   ├── usecase/
│           │   │   └── PipelineUseCase.kt          # 接口定义
│           │   └── orchestrator/
│           │       ├── PipelineStep.kt
│           │       ├── PipelineContext.kt
│           │       └── PipelineProgress.kt
│           ├── ui/
│           │   ├── ArcreelEntry.kt
│           │   ├── ArcreelFragment.kt
│           │   ├── navigation/
│           │   │   └── ArcreelNavGraph.kt
│           │   ├── project/
│           │   │   ├── ProjectListScreen.kt
│           │   │   ├── CreateProjectScreen.kt
│           │   │   ├── ProjectSettingsScreen.kt
│           │   │   └── CostEstimateDialog.kt       # 新增：成本预估
│           │   ├── script/
│           │   │   ├── ScriptScreen.kt
│           │   │   ├── ScriptEditScreen.kt
│           │   │   └── ScriptReviewScreen.kt
│           │   ├── storyboard/
│           │   │   ├── StoryboardScreen.kt
│           │   │   └── StoryboardDetailScreen.kt
│           │   ├── character/
│           │   │   ├── CharacterListScreen.kt
│           │   │   └── CharacterDetailScreen.kt
│           │   ├── asset/
│           │   │   └── AssetLibraryScreen.kt
│           │   ├── generation/
│           │   │   ├── GenerationScreen.kt
│           │   │   └── GenerationViewModel.kt
│           │   ├── assistant/
│           │   │   └── AssistantScreen.kt
│           │   ├── reference/
│           │   │   └── ReferenceVideoScreen.kt
│           │   ├── settings/
│           │   │   ├── ProviderConfigScreen.kt
│           │   │   └── GlobalSettingsScreen.kt
│           │   ├── export/
│           │   │   └── ExportScreen.kt
│           │   └── component/
│           │       ├── StoryboardCard.kt
│           │       ├── ProgressStepper.kt
│           │       ├── VideoPlayer.kt
│           │       └── FilterChip.kt
│           └── di/
│               ├── DatabaseModule.kt
│               ├── NetworkModule.kt
│               ├── WorkManagerModule.kt            # 新增
│               ├── SecurityModule.kt               # 新增
│               └── ExecutorModule.kt               # 新增
```

---

## 五、核心数据模型（修复后）

### 5.1 项目（Project）— 自增 ID 主键

```kotlin
@Entity(
    tableName = "arcreel_projects",
    indices = [Index(value = ["name"], unique = true)]
)
data class ProjectEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,                    // 唯一索引，可重命名
    val title: String,
    val sourceKind: String,
    val contentMode: String,
    val aspectRatio: String,
    val styleTemplateId: String?,
    val videoBackend: String?,
    val imageBackend: String?,
    val textBackend: String?,
    val state: String,
    val targetDuration: Int?,
    val brief: String?,
    val sourceFilePath: String?,
    val createdAt: Long,
    val updatedAt: Long
)
```

### 5.2 剧本（Script）— 外键约束

```kotlin
@Entity(
    tableName = "arcreel_scripts",
    foreignKeys = [ForeignKey(
        entity = ProjectEntity::class,
        parentColumns = ["id"],
        childColumns = ["projectId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("projectId")]
)
data class ScriptEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val projectId: Long,
    val title: String,
    val generationMode: String?,
    val durationSeconds: Int?,
    val createdAt: Long,
    val updatedAt: Long
)
```

### 5.3 段（Segment）— 独立表

```kotlin
@Entity(
    tableName = "arcreel_segments",
    foreignKeys = [ForeignKey(
        entity = ScriptEntity::class,
        parentColumns = ["id"],
        childColumns = ["scriptId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("scriptId")]
)
data class SegmentEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val scriptId: Long,
    val segmentOrder: Int,
    val sceneDescription: String,
    val narrationText: String?,
    val narrationVoice: String?,
    val dialogues: String               // JSON 数组（对话量小，可接受）
)
```

### 5.4 镜头（Shot）— 独立表，可独立更新状态

```kotlin
@Entity(
    tableName = "arcreel_shots",
    foreignKeys = [ForeignKey(
        entity = SegmentEntity::class,
        parentColumns = ["id"],
        childColumns = ["segmentId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("segmentId"), Index("projectId")]
)
data class ShotEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val projectId: Long,
    val segmentId: Long,
    val shotOrder: Int,
    val shotType: String,
    val cameraMotion: String,
    val transition: String,
    val imagePromptPositive: String,
    val imagePromptNegative: String,
    val videoPromptPositive: String?,
    val videoDurationSeconds: Int?,
    // 生成状态：nullable → pending → assetId → 完成
    val imageStatus: String = "pending",
    val imageAssetId: String? = null,
    val videoStatus: String = "pending",
    val videoAssetId: String? = null,
    val ttsStatus: String = "pending",
    val ttsAssetId: String? = null
)
```

### 5.5 生成任务（Task）— 新增 remoteTaskId

```kotlin
@Entity(
    tableName = "arcreel_tasks",
    foreignKeys = [ForeignKey(
        entity = ProjectEntity::class,
        parentColumns = ["id"],
        childColumns = ["projectId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("projectId"), Index("shotId")]
)
data class TaskEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val projectId: Long,
    val shotId: Long? = null,
    val type: String,                   // "image" | "video" | "tts" | "text"
    val provider: String?,
    val model: String?,
    val remoteTaskId: String?,          // 供应商返回的异步任务 ID（用于轮询）
    val status: String = "pending",
    val inputPrompt: String?,
    val outputAssetId: String?,
    val errorMessage: String?,
    val retryCount: Int = 0,
    val cost: Double?,
    val createdAt: Long,
    val startedAt: Long?,
    val completedAt: Long?
)
```

---

## 六、Provider 异步接口（修复后）

### 6.1 统一接口（拆分 submit/poll/download）

```kotlin
interface ProviderApi {
    val providerName: String

    // 文本生成（同步，通常 < 30s）
    suspend fun generateText(prompt: String, model: String?): Result<String>

    // 图片生成（异步）
    suspend fun submitImageTask(prompt: String, negativePrompt: String?, config: ImageConfig): Result<String>
    suspend fun pollImageTask(taskId: String): Result<PollResult>
    suspend fun downloadImage(taskId: String): Result<java.io.File>

    // 视频生成（异步，耗时 30s~5min）
    suspend fun submitVideoTask(prompt: String, negativePrompt: String?, config: VideoConfig): Result<String>
    suspend fun pollVideoTask(taskId: String): Result<PollResult>
    suspend fun downloadVideo(taskId: String): Result<java.io.File>

    // TTS（异步或同步）
    suspend fun generateTts(text: String, config: TtsConfig): Result<java.io.File>

    suspend fun validateCredentials(): Boolean
}

sealed class PollResult {
    data class Processing(val progress: Int, val estimatedSeconds: Int? = null) : PollResult()
    data class Completed(val fileUrl: String, val fileSize: Long? = null) : PollResult()
    data class Failed(val error: String) : PollResult()
}
```

### 6.2 供应商列表

| 供应商 | 文本 | 图片 | 视频 | TTS | 图片模式 | 视频模式 |
|---|---|---|---|---|---|---|
| Gemini | ✅ | ✅ | ✅ (Veo) | ✅ | 同步 | 异步 |
| 火山方舟 (Ark) | ✅ | ✅ | ✅ (Seedance) | ✅ | 异步 | 异步 |
| Grok | ✅ | ✅ | ✅ | ✅ | 同步 | 异步 |
| OpenAI | ✅ | ✅ | ✅ (Sora) | ✅ | 同步 | 异步 |
| 阿里百炼 (DashScope) | ✅ | ✅ | ✅ | ✅ | 异步 | 异步 |
| MiniMax | - | ✅ | ✅ | - | 异步 | 异步 |
| 可灵 (Kling) | - | ✅ | ✅ | - | 异步 | 异步 |
| Vidu | - | ✅ | ✅ | - | 异步 | 异步 |
| 自定义 (Agnes/NewAPI) | ✅ | ✅ | ✅ | ✅ | 配置决定 | 配置决定 |

### 6.3 轮询策略

```kotlin
object PollingStrategy {
    // 初始间隔 1s，最大 10s，指数退避
    fun intervals(): Sequence<Long> = sequence {
        var delay = 1000L
        while (true) {
            yield(delay)
            delay = (delay * 1.5).toLong().coerceAtMost(10_000)
        }
    }
}
```

### 6.4 配置存储（加密）

```kotlin
class ApiKeyStore(context: Context) {
    private val prefs = EncryptedSharedPreferences.create(
        "arcreel_api_keys",
        MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
        context,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun saveApiKey(provider: String, key: String, secret: String? = null)
    fun getApiKey(provider: String): String?
    fun getApiSecret(provider: String): String?
    fun deleteApiKey(provider: String)
}
```

---

## 七、后台任务架构（修复后）

### 7.1 PipelineWorker（WorkManager）

```kotlin
@HiltWorker
class PipelineWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val executor: PipelineExecutor,
    private val projectRepo: ProjectRepository
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // 1. 启动前台服务
        setForeground(createForegroundInfo())

        // 2. 恢复项目上下文
        val projectId = inputData.getLong("projectId", 0)
        val project = projectRepo.getById(projectId) ?: return Result.failure()

        // 3. 执行编排
        return executor.execute(project, sourceText = null) { stepIndex, total, message ->
            // 进度回调 → WorkManager Progress
            setProgress(workDataOf(
                "step" to stepIndex,
                "total" to total,
                "message" to message
            ))
            // 更新前台通知
            updateForegroundNotification(message)
        }
    }
}
```

### 7.2 前台服务通知

```kotlin
fun createForegroundNotification(message: String): ForegroundInfo {
    val channelId = "arcreel_generation"
    val notification = NotificationCompat.Builder(context, channelId)
        .setContentTitle("AI 视频生成中")
        .setContentText(message)
        .setOngoing(true)
        .setSmallIcon(R.drawable.ic_arcreel)
        .addAction(R.drawable.ic_cancel, "取消", cancelPendingIntent)
        .build()

    return ForegroundInfo(NOTIFICATION_ID, notification)
}
```

### 7.3 进度同步（UI 层 → WorkInfo）

```kotlin
@HiltViewModel
class GenerationViewModel @Inject constructor(
    private val workManager: WorkManager
) : ViewModel() {

    fun observeProgress(workId: UUID): Flow<PipelineProgress> {
        return workManager.getWorkInfoByIdLiveData(workId).asFlow()
            .mapNotNull { info ->
                val progress = info.progress.getInt("step", 0)
                val total = info.progress.getInt("total", 0)
                val message = info.progress.getString("message") ?: ""
                PipelineProgress(
                    stepIndex = progress,
                    totalSteps = total,
                    message = message,
                    isComplete = info.state == WorkInfo.State.SUCCEEDED,
                    isFailed = info.state == WorkInfo.State.FAILED
                )
            }
    }
}
```

---

## 八、PipelineUseCase 接口（domain 层）

```kotlin
// domain/domain/usecase/PipelineUseCase.kt
// 纯业务接口，不依赖 Android

interface PipelineUseCase {
    suspend fun execute(
        project: Project,
        sourceText: String? = null,
        onProgress: suspend (stepIndex: Int, totalSteps: Int, message: String) -> Unit
    ): PipelineResult
}

sealed class PipelineResult {
    data class Success(val project: Project) : PipelineResult()
    data class Failure(val step: String, val error: String) : PipelineResult()
}
```

---

## 九、错误处理与重试策略

### 9.1 步骤级错误处理

| 场景 | 策略 |
|---|---|
| 网络超时 | 自动重试 3 次，指数退避 (1s → 3s → 9s) |
| API 限流 (429) | 读取 Retry-After 头，等待后重试 |
| 供应商 5xx 错误 | 重试 2 次，仍失败则跳过该 Shot |
| 图片生成失败 | 跳过该 Shot，继续下一个，最终统计失败数 |
| 视频生成失败 | 跳过该 Shot，继续下一个 |
| 单个 Shot 失败超过阈值 | 若 > 30% Shot 失败，中止整个流水线 |
| WorkManager 被系统杀死 | 自动重试（WorkManager 内置） |

### 9.2 断点续传

- 每个 Shot 的 `imageStatus` / `videoStatus` / `ttsStatus` 独立持久化
- 重启 Worker 时，加载 Shot 表，跳过 status="done" 的 Shot
- `RateLimiter` 使用 `Semaphore` 控制并发数，适配不同供应商的 TPM 限制

---

## 十、存储与内存管理

| 方面 | 方案 |
|---|---|
| 临时文件 | `getExternalFilesDir("arcreel/temp")`，合成后自动清理 |
| 最终输出 | `getExternalFilesDir("arcreel/output")`，用户可导出到相册 |
| 缓存清理 | 每次启动时清理超过 7 天的临时文件 |
| 存储空间不足 | `StorageManager` 预检查，不足时提示用户 |
| 大文件加载 | 不使用 `ByteArray`，始终使用 `File` 路径传递给 Glide/MediaPlayer |

---

## 十一、权限清单

```xml
<!-- 网络 -->
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />

<!-- 前台服务（Android 14+ 需要指定类型） -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />

<!-- 通知 -->
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

<!-- 存储（Android 10+ 使用 Scoped Storage，无需此权限） -->
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE"
    android:maxSdkVersion="28" />
```

---

## 十二、网络安全配置

```xml
<!-- res/xml/network_security_config.xml -->
<network-security-config>
    <base-config cleartextTrafficPermitted="false">
        <trust-anchors>
            <certificates src="system" />
        </trust-anchors>
    </base-config>
    <!-- 各供应商域名 -->
    <domain-config cleartextTrafficPermitted="false">
        <domain includeSubdomains="true">googleapis.com</domain>
        <domain includeSubdomains="true">openai.com</domain>
        <domain includeSubdomains="true">volces.com</domain>
        <domain includeSubdomains="true">aliyuncs.com</domain>
        <domain includeSubdomains="true">klingai.com</domain>
        <domain includeSubdomains="true">vidu.com</domain>
        <domain includeSubdomains="true">minimax.chat</domain>
        <trust-anchors>
            <certificates src="system" />
        </trust-anchors>
    </domain-config>
</network-security-config>
```

---

## 十三、成本预估弹窗

在 Step 6（图片生成）执行前，必须弹出成本预估：

```
┌──────────────────────────────────┐
│  成本预估                        │
│                                  │
│  图片生成: 24 个 Shot × ¥0.05    │
│  = ¥1.20                         │
│  视频生成: 24 个 Shot × ¥0.30    │
│  = ¥7.20                         │
│  TTS 配音: 12 段 × ¥0.02         │
│  = ¥0.24                         │
│  ─────────────────────           │
│  预估总计: ¥8.64                 │
│                                  │
│  [取消]  [确认生成]              │
└──────────────────────────────────┘
```

计算逻辑：`总 Shot 数 × 供应商单价`，单价从配置读取。

---

## 十四、包体积

| 依赖 | 体积 |
|---|---|
| Jetpack Compose + Material 3 | ~4 MB |
| Retrofit + OkHttp + Gson | ~1 MB |
| ffmpeg-kit-min | ~5 MB |
| WorkManager + Hilt | ~1.5 MB |
| Room 已在现有中 | — |
| **总计新增** | **~11.5 MB** |

---

## 十五、实现优先级（重排后）

### Phase 0：技术预研
- 验证 Kling/Vidu 异步提交 + 轮询 API
- 验证 ffmpeg-kit-min 合成 3 秒视频
- 验证 WorkManager + Foreground Service 后台存活
- 验证 EncryptedSharedPreferences 存储

### Phase 1：数据层 + 后台管道
- 数据库（Shot 独立表 + 自增主键 + ForeignKey）
- WorkManager 管道 + PipelineWorker
- EncryptedSharedPreferences API Key 存储
- 网络层 + 1 个供应商完整调通

### Phase 2：编排引擎
- PipelineExecutor + 9 步骤
- 异步轮询框架
- 断点续传 + RateLimiter (Semaphore)
- 文件解析（TXT/EPUB）

### Phase 3：UI 层
- Compose 界面（项目列表/创建/详情）
- 成本预估弹窗
- 生成进度（WorkInfo 订阅）
- 剧本编辑/审阅

### Phase 4：扩展
- 全供应商支持
- 角色/道具/参考视频
- AI 助手
- 视频合成 + 导出
- 测试 + ProGuard 规则