# ArcReel Android 设计文档

> 日期：2026-07-02
> 基于：Legado (阅读Sigma) + ArcReel 全流程深度集成

---

## 一、项目概述

以 Legado（阅读Sigma）为 Android 主体框架，新增 `arcreel` 模块，将 ArcReel 的 AI 视频生成全流程以 Jetpack Compose 原生实现，零外部服务依赖，所有 AI 能力通过 Android 端直接调用供应商 API。

**核心目标**：用户上传小说 → 一键自动生成短视频 → 导出，全流程在手机上完成。

---

## 二、技术栈

| 层 | 技术 |
|---|---|
| UI | Jetpack Compose + Material 3 + Navigation Compose |
| 架构 | MVVM + Clean Architecture（data/domain/ui 三层） |
| 数据库 | Room (KSP) |
| 网络 | OkHttp + Retrofit + Kotlinx Serialization |
| 异步 | Kotlin Coroutines + Flow |
| 编排引擎 | 自定义 Orchestrator（协程状态机） |
| 视频合成 | mobile-ffmpeg |
| 图片加载 | Glide（复用现有） |
| DI | Hilt |
| 最低 SDK | 21 (Android 5.0) |
| 编译 SDK | 36 |
| 语言 | Kotlin 2.3 |

---

## 三、模块结构

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
│           │   │   ├── ArcreelDatabase.kt       # Room 数据库
│           │   │   ├── dao/
│           │   │   │   ├── ProjectDao.kt
│           │   │   │   ├── ScriptDao.kt
│           │   │   │   ├── CharacterDao.kt
│           │   │   │   ├── PropDao.kt
│           │   │   │   ├── AssetDao.kt
│           │   │   │   ├── TaskDao.kt
│           │   │   │   └── ReferenceVideoDao.kt
│           │   │   └── entity/
│           │   │       ├── ProjectEntity.kt
│           │   │       ├── ScriptEntity.kt       # 剧本 + 分镜
│           │   │       ├── CharacterEntity.kt
│           │   │       ├── PropEntity.kt
│           │   │       ├── AssetEntity.kt
│           │   │       ├── TaskEntity.kt
│           │   │       └── ReferenceVideoEntity.kt
│           │   ├── remote/
│           │   │   ├── provider/                 # 各 AI 供应商 API
│           │   │   │   ├── ProviderApi.kt        # 统一接口
│           │   │   │   ├── GeminiApi.kt
│           │   │   │   ├── ArkApi.kt             # 火山方舟
│           │   │   │   ├── GrokApi.kt
│           │   │   │   ├── OpenAIApi.kt
│           │   │   │   ├── ViduApi.kt
│           │   │   │   ├── DashScopeApi.kt       # 阿里百炼
│           │   │   │   ├── MiniMaxApi.kt
│           │   │   │   ├── KlingApi.kt           # 可灵
│           │   │   │   ├── AgnesApi.kt           # 自定义
│           │   │   │   └── NewApiApi.kt          # 自定义
│           │   │   └── model/
│           │   │       ├── ApiModels.kt          # 请求/响应 DTO
│           │   │       └── ProviderConfig.kt
│           │   └── repository/
│           │       ├── ProjectRepository.kt
│           │       ├── ScriptRepository.kt
│           │       ├── GenerationRepository.kt
│           │       ├── ProviderRepository.kt
│           │       └── ConfigRepository.kt
│           ├── domain/
│           │   ├── model/
│           │   │   ├── Project.kt
│           │   │   ├── Script.kt                # 剧本核心模型
│           │   │   ├── Storyboard.kt            # 分镜模型
│           │   │   ├── Character.kt
│           │   │   ├── Prop.kt
│           │   │   ├── GenerationTask.kt
│           │   │   └── ReferenceVideo.kt
│           │   ├── orchestrator/
│           │   │   ├── Orchestrator.kt          # 核心编排引擎
│           │   │   ├── PipelineStep.kt          # 步骤接口
│           │   │   └── steps/
│           │   │       ├── ParseSourceStep.kt    # 1. 解析源文件
│           │   │       ├── ExtractCharactersStep.kt  # 2. 全局角色提取
│           │   │       ├── GenerateScriptStep.kt     # 3. 剧本生成
│           │   │       ├── ReviewScriptStep.kt       # 4. 剧本审阅
│           │   │       ├── PlanStoryboardStep.kt     # 5. 分镜规划
│           │   │       ├── GenerateImagesStep.kt     # 6. 图片生成
│           │   │       ├── GenerateVideosStep.kt     # 7. 视频生成
│           │   │       ├── GenerateTtsStep.kt        # 8. TTS 配音
│           │   │       └── ComposeVideoStep.kt       # 9. 视频合成
│           │   ├── generator/
│           │   │   ├── ImageGenerator.kt
│           │   │   ├── VideoGenerator.kt
│           │   │   ├── TtsGenerator.kt
│           │   │   └── TextGenerator.kt
│           │   ├── parser/
│           │   │   ├── SourceParser.kt          # 统一接口
│           │   │   ├── TxtParser.kt
│           │   │   └── EpubParser.kt
│           │   ├── queue/
│           │   │   ├── TaskQueue.kt             # 协程任务队列
│           │   │   ├── RateLimiter.kt            # RPM 速率限制
│           │   │   └── ResumeManager.kt          # 断点续传
│           │   └── prompt/
│           │       ├── PromptTemplates.kt        # 提示词模板（从 ArcReel 迁移）
│           │       └── PromptBuilders.kt          # 提示词构建器
│           ├── ui/
│           │   ├── navigation/
│           │   │   └── ArcreelNavGraph.kt       # 导航图
│           │   ├── project/
│           │   │   ├── ProjectListScreen.kt
│           │   │   ├── CreateProjectScreen.kt
│           │   │   └── ProjectSettingsScreen.kt
│           │   ├── script/
│           │   │   ├── ScriptScreen.kt          # 剧本查看
│           │   │   ├── ScriptEditScreen.kt      # 剧本编辑
│           │   │   └── ScriptReviewScreen.kt   # 剧本审阅
│           │   ├── storyboard/
│           │   │   ├── StoryboardScreen.kt      # 分镜列表
│           │   │   └── StoryboardDetailScreen.kt
│           │   ├── character/
│           │   │   ├── CharacterListScreen.kt
│           │   │   └── CharacterDetailScreen.kt
│           │   ├── asset/
│           │   │   └── AssetLibraryScreen.kt
│           │   ├── generation/
│           │   │   └── GenerationScreen.kt      # 生成进度 + 任务管理
│           │   ├── assistant/
│           │   │   └── AssistantScreen.kt       # AI 助手
│           │   ├── reference/
│           │   │   └── ReferenceVideoScreen.kt
│           │   ├── settings/
│           │   │   ├── ProviderConfigScreen.kt
│           │   │   └── GlobalSettingsScreen.kt
│           │   ├── export/
│           │   │   └── ExportScreen.kt
│           │   └── component/                   # 共享组件
│           │       ├── StoryboardCard.kt
│           │       ├── ProgressStepper.kt
│           │       ├── VideoPlayer.kt
│           │       ├── FilterChip.kt
│           │       └── ...
│           ├── di/
│           │   ├── DatabaseModule.kt
│           │   ├── NetworkModule.kt
│           │   ├── RepositoryModule.kt
│           │   └── OrchestratorModule.kt
│           └── ArcreelEntry.kt                  # 入口 Composable
```

---

## 四、核心数据模型

### 4.1 项目（Project）

```kotlin
@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey val name: String,           // 唯一标识
    val title: String,
    val sourceKind: String,                 // "novel" | "screenplay"
    val contentMode: String,                // "narration" | "drama" | "ad"
    val aspectRatio: String,                // "9:16" | "16:9" | "1:1"
    val styleTemplateId: String?,
    val videoBackend: String?,              // 默认视频供应商
    val imageBackend: String?,              // 默认图片供应商
    val textBackend: String?,               // 默认文本供应商
    val state: String,                      // "draft" | "scripting" | "generating" | "complete"
    val createdAt: Long,
    val updatedAt: Long
)
```

### 4.2 剧本（Script）

```kotlin
@Entity(tableName = "scripts")
data class ScriptEntity(
    @PrimaryKey val id: String,             // "episode_01"
    val projectName: String,                // FK → projects
    val title: String,
    val segments: String,                   // JSON: List<Segment>
    val characters: String,                 // JSON: List<Character>
    val props: String,                      // JSON: List<Prop>
    val generationMode: String?,            // "v1" | "v2" | "ad"
    val durationSeconds: Int?,
    val createdAt: Long,
    val updatedAt: Long
)
```

### 4.3 分镜/镜头（Segment → Shot）

剧本分段结构（JSON 嵌入）：

```kotlin
data class Segment(
    val id: String,
    val sceneDescription: String,
    val shots: List<Shot>,
    val narration: NarrationSegment?,
    val dialogues: List<Dialogue>
)

data class Shot(
    val id: String,
    val shotType: ShotType,          // Close-up, Medium Shot, etc.
    val cameraMotion: CameraMotion,
    val transition: TransitionType,
    val imagePrompt: ImagePrompt,
    val videoPrompt: VideoPrompt?,
    val imageAssetId: String?,       // 生成后回填
    val videoAssetId: String?,
    val ttsAssetId: String?
)
```

### 4.4 生成任务（Task）

```kotlin
@Entity(tableName = "generation_tasks")
data class TaskEntity(
    @PrimaryKey val id: String,
    val projectName: String,
    val scriptFile: String,
    val segmentId: String?,
    val shotId: String?,
    val type: String,                      // "image" | "video" | "tts" | "text"
    val provider: String,
    val model: String?,
    val status: String,                    // "pending" | "running" | "done" | "failed" | "cancelled"
    val inputPrompt: String?,
    val outputAssetId: String?,
    val errorMessage: String?,
    val retryCount: Int,
    val cost: Double?,
    val createdAt: Long,
    val startedAt: Long?,
    val completedAt: Long?
)
```

---

## 五、编排引擎（Orchestrator）

### 5.1 核心设计

`Orchestrator` 是一个 Kotlin 协程驱动的流水线引擎，执行 9 个步骤，全程通过 `StateFlow` 推送进度：

```kotlin
class Orchestrator(
    private val textGenerator: TextGenerator,
    private val imageGenerator: ImageGenerator,
    private val videoGenerator: VideoGenerator,
    private val ttsGenerator: TtsGenerator,
    private val taskQueue: TaskQueue
) {
    // 进度状态
    data class PipelineProgress(
        val currentStep: PipelineStep,
        val stepIndex: Int,
        val totalSteps: Int,
        val stepProgress: Float,    // 0.0 - 1.0
        val message: String,
        val isComplete: Boolean,
        val error: String?
    )

    private val _progress = MutableStateFlow(PipelineProgress(...))
    val progress: StateFlow<PipelineProgress> = _progress.asStateFlow()

    suspend fun execute(projectName: String, sourceFile: Uri? = null) {
        // 串行执行各步骤，每步内部可并行
        val steps = listOf(
            ParseSourceStep(),
            ExtractCharactersStep(),
            GenerateScriptStep(),
            ReviewScriptStep(),      // 可选的人机交互点
            PlanStoryboardStep(),
            GenerateImagesStep(),    // 内部并行
            GenerateVideosStep(),    // 内部并行
            GenerateTtsStep(),
            ComposeVideoStep()
        )
        // 执行逻辑...
    }
}
```

### 5.2 各步骤职责

| 步骤 | 输入 | 处理 | 输出 |
|---|---|---|---|
| 1. 解析源文件 | TXT/EPUB 文件 | 提取章节文本 | 纯文本章节 |
| 2. 全局角色提取 | 全部章节文本 | Text API → 结构化角色列表 | Character[] |
| 3. 剧本生成 | 章节文本 + 角色 | Text API → 剧本（分镜描述） | Script (Segments) |
| 4. 剧本审阅 | 完整剧本 | 用户审阅/编辑 | 确认后的 Script |
| 5. 分镜规划 | 确认后的 Script | Text API → 细化分镜 | Shot[] + Prompt |
| 6. 图片生成 | Shot[] 的 imagePrompt | Image API 并行调用 | 图片文件 |
| 7. 视频生成 | Shot[] 的 videoPrompt | Video API 并行调用 | 视频文件 |
| 8. TTS 配音 | narration 文本 | TTS API 并行调用 | 音频文件 |
| 9. 视频合成 | 视频 + 音频 | mobile-ffmpeg 合成 | 最终 MP4 |

### 5.3 任务队列

```kotlin
class TaskQueue(
    private val maxConcurrent: Int = 3,
    private val rateLimiter: RateLimiter
) {
    // 多通道并发：Image/Video/Audio 各独立通道
    private val imageChannel = Channel<ImageTask>(Channel.UNLIMITED)
    private val videoChannel = Channel<VideoTask>(Channel.UNLIMITED)
    private val audioChannel = Channel<AudioTask>(Channel.UNLIMITED)

    // 断点续传：检查已完成的 task，跳过重试
    fun resume(projectName: String): Flow<TaskProgress>

    // 取消：取消所有 pending + running 任务
    fun cancelAll()
}
```

---

## 六、AI 供应商集成

### 6.1 统一接口

```kotlin
interface ProviderApi {
    suspend fun generateText(prompt: String, model: String): String
    suspend fun generateImage(prompt: String, config: ImageConfig): ByteArray
    suspend fun generateVideo(prompt: String, config: VideoConfig): ByteArray
    suspend fun generateTts(text: String, config: TtsConfig): ByteArray
    suspend fun validateCredentials(): Boolean
}
```

### 6.2 供应商列表

| 供应商 | 文本 | 图片 | 视频 | TTS |
|---|---|---|---|---|
| Gemini | ✅ | ✅ | ✅ (Veo) | ✅ |
| 火山方舟 (Ark) | ✅ | ✅ | ✅ (Seedance) | ✅ |
| Grok | ✅ | ✅ | ✅ | ✅ |
| OpenAI | ✅ | ✅ | ✅ (Sora) | ✅ |
| 阿里百炼 (DashScope) | ✅ | ✅ | ✅ | ✅ |
| MiniMax | - | ✅ | ✅ | - |
| 可灵 (Kling) | - | ✅ | ✅ | - |
| Vidu | - | ✅ | ✅ | - |
| 自定义 (Agnes/NewAPI) | ✅ | ✅ | ✅ | ✅ |

### 6.3 配置存储

用 SharedPreferences 存储每个供应商的 API Key 和配置：

```kotlin
data class ProviderConfig(
    val provider: String,
    val apiKey: String,
    val apiSecret: String?,
    val baseUrl: String?,
    val defaultModel: String?,
    val isEnabled: Boolean
)
```

---

## 七、UI 导航结构

### 7.1 底部导航栏新增 Tab

```
┌──────────────────────────────────────────────┐
│  [书架]  [发现]  [订阅]  [我的]  [AI视频]  │
└──────────────────────────────────────────────┘
```

点击 `AI视频` Tab 进入 Arcreel 独立导航图：

```
AI视频 Tab
├── 项目列表 (ProjectListScreen)          ← 入口
├── 创建项目 (CreateProjectScreen)
│   ├── 选择源文件（从书架/本地）
│   ├── 选择风格模板
│   ├── 设置画幅比 / 供应商
│   └── 点击"开始生成" → 进入生成流程
├── 项目详情 (MainProjectScreen)
│   ├── Tab: 剧本 → ScriptScreen → ScriptEditScreen
│   ├── Tab: 分镜 → StoryboardScreen → StoryboardDetailScreen
│   ├── Tab: 角色 → CharacterListScreen → CharacterDetailScreen
│   ├── Tab: 素材 → AssetLibraryScreen
│   ├── Tab: 参考 → ReferenceVideoScreen
│   └── Tab: 生成 → GenerationScreen (进度)
├── 项目设置 (ProjectSettingsScreen)
├── AI 助手 (AssistantScreen)               ← 全局浮窗
├── 全局设置 (GlobalSettingsScreen)
│   ├── 供应商配置 (ProviderConfigScreen)
│   └── 全局偏好
└── 导出 (ExportScreen)
```

### 7.2 关键交互

- **一键生成**：项目创建后点击"开始生成"，进入 `GenerationScreen`，展示逐步进度，全程无需手动。
- **剧本审阅**：第 4 步暂停，用户可查看/编辑剧本，确认后继续。
- **生成进度**：实时显示当前步骤、已完成/总数、预计剩余时间。
- **断点续传**：退出后重进可恢复。

---

## 八、与 Legado 的集成点

### 8.1 底部导航

在 `MainActivity` 中新增第 5 个 Tab（`idArcreel = 4`），使用 `ArcreelEntry` Composable 作为内容。

### 8.2 书架集成

- 从书架选择小说 → 直接导入到 Arcreel 项目
- 通过 Legado 的 `Book` 实体获取封面、书名、章节内容

### 8.3 依赖注入

Arcreel 模块使用 Hilt，Legado 原有代码不做改动。Arcreel 的 Hilt 组件通过 `@Module` 声明，在 `ArcreelEntry` Composable 中提供。

### 8.4 包体积

新增依赖预估：
- Jetpack Compose + Material 3: ~4 MB
- Retrofit + OkHttp: ~1 MB
- mobile-ffmpeg: ~15 MB
- Room 已在现有依赖中
- **总计新增约 20 MB**

---

## 九、实现优先级

### Phase 1：基础框架（v0.1）
- arcreel 模块搭建 + Gradle 配置
- Room 数据库 + DAO
- 底部导航集成
- 项目列表 + 创建项目 UI

### Phase 2：核心流水线（v0.2）
- 编排引擎 + 9 步骤
- 文件解析（TXT/EPUB）
- 文本生成接口（1 个供应商）
- 剧本生成 + 审阅 UI

### Phase 3：多媒体生成（v0.3）
- 图片生成接口（3 个供应商）
- 视频生成接口（3 个供应商）
- TTS 生成接口
- 分镜预览 + 生成进度 UI

### Phase 4：扩展功能（v0.4）
- 全供应商支持
- 角色/道具/线索管理
- 参考视频
- 素材库
- AI 助手

### Phase 5：完善（v1.0）
- 视频合成 + 导出
- 断点续传 + 速率限制
- 全局配置
- 测试 + 优化