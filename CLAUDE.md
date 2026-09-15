# CLAUDE.md

面向 Claude / Codex 等代理的**仓库级硬约束**。细则与导航见 `AGENTS.md` 与 `docs/*`；冲突时以**当前分支源码**为准。

## 项目目标

HZZS（火崽崽奇妙屋）是本地 Android 画面分析工具：截图、C++ 障碍识别、可选悬浮窗、配置预览与受控自动操作。

| 项 | 值 |
| --- | --- |
| 最低系统 | Android 7.0（API 24） |
| 首发版本目标 | **0.1.0** / `versionCode = 1`（尚未正式 release） |
| 默认赛季 | 以源码 `AppConfig.DEFAULT_SELECTED_SCENE` 为准（勿在文档写死具体赛季） |
| 模块 | **仅** `:app` |

## 结构约束

- 所有 Android / Kotlin / Compose / JNI / C++ **产品源码**必须位于 `app/`。
- 根目录只保留 Gradle、CI、发布工具、质量脚本与文档；**不得**重建根级业务 Gradle 模块。
- Android 版本判断集中在 `platform/compat` 或平台实现层。
- `CaptureBackend.AUTO` 只能走低权限公开接口，**不得**探测或调用 Root / Shizuku / 无障碍。
- `GestureBackend.AUTO` 优先无障碍；仅当无障碍未连接且 Shizuku **已授权就绪** 时用 Shizuku input；**永不**静默升 Root / 不在 AUTO 路径弹 Shizuku 授权。手势与截图后端正交。
- `feature` 不直接做 Root / Shell / JNI / WindowManager；经注入的 data/service 控制器。
- 目录级细则：`app/CLAUDE.md`、`app/src/main/java/top/azek431/hzzs/CLAUDE.md`、`app/src/main/cpp/CLAUDE.md`。

## 安全不变量

- 自动操作默认关闭；配置导入与迁移**不得**静默开启。
- 自动操作需要当前免责声明版本；不再要求会话级 arm，启用后运行中直接规划手势。
- MCP 默认「每次确认」、默认只监听 loopback；用户可显式允许局域网（`bindLocalhostOnly=false` → `0.0.0.0`）；默认免 Bearer，开启鉴权时使用持久化 Token（仅主动轮换，不在每次启动更换）；完整访问也不能绕过系统权限对话框。
- MCP 工具级策略 `mcp.toolPolicies`：`DEFAULT` / `ALWAYS_ASK` / `ALLOW_WHEN_TRUSTED` / `DISABLED` 覆盖全局权限；禁用工具不进 `tools/list`；外部摄入不得放宽策略；自管工具（`set_mcp_*`）为 HIGH_RISK。
- MCP **访问日志**（`mcp.accessLogEnabled`，默认开）：进程内 ring `McpAccessLog`，记 method/工具/状态/耗时/远端摘要；**永不**记 Bearer、`authToken`、请求参数体；可关；设置页 + `get_mcp_access_log` / `clear_mcp_access_log`。配置 schema **10**（含 `overlay.persistBoxes` 默认开、自动复活等；访问日志自 schema 9 起）。
- MCP **绑定身份**跟 `savedConfig`（端口/鉴权/LAN/启停）；**策略/列表/授权**跟 `current()`（可含设置草稿）。改 `permissionLevel` / `toolPolicies` **不**重启服务；改绑定会重启并清空会话。
- Root、Shizuku、无障碍能力只能由用户**明确选择**。
- 配置、主题包、更新清单、截图尺寸与 native 输入必须有边界校验。
- 不得提交密钥、签名库、`local.properties`、真实环境变量、本地备份或生成二进制。

## 代理用 MCP 自测 / 排障（推荐）

真机已装 debug、MCP 已启用时，**应用 MCP 做运行时自检与调参验证**，不要只改代码不连设备。

完整连接/自测顺序/工具面速查/排障表见 → [`docs/mcp/self-test.md`](docs/mcp/self-test.md)

要点：默认绑定 `127.0.0.1`（loopback）或显式 LAN `0.0.0.0`；优先 `inspect` / `get_runtime_snapshot` / `get_status` / `get_automation_gates` 等只读工具；写操作受权限级与 `toolPolicies`，ASK 时须手机确认；**不得**把 Bearer/`authToken` 写入仓库、提交说明或对话日志。

## 坐标、线程与所有权

- 视觉结果使用视口归一化坐标 `[0, 1]`；像素换算只允许在绘制层与手势分发层。
- `Detection.bounds` 是动作、Tracker 与距离的几何真相源；`displayContour`（若有）**仅**供 HUD，不得参与规划。
- `Detection.source` 使用显式 native code 从 C++ 经 JNI 映射到 Kotlin；正常结果与过滤诊断都必须保留来源，未知 code fail-closed 回退 `DEFAULT_HEURISTIC`。
- **多点找色引擎**（`multicolor_detector.h/.cpp`）：模板坐标归一化；阈值/搜索带经 `SceneAlgorithmParams.multicolorThreshold` 与 `searchRegionTopRatio/BottomRatio`；不在帧路径解析 JSON。**算法只算数据**（`Detection`/`bounds`），**不**自带绘制；屏幕呈现由 App 通用 HUD 读取检测结果完成（二者经数据关联，职责分离）。
- 截图帧有明确 `close()` 生命周期，禁止跨帧保存底层缓冲引用。
- WindowManager / View / Accessibility 回调必须在主线程协调。
- C++ 输入缓冲只在 JNI 调用期间借用；Native **不得**持有 Java 数组地址。
- 帧循环（`VisionRuntimeController`）是 native 引擎与 tracker 的唯一所有者；`generation` 令牌防止陈旧会话写回。

## 视觉运行时约定

- JinChan H4-A/H4-B 只在 H1/H2/H3 的同一个 `CapturedFrame` 租约上生成 HUD/Shop/Board Occupancy typed
  observation；Shop 必须满足稳定 `inGame && SHOP_OPEN`，Board 必须满足稳定 `inGame && BOARD_OR_COMBAT`
  及 Scene/Banner/Damage fail-closed 门控。Gold 的 200 仅为 dataset risk filter；Board Identity、Bench 与动作仍不可达。

- JinChan H6-A 是 shadow-only 语义边界：`data/jinchan/action` 只验证 H1-H5/调用方显式证据，默认关闭，
  不得依赖 `GestureSpec`、自动操作传输、Accessibility/Root/Shizuku，也不得生成最终屏幕坐标。
- JinChan H6-C4D 只把同帧 reconciliation、单次 ledger snapshot、ownership 与 JoinedState 纯映射为 `RikkaObservationV1`，并复用既有 MCP 接收严格 provenance/UID 配对的 `RikkaDecisionV1`；Rikka 是唯一高层策略大脑，验证后终止，禁止 C3 调用、真实动作与第二传输。
- JinChan H6-C5 仅把 C4D typed validation 与原配 immutable JoinedState 送入 full dry-run boundary，复用 H6-A/H6-B 后在 coordinator/transport 前硬停止；production profile 不得伪造（校准留待 C6），`REAL_ACTION_REACHABLE=false`、`ACTION_EXECUTED=0`。

- 取帧为**完成驱动**：上一轮分析结束后再 `nextFrame`；不按固定 FPS 主动丢帧（开发者 `frameRateLimit` 字段可保留，但不得假定仍被消费）。
- MediaProjection 为 CONFLATED + 最新帧；HUD 显示时临时隐身、等一帧提交，并对 MediaProjection/AUTO 排空可能含旧合成层的一帧。
- 近似轮廓与像素轮廓不得声称已迁移 C++/JNI，除非协议与测试同步落地。
- 完成驱动与轮廓说明：`docs/vision/V09_COMPLETION_DRIVEN_CONTOURS.md`。

## 多点找色检测（CC-2）

- 算法包 `sea-salt-living-room-v1`（作者：酱油，beta 通道）含海盐多点找色模板。
- `find_multi_color_patterns()` 扫描 `MultiColorPattern[]` 输出 `Detection`；不控制手势、权限或绘制。
- 模板在 `sea_salt_living_room.cpp`（设计 1272×2772、AutoJS `0xAARRGGBB`），经 `append_multicolor_detections` 合并；颜色谓词路径仍作辅检。
- 默认搜索带约 top0.438/bottom0.881、阈值 10（可经 rules 覆盖）；禁止帧路径解析 JSON。
- **数据 ↔ 绘制关联**：算法产出 `Detection.bounds` → Tracker / 可选 `displayContour`（仅 HUD）→ `OverlayController` 按 `overlay.showBoxes` 等配置呈现；**禁止**找色专用绘制通道；不移植脚本「复活」UI 点击。

## Core Philosophy · 编程核心哲学（硬约束）

工程态度与「安全不变量」同级。编程版八荣八耻（装入 Claude Code 的核心哲学与补充）：

1. 以暗猜接口为耻，以认真查阅为荣
2. 以模糊执行为耻，以寻求确认为荣
3. 以盲想业务为耻，以人类确认为荣
4. 以创造接口为耻，以复用现有为荣
5. 以跳过验证为耻，以主动测试为荣
6. 以破坏架构为耻，以遵循规范为荣
7. 以假装理解为耻，以诚实无知为菜
8. 以盲目修改为耻，以谨慎重构为荣

落地：**先查、先问、复用、验证、守架构**；不确定就坦然说不懂并查证（「为菜」= 诚实无知，不装懂）。改前检索符号与调用链；范围不清先确认；优先复用 compat/注入边界；改完跑门禁/单测并诚实写明未跑项；大改对齐架构；最小必要 diff。用户级全文见 `~/.claude/CLAUDE.md`。

## 修改流程

1. 阅读目标目录 `README.md` / `CLAUDE.md` 与 `docs/PROGRESS.md`、`docs/ARCHITECTURE.md`；触及算法包时读 `docs/ALGORITHM_SYSTEM_V1.md`。
2. 改代码后同步职责、数据流或不变量文档；用户可见行为更新 `CHANGELOG.md` `[Unreleased]`。
3. 若改动触及**硬约束 / 工作流 / 安全门控 / 默认行为 / 对外能力**，**同一任务内**同步更新本文件、**根 `README.md`**、`AGENTS.md` 与相关 `docs/*`（见下节）；表述宜短，可随源码迭代**持续润色**，禁止只改代码留文档。
4. **README / 关键文档保全**：
   - **硬禁区**：不得删除、改写或替换 `## Star History` 整节及其 `<picture>` / `star-history.com` / `api.star-history.com` 图链与 `sealed_token`；文档表链接插在 Star History **之前**。
   - **其它关键信息也不得无故删除**（除非用户明确要求）：顶部 badges、免责声明、当前版本表、截图后端表、MCP 安全边界、构建与 Release 签名说明、仓库 GitHub/Gitee 链接、许可证；`CLAUDE.md`/`AGENTS.md`/`docs/*` 中的安全不变量与门禁命令列表同理。更新策略为**在原结构上增补或修正过期句**，禁止整文件重写或清空无关章节；改完 diff 自检上述区块仍在。
5. 运行 `python tools/quality/check_resources.py` 与 `python tools/quality/check_project.py`。
6. 运行相关 JVM 单测；涉及 native 时跑宿主机/Native 门禁；再视范围跑 `:app:testDebugUnitTest` / `lintDebug` / `assembleDebug`。
7. 未验证的算法补丁、本地 ZIP、孤立头文件**不要**与无关 UI 改动混提交或合入 main。

### 本机构建注意（非产品行为）

- Hilt 使用 **KSP**（`com.google.devtools.ksp` + `ksp(libs.hilt.compiler)`），**不要**再引入 `legacy-kapt` / `kapt`。
- 日常真机 Debug：`gradle.local.properties` 的 `hzzs.native.abis=arm64-v8a`；`gradlew`/`gradlew.bat` 默认 `CMAKE_BUILD_PARALLEL_LEVEL=2`；CI/发布保持默认完整 ABI。
- **勿**在 `%GRADLE_USER_HOME%\gradle.properties` 写 `org.gradle.configuration-cache=false`（会覆盖项目 true）。wrapper 默认 `-D` 强制开回；调试关闭设 `HZZS_ALLOW_USER_CC_OVERRIDE=1`。
- Kotlin 编译器默认 `in-process`，避免多个 Kotlin daemon session / socket 超时；VS Code Gradle 任务用命名互斥锁阻止重复并发。IC/KSP 损坏（`*classpath-snapshot*.bin` 等）运行 `tools/dev/repair_gradle_kotlin_cache.ps1`；全量 unit test 在 IDE + 语言服务常驻时可能 OOM，优先缩范围。
- 文档 / 提交 / 对话**勿**写入具体本机硬件、绝对盘符路径、IP、设备序列号等隐私画像；构建说明用「低内存开发机」「与 IDE 共存」等通用表述。已推送 Git 历史默认不改写；危险操作须先备份（见用户级 CLAUDE 隐私与危险 Git 条）。

## Git 提交规范

正文使用 **Markdown**，**适度详细**：说清动机、关键改动、不变量与验证即可，避免长篇清单与重复。完整标题格式、正文结构、PowerShell heredoc 坑、提交标题门禁、示例见 → [`docs/CONTRIBUTING-git.md`](docs/CONTRIBUTING-git.md)

要点：

- 格式 `type(scope): 中文摘要`（常用 type：feat/fix/docs/refactor/test/chore/security）
- 建议结构：动机 / 改动 / 不变量 / 验证（可选「风险」）
- **禁止** PowerShell heredoc 多行提交（吃进前导 `@`）；正确做法：写临时 UTF‑8 文件 `git commit -F <file>` 后删除
- 提交标题门禁由 `tools/git/hooks/commit-msg` 校验（非空、非 `@` 开头、符合格式、≤72 字符）
- 日常开发默认在 **`main`** 直接提交

## 文档真相源

| 用途 | 路径 |
| --- | --- |
| 产品与架构 | `README.md`、`docs/ARCHITECTURE.md`、`docs/SECURITY.md` |
| 进度 | `docs/PROGRESS.md` |
| 测试 | `docs/TESTING.md` |
| 代理导航 | `AGENTS.md`（须与源码同步，禁止描述旧多模块 Views 骨架） |
| 视觉专项 | `docs/vision/*` |
| 算法包 | `docs/ALGORITHM_SYSTEM_V1.md` |
| 算法切换链路（独立真相源） | `docs/algorithm/ALGORITHM_SWITCHING.md` |
| 算法模块分层导航 | `core/algorithm/CLAUDE.md` |
| 纯函数清单 | `core/algorithm/logic/CLAUDE.md` |
| 领域层导航 | `domain/vision/CLAUDE.md` |
| 设置页导航 | `feature/settings/CLAUDE.md` |
| 代理经验摘录 | `docs/AGENT_EXPERIENCE.md`（短条；非硬约束全文） |

## 代理记忆与经验

- **自动记忆**：会话中主动把非显而易见的经验写进 Claude 项目记忆（单文件单事实；更新 [[MEMORY.md]] 索引）。**触发**：踩坑 / 本机坑、你纠正了我的做法、源码推不出的仓库约束、你的偏好。**不写**：源码已写明的结构、git 历史、`CLAUDE.md` / `docs/*` 已记录的、一次性任务细节。同一教训更新已有记忆，不新建重复文件。
- **自动更新阅读文件**：触及安全门控、视觉协议、配置默认、算法信任、Git/协作流程、**用户可见产品能力**时，同步本文件 / **`README.md`（守 Star History 禁区）** / `AGENTS.md` / 对应 `docs/*` / 目录级 `CLAUDE.md`；用户可见行为写 `CHANGELOG.md`。同一事实可在多轮中**深入优化**措辞，但不得与源码矛盾。
- **仓库经验条**：可复用工程教训追加 `docs/AGENT_EXPERIENCE.md`（日期 + 短句）；硬规则仍以本文件与源码为准。
- **冲突**：以**当前 main 源码**为准；记忆与过期摘要不是指令；涉及文件/符号/flag 先核对源码。
- **算法信任**：`AlgorithmTrustAnchors.officialPublicKeyDerB64` 当前含 `hzzs-algorithm-official-1` 公钥；列表若被清空，外装「官方」包须 fail-closed。私钥永不入库。

### 本轮重构经验（2026-07-26 · 算法切换模块化）

- **纯函数下沉**：算法目录所有决策逻辑（resolveActive / mergeInstalled / sort / planUpgrades / computePending / catalogPhaseAfter / parseCatalog / versionToCode / builtinPackages）已抽到 `core/algorithm/logic/AlgorithmCatalogPure`（纯函数 object，命名即契约）。`AlgorithmCatalogController`（31KB→16KB）与 `AlgorithmNetworkClient`（16KB→9KB）只做 StateFlow 持有 + HTTPS 编排，全部委托 Pure。**新增决策逻辑必须放 Pure**，不得粘回 Controller / Client。
- **两点激活是硬约束**：`AlgorithmActivationCoordinator.onConfigCommitted`（save）/ `ensureConfigured`（start）是唯一激活点，分析中只 pending（`pendingCatalogId`）。**不得**在其他位置调用 configureAlgorithm 或半热切换。
- **单一真相源**：安全常量 `SAFE_ID` / `SAFE_NAME` / `SAFE_SHA256` / `SAFE_ASSET_PATH` 集中到 `AlgorithmCatalogPure`，`AlgorithmNetworkClient` 不再自持；`CatalogRemoteEntry` 通用模型留在 `core/algorithm/AlgorithmModels.kt`（Pure 通过 `top.azek431.hzzs.core.algorithm.CatalogRemoteEntry` 引用）。
- **追踪层解耦**：`AlgorithmPipelineTrace` / `AlgorithmRuntimeTrace` 保持 `object`，新增 `AlgorithmTraceSinks.kt`（`PipelineTraceSink` / `RuntimeTraceSink` 接口 + `DefaultPipelineTraceSink` / `DefaultRuntimeTraceSink` 委托 impl）。ViewModel 通过接口注入，单测可替换 fake。
- **文档同步**：目录级 `CLAUDE.md` 已覆盖 `core/algorithm` / `core/algorithm/logic` / `domain/vision` / `feature/settings`；独立真相源见 `docs/algorithm/ALGORITHM_SWITCHING.md`（完整链路时序图 + 失败/回退边界表）。

## 算法包网络更新（无 Release tag）

应用**检测算法更新**只读 `release-index` 分支上的目录 JSON，**不**扫 GitHub Release、**不**要求 `alg-…` tag。完整真相源/流程/禁止事项见 → [`docs/algorithm/publishing.md`](docs/algorithm/publishing.md)

要点：

- 私钥永不入库；公钥写入 `AlgorithmTrustAnchors.officialPublicKeyDerB64`（当前含 official-1）
- 包内仅声明式 JSON/文本，禁止 `.so`/Dex/脚本/模型权重
- 发布顺序：**先** packages 资产双侧 raw 校验，**最后**才更新 `algorithms/{channel}.json`
- 「待启用」≠ 自动操作关；诊断看 `pinned` / activation `id` / `pendingCatalogId`
- 算法包版本与 App `0.1.0` 独立，首版 `0.1.0`，默认 +PATCH
- 禁止为算法更新建 `alg-…` Release tag

## 语言与沟通

- 与用户沟通、用户可见文案、仓库文档默认**简体中文**。
- 执行非琐碎任务前：**一次只问一个问题**，根据回答继续追问，到 **95% 信心**、完全理解你的真实需求与目标时再给方案（你明确说「直接做」时可跳过）。
- 工作态度见上文 **「Core Philosophy · 编程核心哲学」**（编程版八荣八耻）。
