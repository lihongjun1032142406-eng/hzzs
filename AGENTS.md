# AGENTS.md

本文档为 AI 代理（Codex / Claude / 其他）提供**当前仓库**的开发导航。  
**以源码与本文件、`CLAUDE.md`、`docs/*` 为准。** 若与任何旧备份、旧 Issue 摘录冲突，以当前分支源码为准。

## 项目概述

| 项 | 内容 |
| --- | --- |
| 名称 | HZZS（火崽崽数据分析） |
| 定位 | 《火崽崽奇妙屋》第三方 **本地** Android 画面分析工具 |
| 包名 | `top.azek431.hzzs` |
| 语言 | Kotlin + Jetpack Compose + Hilt + C++17 (JNI) |
| 最低 SDK | 24（Android 7.0） |
| 目标 / 编译 SDK | 37 |
| 版本 | **0.1.0** / `versionCode = 1`（首发目标，尚未正式 release） |
| 默认赛季 | `AppConfig.DEFAULT_SELECTED_SCENE`（源码唯一真相；文档不写死赛季名） |
| 模块 | **仅** `:app` |
| 仓库 | [Azek431/hzzs](https://github.com/Azek431/hzzs) |

主要能力：MediaProjection 截图、C++ 多赛季障碍识别、可配置悬浮窗、受控自动操作、本地 MCP、主题包、双源 APK 更新、算法包（`release-index` 目录 + packages，**无** alg tag）。

### 算法包（代理速查）

- **检测更新**：读 `release-index` 的 `algorithms/stable.json|beta.json`，不是扫 Release。
- **下载**：`algorithms/packages/<filename>` 的 raw URL（Gitee/GitHub 双源）。
- **已有算法包**：
  - `official-bamboo-baseline v0.1.0` — 竹影书屋默认阈值（作者：HZZS Official，stable）
  - `sea-salt-living-room-v1 v0.1.0` — 海盐客厅多点找色基线（作者：酱油，**beta** 通道）
- **版本**：`manifest.json` 语义化 `MAJOR.MINOR.PATCH`（**首版 `0.1.0`**；与 App `0.1.0` 独立）。修一点 → `+PATCH`；完整一波 → `+MINOR`；破坏性 → `+MAJOR`。**先验证门禁通过再 bump**，禁止先改版本再测。
- **通道**：`beta` 测试 / `stable` 稳定；用户设置 `AlgorithmConfig.channel` 自选；未验证勿上 stable。
- **发布**：`tools/algorithm/publish_algorithm_release.py`（默认 dry-run；`--execute` 上传 packages 后写目录）。**禁止**为算法包创建 `alg-…` tag（除非用户改协议）。CI：`algorithm-release.yml` push `algorithm-packs/**` 时跑 `prepare_algorithm_release.py --auto-bump`：内容变则 **自动 PATCH** 再签包写 `release-index`，并把新 version commit 回 main（需 `ALGORITHM_SIGNING_*` Secrets）。
- **本地升版本**：`python tools/algorithm/bump_algorithm_version.py --source algorithm-packs/<id>`（默认 PATCH，同步 assets）。
- **信任锚**：⚠ 当前 0.1.0 暂未启用签名验签（`AlgorithmTrustAnchors` / `AlgorithmPackVerifier` 已移除），远端仅走 sha256 完整性校验。私钥永不入库；签名约束将来启用时 fail-closed 回滚。
- **客户端激活（「待启用」）**：
  - 点「使用此版本」→ 草稿 `pinnedAlgorithmId`（MANUAL）
  - **保存并应用** → `AlgorithmActivationCoordinator.onConfigCommitted`：未分析则立即 `configure`；**分析中**只记 `pendingCatalogId`，UI 可显示「待启用」
  - **开始分析** → `ensureConfigured` 消费 pending / 按 saved 解析
  - UI `pendingActivation` ≠ 自动操作关；诊断看 `pinned` / activation `id` / `pendingCatalogId` / `analysisRunning`
  - 详情：`docs/navigation/KOTLIN.md`、`docs/ALGORITHM_SYSTEM_V1.md`
- **完整步骤与 AI 代发流程**：根目录 `CLAUDE.md` 节「算法包网络更新」；规范 `docs/ALGORITHM_SYSTEM_V1.md`。

## 快速开始

```powershell
python tools/quality/check_resources.py
python tools/quality/check_project.py
.\gradlew.bat --no-daemon testDebugUnitTest lintDebug assembleDebug
```

本机 Debug 加速与 IC 修复：`gradle.local.properties`（`hzzs.native.abis=arm64-v8a`）、wrapper 默认 `CMAKE_BUILD_PARALLEL_LEVEL=2`、Kotlin 编译器默认 `in-process`、VS Code Gradle 任务互斥、勿在用户级关 configuration-cache、`tools/dev/repair_gradle_kotlin_cache.ps1`。Hilt 使用 **KSP**（非 kapt）。详见 `docs/testing.md` / README「本机构建加速」。

Debug APK：`app/build/outputs/apk/debug/app-debug.apk`（包名 `top.azek431.hzzs.debug`）

Release 需完整签名配置（见 README「Release 构建与签名」）：

```powershell
# 环境变量 ANDROID_KEYSTORE_* 或本机 keystore.properties（gitignore）
.\gradlew.bat --no-daemon :app:testDebugUnitTest :app:lintRelease :app:assembleRelease
```

Release APK：`app/build/outputs/apk/release/app-release.apk`  
签名解析：`ANDROID_KEYSTORE_*` → 历史 `AZEK431_RELEASE_*` → 根目录 `keystore.properties`。密钥库文件本身不在仓库。

## 架构（当前）

```text
feature → data/service → domain/core
platform/compat 只做能力探测
```

```text
top.azek431.hzzs/
├─ MainActivity.kt / HzzsApplication.kt
├─ core/           AppConfig、SettingsRepository、主题、更新
├─ domain/         VisionModels、GestureArbiter
├─ data/vision/    VisionRuntimeController、NativeVisionEngine、Tracker
├─ feature/        onboarding / home / runtime / settings / about
├─ service/        capture / overlay / automation
├─ platform/compat CaptureCapabilities、GestureCapabilities、SystemCapabilityAccess（悬浮窗/无障碍跳转）
├─ mcp/            McpService、Protocol、Session、ToolCatalog、ActionRegistry、UiBridge
└─ nativevision/   NativeVision JNI 边界

app/src/main/cpp/  jni_bridge、vision_engine、legacy_main/{vision2,vision_bamboo}（主路径）、sweet_factory/bamboo_bookstore（回退）、scene_geometry/color_components
app/src/test/      JVM 单测 + cpp/native_tests.cpp
```

### 主数据流

JinChan H4-A 复用同一帧链路生成 LEVEL/EXP/GOLD 与五槽 Shop typed observation；Shop 只在稳定
`inGame && SHOP_OPEN` 运行，未知槽不等于空槽，身份只做 GameData canonical/alias exact resolution。
Gold 保持 best-effort，200 仅为数据集风险过滤值。Board/Bench/Decision/Action 不在 H4-A 范围。

```text
FrameSource → VisionRuntimeController（完成驱动取帧；HUD 显示时临时隐身）
  → NativeVisionEngine (JNI)  【算法：只算 Detection 数据，不绘制】
  → VisionResultValidator → MultiObjectTracker（分析序号）
  → displayContour（App 呈现增强，仅 HUD）→ OverlayController（双层：穿透框 + 可拖 HUD；缺权限 fail-closed）
  → (自动操作门控通过后，独立 actionJob) GestureArbiter → GestureDispatcherFactory
       （ACCESSIBILITY dispatchGesture / SHIZUKU·ROOT input + dumpsys 前台）
```

**算法 ↔ 绘制**：经 `Detection` 数据关联、职责分离——Native/算法包不算绘制；框是否出现由 `overlay.showBoxes` 等决定。动作 / 距离 / Tracker 几何只读 `Detection.bounds`。`Detection.source` 从 C++ 经 JNI 到 Kotlin 保留默认启发式 / 多点找色 / Native sparse 等产生路径，未知 native code 回退默认来源；过滤诊断不得丢失原来源。`displayContour` 不得参与规划。详见 `docs/vision/V09_COMPLETION_DRIVEN_CONTOURS.md` 与根目录 `CLAUDE.md`。

### 配置流

```text
SettingsHome + 分类子页（appearance / algorithm / capture / overlay / automation / network / mcp / developer）共享 SettingsViewModel
  → update() 乐观 UI + SettingsRepository.preview（草稿，不落盘）
  → 算法页：bindSettings(draft) 刷新列表 active；选包写 pinned 草稿
  → 顶栏「保存并应用」→ SettingsRepository.save → AppConfig Flow
  → algorithmActivation.onConfigCommitted（未分析立即 configure；分析中 pending）
  → 离开设置 / 切走主导航：dirty 则弹窗（保存并离开 / 丢弃 / 取消）
  → Theme / Runtime / MCP（preview 优先于已保存）
AlgorithmCatalogController（检查/下载/pending 徽章）→ 算法页 UI
开始分析 → VisionRuntimeController → ensureConfigured + setAnalysisRunning
```

设置为**草稿预览 + 显式保存**：改动进进程内 preview，右上角「保存并应用」才落盘；分类间切换保留草稿。手动开自动操作等危险项先确认再写草稿；导入 / MCP 外部摄入经 `hardenedForExternalIngest`，不得静默开自动操作或自提 MCP 权限 / 关闭 `requireAuth` / 改写 `authToken`。

## 安全不变量（修改时必须保持）

1. `CaptureBackend.AUTO` **只**走 MediaProjection，不探测 Root/Shizuku/无障碍。
2. `GestureBackend` 与截图正交；手势 AUTO 优先无障碍、条件 Shizuku、永不 Root。
3. 自动操作默认关；导入/迁移不得静默开启；需免责声明版本。
4. MCP 默认仅 loopback；用户可显式允许局域网（`bindLocalhostOnly=false` → `0.0.0.0`）。默认免 Bearer，开启鉴权时持久化 Token（仅主动轮换）；默认写操作需确认；`toolPolicies` 可按工具覆盖。导入不得静默开局域网/自动操作/放宽工具策略（除非用户确认 elevations）。
5. MCP **访问日志**默认开（`accessLogEnabled`）；配置 schema **10**（叠加检测框 `persistBoxes`、自动复活等）。访问日志进程内 ring，无 Token/参数体；设置页 + `get_mcp_access_log`。绑定跟 `savedConfig`，策略/列表跟 `current()`；改权限级不重启服务。可选主机始终含 `127.0.0.1`。
6. **代理可用 MCP 自测**：`adb forward tcp:18765 tcp:8765` → `http://127.0.0.1:18765/mcp`（Claude Code `type: http`）。优先 `inspect` / `get_status` / `get_runtime_snapshot` / `get_automation_gates` / `get_events` / `get_mcp_access_log`；Wi‑Fi 直连失败先怀疑 AP 隔离。详见根 `CLAUDE.md`「代理用 MCP 自测」。
7. 主题包声明式 JSON，无脚本/远程资源。
8. 帧缓冲有 `close()` 租约；Native 不持有 Java 数组地址。
9. 视觉坐标归一化 `[0,1]`，仅绘制/手势层转像素。

## 与历史 main 的关系

- 更早历史线：多模块（`app`/`core`/`features/*`）、Views、叠层 `runtime/*` 视觉、`libhzzs_native.so`（analysis 状态机 + vision2 + bamboo）。
- 当前 `main`：单模块 Compose 统一架构；算法与动作阈值自历史线 **有选择回迁**。
- 对照历史行为时用历史提交/备份路径，**不要**把旧多模块包路径当当前代码路径修改。

## 测试

存在 JVM 测试，例如：

- `SettingsSessionTest`、`ThemePackageTest`
- `GestureArbiterTest`、`VisionResultValidatorTest`
- `FrameSequenceTest`

Native：`tools/vision/run_native_sanitizers.sh`、`run_host_tests.py --max-representative`。

## 文档地图

| 文件 | 用途 |
| --- | --- |
| `README.md` | 对外说明 + Star 趋势图 |
| `CLAUDE.md` | Claude 硬约束、**Core Philosophy（编程版八荣八耻）**、修改流程、代理记忆与经验；用户级同旨见 `~/.claude/CLAUDE.md` |
| `docs/ARCHITECTURE.md` | 架构 |
| `docs/SECURITY.md` | 安全 |
| `docs/TESTING.md` | 测试 |
| `docs/PROGRESS.md` | 进度与未完成 |
| `docs/ALGORITHM_SYSTEM_V1.md` | 官方算法包格式、发布与运行时（CC-1） |
| `docs/AGENT_EXPERIENCE.md` | 代理可复用工程经验短条（非硬约束全文） |
| `CHANGELOG.md` | 变更 |
| `CONTRIBUTING.md` | 贡献 |

## 协作规则

- 与用户沟通默认使用**中文**。
- 执行任务前识别模糊需求；不确定处先提问（用户明确授权自行决定除外）。
- **Core Philosophy（编程版八荣八耻）**：先查阅、先确认、复用现有、主动验证、守架构、诚实无知（「为菜」= 不装懂）；全文见根 `CLAUDE.md`「Core Philosophy · 编程核心哲学」；用户级 `~/.claude/CLAUDE.md` 对所有项目同样生效。
- 日常开发默认在 **`main`**；除非用户要求，不主动开 feature 分支。
- 改代码后同步相关 **README / CLAUDE / docs**，并跑质量门禁；触及硬约束或对外能力时更新 `CLAUDE.md` 与 `README.md`。
- 更新 `README.md` 时**不得**改动 `## Star History` 图链与 `sealed_token`；也不得无故删除徽章、免责、版本/构建/签名、MCP、仓库与许可证等关键信息。
- 可复用坑与取舍可记入 `docs/AGENT_EXPERIENCE.md` 与代理记忆；**冲突以当前源码为准**。
- 不提交密钥、签名文件、`keystore.properties`、`local.properties`、本地备份与生成二进制。
- Release 签名只从环境变量或 gitignore 的本地 properties 读取；**不要**把真实路径/密码写进会提交的文档。
- **不要**根据过期的“无测试 / 仅模拟 HUD / MediaProjection 未接入”描述做决策。
- 算法信任锚列表若空则外装官方包 fail-closed；私钥不入库。

## 游戏素材（可选参考）

视觉调参可参考本机素材库（若存在）：

`D:\Code\AI\火崽崽\火崽崽奇妙屋\`

仅用于算法验证；注意隐私与版权，勿把未脱敏截图提交进仓库。
