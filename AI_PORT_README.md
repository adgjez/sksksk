# Legado_Max + AI 移植进度

基于 `Suml-1/Legado_Max` 移植 `Rimchars/legado` 的 `feature/discovery-suite-select` 分支中的 AI 功能。

## 已完成

### 数据层（阶段 1+1.5）
- ✅ **11 个 AI Entity**（`app/src/main/java/io/legado/app/data/entities/`）
  - `AiAgentJob.kt`, `AiAgentSession.kt`, `AiAgentTrace.kt`
  - `AiGeneratedImage.kt`, `AiImageGroup.kt`
  - `AiMemoryItem.kt`, `AiMemoryFragment.kt`, `AiMemoryFts.kt`
  - `AiReadAloudRoleCache.kt`, `AiReadAloudUsageRecord.kt`
  - `BookAiChapterSummary.kt`
- ✅ **7 个 AI DAO**（`app/src/main/java/io/legado/app/data/dao/`）
  - `AiAgentDao.kt`, `AiGeneratedImageDao.kt`, `AiImageGroupDao.kt`
  - `AiMemoryDao.kt`, `AiReadAloudRoleCacheDao.kt`, `AiReadAloudUsageRecordDao.kt`, `BookAiChapterSummaryDao.kt`
- ✅ **AppDatabase 已升级到 v109**，注册全部新 Entity + DAO
- ✅ **DatabaseMigrations.kt** 新增 `migration_100_109`，合并源仓库 100→109 的所有建表操作
- ✅ **7 个配套 Entity + 3 个 DAO**（被 AI 强依赖）
  - `BookCharacter`, `BookCharacterRelation` + `BookCharacterDao`
  - `ReadAloudBgmGroup`, `ReadAloudBgmTrack`, `ReadAloudBgmAssignmentCache` + `ReadAloudBgmDao`
  - `ReadAloudSpeakerGroup`, `ReadAloudSpeakerGroupItem` + `ReadAloudSpeakerGroupDao`
- ✅ 2 个配套 Helper：`help/character/BookCharacterIdentityMigrator.kt`, `BookCharacterProfileMeta.kt`, `help/book/BookTagHelper.kt`

### 服务层（阶段 2）
- ✅ **34 个 AI 服务**（`app/src/main/java/io/legado/app/help/ai/`）
  - Agent 引擎：`AiAgentRuntime.kt`, `AiAgentPlanner.kt`, `AiAgentValidator.kt`, `AiAgentInterruption.kt`, `AiAgentStateStore.kt`, `AiAgentRuntimeTypes.kt`
  - 工具层：`AiToolRegistry.kt`, `AiToolExecutor.kt`, `AiWorkspaceTool.kt`, `AiLibraryTool.kt`, `AiBookshelfTool.kt`, `AiBookSourceTool.kt`, `AiBookCharacterTool.kt`, `AiSettingsTool.kt`, `AiSkillPromptTool.kt`, `AiReadingNetworkTool.kt`, `AiContextManager.kt`, `AiWorldBookTool.kt`, `AiWorldBookManager.kt`, `AiMemoryStore.kt`
  - 对话/图像：`AiChatService.kt`, `AiImageService.kt`, `AiImageTool.kt`, `AiImageGalleryManager.kt`, `AiImagePromptRewriter.kt`, `AiChapterSummaryService.kt`
  - 朗读角色：`AiReadAloudRoleService.kt`, `AiReadAloudRoleState.kt`, `AiReadAloudUsageRecorder.kt`, `AiReadAloudBgmService.kt`, `AiReadAloudBgmTool.kt`
  - MCP/搜索：`AiMcpClient.kt`, `AiTavilyTool.kt`
  - 后台保活：`AiTaskKeepAlive.kt`

### UI 层（阶段 3+4+5）
- ✅ **9 个配置 Activity/Fragment**（`app/src/main/java/io/legado/app/ui/config/`）
  - `AiConfigFragment.kt`, `AiProviderManageActivity.kt`, `AiProviderEditActivity.kt`, `AiProviderEditScreen.kt`
  - `AiImageProviderManageActivity.kt`, `AiImageProviderEditActivity.kt`, `AiImageProviderEditScreen.kt`, `AiImageProviderManageScreen.kt`
  - `AiWorldBookManageActivity.kt`
- ✅ **15 个聊天/画廊 UI**（`app/src/main/java/io/legado/app/ui/main/ai/`）
  - `AiChatActivity.kt`, `AiChatViewModel.kt`, `AiChatModels.kt`, `AiChatSpeechPlayer.kt`
  - `AiConfigModels.kt`, `AiImageGalleryActivity.kt`, `AiImagePreviewDialog.kt`, `AiMarkdownRender.kt`
  - `compose/AiChatScreen.kt`, `compose/AiChatUiMapper.kt`, `compose/AiComposeTheme.kt`, `compose/AiMarkdownComponents.kt`, `compose/AiProcessChain.kt`, `compose/AiToolPreviewDialog.kt`, `compose/AiWorldBookManageScreen.kt`
- ✅ **1 个朗读用量 Activity**：`ui/book/read/config/AiReadAloudUsageRecordActivity.kt`
- ✅ **1 个保活 Service**：`service/AiTaskKeepAliveService.kt`

### 资源（阶段 6）
- ✅ **20 个 AI 资源文件**（layout + drawable）
  - layout：`activity_ai_chat.xml`, `activity_ai_image_gallery.xml`, `activity_ai_image_provider_edit.xml`, `activity_ai_provider_manage.xml`, `activity_ai_world_book_manage.xml`, `dialog_ai_image_preview.xml`, `item_ai_generated_image.xml`
  - drawable：`bg_ai_*`（4 个）、`bg_read_ai_*`（3 个）、`ic_bottom_ai*`（4 个）

### 集成（阶段 7）
- ✅ `AndroidManifest.xml` 注册 9 个新 Activity + 1 个新 Service
- ✅ `App.kt` 新增 `aiTaskChannel` 通知通道
- ✅ `AppConst.kt` 新增 `channelIdAiTask` 常量
- ✅ `gradle/libs.versions.toml` + `app/build.gradle` 新增 markwon ext-strikethrough 依赖

## 未完成 / 已知缺陷

### 1. 编译验证未执行
沙箱内**没有安装 Android SDK**，所以以下工作留给你完成：
- 在本地（有 SDK 17 + Android SDK 36）执行 `./gradlew assembleAppMaxDebug`
- 修复过程中暴露的所有编译错误（参考下方预期问题）

### 2. 入口未集成
AI 入口（菜单项、底部 Tab、"我的"页面按钮）**没有接到现有 UI**。需要手动：
- 在 `ui/main/MainActivity.kt` / `MainViewModel.kt` 加入 AI 聊天入口
- 在 `res/menu/main_bnv.xml` 加 AI Tab
- 在 `ui/main/my/MyFragment.kt` 加 AI 设置入口

### 3. 字符串/多语言资源
源分支的 strings.xml 有大量 AI 相关条目（`ai_*`, `*_ai_*`），本项目**未抽取**。运行时所有 AI 界面会显示 Android 默认 key 名（`@string/ai_chat` → 显示 "ai_chat"）。需要：
```bash
diff /root/.codebuddy/artifact/legado-work/ai-source/app/src/main/res/values/strings.xml \
     /workspace/legado_max_ai/app/src/main/res/values/strings.xml | grep -E "^>" | grep -i ai
```
按需复制到 `values/strings.xml` 和 `values-zh/strings.xml`。

### 4. 数据库迁移
- 源仓库 100→109 的每个版本号都有自己的迁移逻辑（含"修复 AI 表"等细节）
- 本项目**合并成单一 migration_100_109**，对老用户**不保留原版本号**
- **不破坏数据**：未触及 `book_characters`、`httpTTS` 等已存在的表

### 5. 编译预期错误
最可能出现的：
- `help/ai/*` 引用了 `io.legado.app.help.book.BookHelp.characterBookKey` 之类的扩展函数，源版本里 BookHelp 改动过；需要把 `BookHelp.kt` 里的对应扩展函数也复制过来
- `ui/main/ai/AiChatActivity.kt` 用了 `BuildConfig` 之外的 AI provider ID 常量，可能需要在 `BuildConfig` 加字段
- `data/dao/BookCharacterDao.kt` 等可能用到 `Migration_*_*` 类

## 下一步建议

1. **先编译**：`./gradlew :app:compileAppMaxDebugKotlin`，把错误列出来
2. **优先修基础设施错误**：把 `BookHelp.kt` 等源版本独有的扩展函数补齐
3. **接入 UI 入口**：在 `MainFragmentInterface` / `MainViewModel` 加 AI 入口
4. **抽取 strings.xml**：从源仓库复制 AI 字符串
5. **打包 debug APK** 测试 AI 聊天、图片生成等核心功能

## 关键文件清单

| 文件 | 状态 |
| --- | --- |
| `app/src/main/java/io/legado/app/data/AppDatabase.kt` | 已更新（v109，11 个 AI Entity + 7 个 DAO） |
| `app/src/main/java/io/legado/app/data/DatabaseMigrations.kt` | 已更新（`migration_100_109`） |
| `app/src/main/AndroidManifest.xml` | 已更新（9 Activity + 1 Service） |
| `app/src/main/java/io/legado/app/App.kt` | 已更新（AI 通知通道） |
| `app/src/main/java/io/legado/app/constant/AppConst.kt` | 已更新（`channelIdAiTask`） |
| `app/src/main/java/io/legado/app/help/ai/*.kt` | 34 个文件已复制 |
| `app/src/main/java/io/legado/app/ui/main/ai/*.kt` | 15 个文件已复制 |
| `app/src/main/java/io/legado/app/ui/config/Ai*.kt` | 9 个文件已复制 |
| `app/src/main/java/io/legado/app/service/AiTaskKeepAliveService.kt` | 已复制 |
| `app/src/main/res/layout/activity_ai_*.xml` | 已复制（7 个 layout） |
| `app/src/main/res/drawable/{bg,ic}_*ai*.xml` | 已复制（13 个 drawable） |
| `gradle/libs.versions.toml` | 已加 markwon-ext-strikethrough |
| `app/build.gradle` | 已加 markwon 依赖 |

## 关键决策记录

1. **包名**：未改。源和 base 都是 `io.legado.app.*`，所以包名零修改。
2. **Application ID**：未改。保留 base 的 `io.legado.app.yuedu`。
3. **资源命名空间**：未改。
4. **数据库版本号**：直接跳到 109，匹配源仓库。
5. **依赖管理**：尽量复用 base 已有依赖，只加 markwon ext-strikethrough。
