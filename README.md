# HZZS 火崽崽数据分析

<p align="center">
  <strong>面向《火崽崽奇妙屋》的第三方 Android 本地画面分析工具</strong><br>
  截图采集 · C++ 障碍识别 · 可配置悬浮窗 · 受控自动操作 · 本地 MCP
</p>

<p align="center">
  <a href="https://github.com/Azek431/hzzs/stargazers">
    <img src="https://img.shields.io/github/stars/Azek431/hzzs?style=social" alt="GitHub stars">
  </a>
  <a href="https://github.com/Azek431/hzzs/releases">
    <img src="https://img.shields.io/github/v/release/Azek431/hzzs?include_prereleases" alt="Release">
  </a>
  <img src="https://img.shields.io/badge/Android-7.0%2B-green" alt="Android 7.0+">
  <img src="https://img.shields.io/badge/Kotlin%20%2B%20C%2B%2B17-blue" alt="Kotlin + C++17">
  <img src="https://img.shields.io/badge/version-0.1.0-orange" alt="version 0.1.0">
</p>

<p align="center">
  <a href="#主要能力">主要能力</a> ·
  <a href="#权限与截图后端">截图后端</a> ·
  <a href="#mcp">MCP</a> ·
  <a href="#代码结构">结构</a> ·
  <a href="#构建">构建</a> ·
  <a href="#文档">文档</a> ·
  <a href="#star-history">Star History</a>
</p>

> **免责声明**
>
> HZZS 与游戏、平台及其运营方没有隶属或授权关系。本项目用于技术研究与数据分析，不承诺识别结果绝对准确。自动操作属于高风险可选功能，可能因画面变化、网络延迟、系统卡顿或识别误差产生错误操作。使用者应遵守游戏规则、平台条款和当地法律，并自行承担使用风险。

## 当前版本

| 项 | 值 |
|---|---|
| 版本 | **0.1.0**（首发目标版本，尚未正式打 tag 发布） |
| versionCode | **1** |
| 包名 | `top.azek431.hzzs` |
| 最低系统 | Android 7.0（API 24） |
| 目标 / 编译 SDK | 37 |
| 默认赛季 | 见源码 `AppConfig.DEFAULT_SELECTED_SCENE`（文档不写死，避免与代码漂移） |
| 模块形态 | 单一 `app` Gradle 模块 |
| 配置 schema | DataStore v10（含手势后端、MCP 工具策略/访问日志、检测框持久绘制、自动复活开关） |

首要目标：低权限默认、Android 7+ 兼容、比例坐标适配、设置草稿预览与显式保存、算法结果可测试，以及让开发者和 AI 能快速理解并安全修改代码。

## 主要能力

> JinChan 迁移状态：H1/H2/H3/H4-A 已冻结，H4-B 已接入同帧 Board Occupancy typed observation、
> Scene Truth Gate 与污染门控；Board Identity、Bench、Decision 与 Action 未启用。Gold 仍为 best-effort，
> Shop 仅在稳定 `inGame && SHOP_OPEN` 且完整构建时可用。
>
> H6-A 仅新增 JinChan 像素无关的语义动作契约与纯安全门：默认 `actionEnabled=false`，只产出带证据来源的
> Approved/Rejected 值；未接入坐标解析、手势或任何真实输入，`REAL_ACTION_REACHABLE=false`。

- 单一 `app` 模块，业务代码按职责分包（Compose + Hilt）。
- **多赛季**障碍配置，共用视口比例坐标；首次安装默认赛季**只**由源码 `AppConfig.DEFAULT_SELECTED_SCENE` 决定（文档不写死赛季名）。
- 默认识别当前赛季可用障碍类别，可按赛季关闭具体类别。
- 固定比例、启动检测一次、持续检测三种玩家基准策略。
- **完成驱动取帧**（非固定 FPS 丢帧）；**MediaProjection 默认截图**；`CaptureBackend.AUTO` 只选低权限公开接口，**不探测 Root / Shizuku / 无障碍**。
- 无障碍、Shizuku、Root 仅由用户主动选择（Shizuku 适配器见进度文档）。
- 极简、紧凑、调试 HUD 三种悬浮窗（**默认调试 HUD**）；**双层绘制**（穿透检测框 + 可拖 HUD 同时存在）；检测框默认**短时持久绘制**（丢检淡出，仅 HUD）；HUD 可显示近似轮廓（仅显示，不参与动作几何）。
- 设置 / 首次引导 / 运行页提供系统悬浮窗与无障碍权限状态与跳转；缺系统悬浮窗权限时分析可继续，但不绘制。
- Material 3 内置主题、动态取色、AMOLED、高对比、自定义颜色与 `.hzzstheme` 主题包；动效受应用「减少动效 / 动画强度」与系统 animator 倍率约束。
- 设置改动进入**草稿预览**（进程内 preview，可即时作用于主题/悬浮窗等）；顶栏右上角「保存并应用」才永久落盘。离开设置若有未保存更改则弹窗（保存并离开 / 丢弃 / 取消）；分类间切换保留草稿。手动开启自动操作等危险项须先确认风险；导入/MCP 外部摄入不得静默开启自动操作或自提权限。
- 首次启动引导（5 步：欢迎/赛季/截图/权限/完成；权限可稍后；完成页可折叠开启自动操作并风险确认）、简体中文免责声明。
- 关于页连续点击版本号 7 次解锁开发者选项；解锁后设置首页出现「开发者选项」，页内可关闭。关于入口与设置入口共用同一开发者页；可开关系统「指针位置」（可点授权 Shizuku，已授权优先 shell 绝对路径 settings；否则修改系统设置 / Root）；可选诊断导出（脱敏，不含 Bearer）。MCP 服务在独立「MCP 服务」分类页中配置。
- MCP 本地服务：只读 / 每次确认 / 会话信任 / 完整访问四级权限，可按工具覆盖策略（始终确认 / 信任放行 / 禁用）。默认免鉴权；可选持久化 Bearer（仅主动轮换）。设置页状态卡始终展示 `http://127.0.0.1:<port>/mcp`，可选主机固定含回环；弹窗搜索管理工具策略；可选访问日志 ring（默认开，不记 Token/参数）。
- 声明式算法包（Ed25519 验签；信任锚含 official-1 公钥；列表空时外装 fail-closed）；APK 可捆绑声明式包；内置算法回退。
- C++ 输入边界、JNI 失败隔离、宿主机 Sanitizer、数据集回归与发布门禁。
- Gitee 优先的双源签名更新与增量补丁工具链（应用内检查入口见设置/关于）。

## 权限与截图后端

| 后端 | 适用范围 | 默认 | 说明 |
|---|---|---:|---|
| 自动 | API 24+ | 是 | **只选择低权限 MediaProjection**，不探测 Root 或 Shell |
| 屏幕录制 | API 24+ | 推荐 | 用户通过系统界面授权，适合连续分析 |
| 无障碍截图 | API 30+ | 否 | 仅在用户已开启无障碍服务后使用，存在系统调用频率限制 |
| Shizuku | 高级 | 否 | 用户显式选择；经 Shizuku 执行 `screencap -p`（需安装/启动/授权） |
| Root | Root 设备 | 否 | 每帧执行受超时和大小限制的 `screencap`，兼容性和风险最高 |

应用不会自动调用 `su`、自动启动 Shizuku、自动开启无障碍或悬浮窗，也不能绕过 Android 系统授权界面。录屏在开始分析时由系统对话框授予；悬浮窗与无障碍须进入系统设置。

## MCP

MCP 服务默认关闭，**默认只绑定**设备 IPv4 回环（`127.0.0.1`）。可在设置中显式「允许局域网访问」后绑定 `0.0.0.0`（同 Wi‑Fi 可达）。传输为 **Streamable HTTP**（`POST /mcp`），兼容 [RikkaHub](https://github.com/rikkahub/rikkahub)、OperitAI、Claude Code 等客户端。

设置 → MCP 服务提供**展示地址场景**（同机回环 / 电脑 ADB 转发 / 局域网 IP / 自定义主机）与**客户端导入格式**（RikkaHub `streamable_http` / Claude Code `http`）。展示场景只改复制文案；绑定由 loopback / 局域网开关决定。

**同机 RikkaHub（推荐）**

1. HZZS 设置 → MCP 服务 → 启用服务（即时生效）；**默认免 Bearer 鉴权**，同机客户端无需填请求头。
2. 确认状态卡显示绑定 `127.0.0.1:<port>`（或局域网模式下 `0.0.0.0:<port>`）正在运行。
3. 客户端格式选 RikkaHub → 点「复制导入 JSON」→ RikkaHub 设置 → MCP → 导入粘贴（类型必须是 **Streamable HTTP**，不要选 SSE）。
4. 在 RikkaHub 助手里勾选该 MCP 服务器即可调用工具。

若仍连不上：确认服务已启用并在运行、端口未被占用、客户端未误选 SSE。可选开启「要求 Bearer 鉴权」时 Token **持久保存**，重启不会自动更换；点「轮换 Token」后须重新复制/导入。

**电脑端（Claude Code 等）**

1. **ADB**：展示场景选「电脑 ADB 转发」，执行 `adb forward tcp:<port> tcp:<port>`，URL 仍用 `http://127.0.0.1:<port>/mcp`。
2. **局域网**：设置中确认开启「允许局域网访问」后，用手机 Wi‑Fi IPv4：`http://<lan-ip>:<port>/mcp`。
3. 客户端格式选 Claude Code → 复制导入 JSON（`type: http`）到项目 `.mcp.json`；若开启鉴权再加 `Authorization: Bearer <token>`。

应用内页面、状态、设置、分析和悬浮窗操作通过语义工具暴露（严格 inputSchema），不依赖屏幕坐标点击。常用能力包括：`inspect` 一键诊断、运行态快照、局部 `patch_settings`（含批量 operations）、命名配置 profile、赛季/阈值/主题/悬浮窗、开发者开关、调试帧触发/读取（HIGH_RISK + `allowDebugFrames`）、算法列表/激活/下载/一键升级、`get_events` 增量事件、版本/更新检查/运行时指标、自动操作门闩解释、日志与脱敏诊断导出、MCP 状态/访问日志。写操作受四级权限与工具策略；「信任本次会话」仅绑定当前内存会话，服务重启后失效。即使「完整访问」也不能绕过系统录屏 / 悬浮窗 / 无障碍 / 安装对话框。

H6-C4D 复用同一 MCP 服务提供 `app://rikka/observation/v1/latest` 与 `submit_rikka_decision_v1`：前者读取由同帧 JoinedState 纯映射的不可变观测，后者仅按配对 provenance/UID 验证 Rikka 决策并终止；Rikka 是唯一高层策略大脑，桥接层不调用 C3、不执行动作，也不建立第二套传输。

## 代码结构

```text
app/
├─ src/main/java/top/azek431/hzzs/
│  ├─ core/         配置、主题、设计系统、更新模型
│  ├─ domain/       视觉与自动操作领域规则
│  ├─ data/vision/  运行时编排、追踪、JNI 适配
│  ├─ feature/      引导、首页、运行、设置、关于
│  ├─ platform/     Android 版本与能力探测
│  ├─ service/      截图、无障碍、悬浮窗
│  ├─ mcp/          本地 MCP 传输和权限仲裁
│  └─ nativevision/ JNI 加载边界
├─ src/main/cpp/    C++ 视觉算法、JNI 与宿主机 ABI
└─ src/test/        JVM 与 C++ 测试

docs/               架构、安全、测试和进度
tools/              质量检查、视觉回归和发布工具
.github/workflows/  构建与发布门禁
```

每个重要目录的 `README.md` 与 `CLAUDE.md` 说明职责、数据流、线程模型、修改入口和验证命令。

主数据路径：

```text
FrameSource → VisionRuntimeController（完成驱动取帧；HUD 显示时临时隐身）
  → NativeVisionEngine (JNI)  【算法：只算 Detection，不绘制】
  → VisionResultValidator → MultiObjectTracker
  → displayContour（App 呈现增强，仅 HUD）→ OverlayController（双层：穿透框 + 可拖 HUD）
  → (自动操作门控通过后) GestureArbiter → 无障碍手势
```

算法与绘制经 `Detection` 数据关联、职责分离：Native/算法包不绘制；是否画框由悬浮窗配置决定。`Detection.source` 端到端标记默认启发式、多点找色与 Native sparse 等产生路径；JNI 使用稳定数值协议，未知值 fail-closed 回退默认来源。

## 构建

需要：

- JDK 17
- Android SDK 37
- Android NDK `28.2.13676358`
- CMake `3.22.1`
- Python 3.11+（质量与视觉回归工具）

```bash
python3 -m compileall -q tools
python3 tools/quality/check_resources.py
python3 tools/quality/check_project.py
./gradlew --no-daemon clean testDebugUnitTest lintDebug assembleDebug
```

Windows：

```powershell
.\gradlew.bat --no-daemon clean testDebugUnitTest lintDebug assembleDebug
```

### 本机构建加速（可选）

复制示例并按需修改（已 gitignore，不入库）：

```powershell
Copy-Item gradle.local.properties.example gradle.local.properties
# 默认示例为 hzzs.native.abis=arm64-v8a（仅真机 ABI）
```

也可单次传入：`.\gradlew.bat -Phzzs.native.abis=arm64-v8a assembleDebug`。  
**发布 / CI 不要设置该属性**，默认仍编 `arm64-v8a,armeabi-v7a,x86_64`。

低内存 / 逻辑核偏少时限制 Ninja 并行，避免与 Kotlin/IDE 互抢：

```powershell
# 可选：仓库 gradlew.bat / gradlew 在未设置时默认即为 2；VS Code 任务也会注入
$env:CMAKE_BUILD_PARALLEL_LEVEL = '2'
```

项目已开启 Build Cache 与 Configuration Cache。  
**常见慢因**：`%GRADLE_USER_HOME%\gradle.properties` 若写了 `org.gradle.configuration-cache=false`，会覆盖项目级 `true`，配置阶段每次全量重算（低配开发机上可能多出数分钟）。请**删除或注释该行**。仓库 `gradlew` / `gradlew.bat` 默认再注入 `-Dorg.gradle.configuration-cache=true` 作为保险；若要调试用户级关闭，设 `HZZS_ALLOW_USER_CC_OVERRIDE=1`。

另一常见慢因：VS Code / Claude / Serena / 多路 Kotlin LS 常驻时，空闲内存不足会使增量任务因换页与 daemon 抖动变慢——构建前尽量释放语言服务与多余 agent。

依赖注入使用 **Hilt + KSP**（无 kapt 双编译）。根 `gradle.properties` 按**低内存开发机 + IDE 共存**收紧 Daemon/Kotlin 堆与 `workers.max=2`；空闲内存充足时在 `%GRADLE_USER_HOME%\gradle.properties` 覆盖堆（`gradle.local.properties` 仅读 `hzzs.*` 如 ABI）。

Kotlin 增量损坏（`*classpath-snapshot*.bin` 找不到）或 daemon 被 stop 时：

```powershell
.\tools\dev\repair_gradle_kotlin_cache.ps1 -Compile
```

可用内存过低时先释放 IDE/语言服务再构建；详见 [`docs/testing.md`](docs/testing.md)。

Debug APK：`app/build/outputs/apk/debug/app-debug.apk`

Native 宿主机验证：

```bash
bash tools/vision/run_native_sanitizers.sh
python3 tools/vision/run_host_tests.py --dataset /path/to/screenshots --max-representative 48
python3 tools/vision/evaluate_dataset.py --dataset /path/to/screenshots --output build/vision-results
```

## Release 构建与签名

Debug APK 使用 Android 调试签名，仅适合本机测试（包名 `top.azek431.hzzs.debug`）。  
正式 Release APK（包名 `top.azek431.hzzs`）使用开发者本机私有 PKCS12 签名，**密钥库与密码永不入库**。

### 签名材料从哪里来

| 来源 | 说明 |
|---|---|
| 本机密钥库 | 开发者私有保存（例如独立目录下的 `*.p12`），不在本仓库 |
| CI | GitHub Secrets：`ANDROID_KEYSTORE_BASE64` + 密码 / alias，仅发布工作流使用 |
| 算法包密钥 | **独立** Ed25519，与 APK keystore 无关 |

`.gitignore` 已忽略 `*.p12` / `*.jks` / `keystore.properties` 等。历史重构**不会**把密钥写进 git；若本机找不到，请查私密备份目录或 CI Secrets，而不是 git 历史。

### 配置方式（任选其一）

#### 推荐：环境变量（与 CI 一致）

```powershell
$env:ANDROID_KEYSTORE_PATH = 'D:\path\to\azek431-android-release.p12'
$env:ANDROID_STORE_PASSWORD = '********'
$env:ANDROID_KEY_ALIAS = 'your-alias'
$env:ANDROID_KEY_PASSWORD = '********'
.\gradlew.bat --no-daemon :app:testDebugUnitTest :app:lintRelease :app:assembleRelease
```

仍兼容历史变量名：`AZEK431_RELEASE_STORE_FILE` / `AZEK431_RELEASE_STORE_PASSWORD` / `AZEK431_RELEASE_KEY_ALIAS` / `AZEK431_RELEASE_KEY_PASSWORD`。

#### 本机：交互脚本（推荐）

在仓库根目录执行，按提示输入路径 / 密码 / alias（密码不回显），可选写入 `keystore.properties` 并打包：

```powershell
powershell -ExecutionPolicy Bypass -File tools\release\build_signed_release.ps1
```

仅配置不构建：加 `-SkipBuild`。已有配置只构建：加 `-BuildOnly`。

#### 本机：属性文件（gitignore）

```powershell
Copy-Item keystore.properties.example keystore.properties
# 编辑 keystore.properties 填入路径、alias 与密码
.\gradlew.bat --no-daemon assembleRelease
```

解析顺序：`ANDROID_KEYSTORE_*` → `AZEK431_RELEASE_*` → `keystore.properties` / `signing.properties` / `local.secrets.properties`。  
四项齐全且密钥库文件存在才启用 release 签名；`assembleRelease` 在缺失时会**失败**，不会产出误签名包。

Release APK：`app/build/outputs/apk/release/app-release.apk`

### 发布工作流

正式对外发布由 `.github/workflows/release.yml` 负责：APK 验签、更新清单签名、增量补丁、GitHub/Gitee 资产同步与匿名下载校验。  
当前仓库尚未打出正式 `v0.1.0` Release 时，应用内更新检查因源不可用而失败是预期行为。

### 官方算法包

算法包（`.hzzsalg`）使用**独立于 APK keystore** 的 Ed25519 密钥发布，工具链见 `tools/algorithm/`，说明见 [`docs/ALGORITHM_SYSTEM_V1.md`](docs/ALGORITHM_SYSTEM_V1.md)。

```bash
python tools/algorithm/validate_algorithm_pack.py --source algorithm-packs/official-bamboo-baseline
python tools/algorithm/publish_algorithm_release.py \
  --source algorithm-packs/official-bamboo-baseline \
  --work-dir build/algorithm-release \
  --private-key /secure/algorithm-ed25519.pem \
  --key-id hzzs-algorithm-official-1
```

默认 dry-run；真实网络发布需 `--execute`，并配置 Secrets：`ALGORITHM_SIGNING_PRIVATE_KEY_B64`、`ALGORITHM_SIGNING_KEY_ID`、`GITEE_TOKEN`。  
目录发布在 `release-index` 分支的 `algorithms/stable.json` 与 `algorithms/beta.json`；下载 URL 由客户端按 `source + tag + filename` 拼装，不写入目录。  
CI 工作流：`.github/workflows/algorithm-release.yml`。

## 测试边界

宿主机数据集运行可以证明崩溃安全、输出约束和性能分布，但 **不能替代人工真值标注**。在没有独立标注前，项目不会声称达到 99% 准确率、所有机型覆盖或固定像素误差目标。

中国厂商 ROM 的后台限制、悬浮窗、Root、Shizuku 和真实游戏操作仍需要对应真机报告。详见 [`docs/TESTING.md`](docs/TESTING.md) 与 [`docs/PROGRESS.md`](docs/PROGRESS.md)。

## 文档

| 文档 | 说明 |
|---|---|
| [`CLAUDE.md`](CLAUDE.md) | AI / Claude Code 协作硬约束、记忆与文档同步流程 |
| [`AGENTS.md`](AGENTS.md) | Codex / 通用 AI 代理导航（与当前架构同步） |
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | 架构与数据流 |
| [`docs/SECURITY.md`](docs/SECURITY.md) | 安全与权限模型 |
| [`docs/TESTING.md`](docs/TESTING.md) | 测试与门禁 |
| [`docs/PROGRESS.md`](docs/PROGRESS.md) | 进度与未完成项 |
| [`docs/ALGORITHM_SYSTEM_V1.md`](docs/ALGORITHM_SYSTEM_V1.md) | 官方算法包格式、签名与发布 |
| [`docs/AGENT_EXPERIENCE.md`](docs/AGENT_EXPERIENCE.md) | 代理可复用工程经验短条 |
| [`CHANGELOG.md`](CHANGELOG.md) | 变更日志 |
| [`CONTRIBUTING.md`](CONTRIBUTING.md) | 贡献说明 |

## Star History

<!-- 官方嵌入（picture + sealed_token）结构保留。当前 api.star-history.com 对本仓库返回 timeout，GitHub 上主图会裂；下方仓库内 SVG 保证可见。 -->
<a href="https://www.star-history.com/?repos=Azek431%2Fhzzs&type=date&legend=top-left">
 <picture>
   <source media="(prefers-color-scheme: dark)" srcset="https://api.star-history.com/chart?repos=Azek431/hzzs&type=date&theme=dark&legend=top-left&sealed_token=TrebmjTeykrZKyRKqO5hC3x3jK1uVMBiiIDoIC-qZgkEIRzdfy8q6EC0wIK331P9LvcEql19Oonj-x0-1kzxfmzdiucuyIF0nP2yedpNm0E5L17po7wYPw0Q7LQDOWmTjQ1GZVM5pCel6VlYP2iqUSh6L648xAIOBa2T37Icc1SdBF7ypZrtJ8DV9rnw" />
   <source media="(prefers-color-scheme: light)" srcset="https://api.star-history.com/chart?repos=Azek431/hzzs&type=date&theme=light&legend=top-left&sealed_token=TrebmjTeykrZKyRKqO5hC3x3jK1uVMBiiIDoIC-qZgkEIRzdfy8q6EC0wIK331P9LvcEql19Oonj-x0-1kzxfmzdiucuyIF0nP2yedpNm0E5L17po7wYPw0Q7LQDOWmTjQ1GZVM5pCel6VlYP2iqUSh6L648xAIOBa2T37Icc1SdBF7ypZrtJ8DV9rnw" />
   <img alt="Star History Chart" src="https://api.star-history.com/chart?repos=Azek431/hzzs&type=date&legend=top-left&sealed_token=TrebmjTeykrZKyRKqO5hC3x3jK1uVMBiiIDoIC-qZgkEIRzdfy8q6EC0wIK331P9LvcEql19Oonj-x0-1kzxfmzdiucuyIF0nP2yedpNm0E5L17po7wYPw0Q7LQDOWmTjQ1GZVM5pCel6VlYP2iqUSh6L648xAIOBa2T37Icc1SdBF7ypZrtJ8DV9rnw" />
 </picture>
</a>

<p align="center">
  <a href="https://www.star-history.com/#Azek431/hzzs&Date">
    <img src="docs/assets/star-history.svg" alt="Star History" width="800" />
  </a>
</p>

仓库地址：

- GitHub：<https://github.com/Azek431/hzzs>
- Gitee：<https://gitee.com/Azek431/hzzs>

## 许可证

见 [`LICENSE`](LICENSE)。
