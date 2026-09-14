# service/capture — 截图后端

把系统截图能力抽象为统一 `FrameSource`，供 `VisionRuntimeController` 完成驱动取帧。**AUTO 仅走 MediaProjection，永不升权**。

## 职责

- `FrameSource` 抽象 + `CaptureState` 状态机（Idle / RequestingPermission / Ready / Failed）。
- `CapturedFrame`：单帧像素租约，**分析完必须 close**，禁止跨帧保存底层缓冲引用。
- `data/jinchan/frame/JinChanFrameBridge`：H1 只读、零拷贝适配；统一负责 HZZS source 与 3120×1440 JinChan canonical 像素边界坐标互转，不注册 ROI、不做识别或动作。
- `IntFramePool`：有界、分代感知的 Int 像素池（分辨率变化递增 generation，旧租约不得回池）。
- `PlaneRgbaReader` / `FrameSequencer`：Image→ARGB 拷贝与单调帧序号。
- 五个后端：`AutoFrameSource` / `MediaProjectionFrameSource` / `AccessibilityFrameSource` / `ShizukuFrameSource` / `RootFrameSource`。

## 入口

1. `FrameCapture.kt` — 帧租约、状态机、`FrameSource`、像素池与帧序号等基础契约。
2. `CaptureSources.kt` — `FrameSourceFactory` 与五个截图后端（主要平台实现，改权限/旋转/资源释放必读）。
3. `data/vision/VisionRuntimeController`（主消费者）、`platform/compat/CaptureCapabilities`（能力探测）、`service/automation/ShellProcessSupport`（Shizuku/Root 进程通道同源）。

## 数据流

```text
VisionRuntimeController.start
  → FrameSourceFactory.source(backend)
  → source.start() → source.nextFrame(afterSequence) → CapturedFrame（use{ } 内借用）
  → source.stop()
```

## 不变量 / 安全 / 线程 / 坐标

- **AUTO 安全不变量（硬性）**：`AutoFrameSource` 仅委托 `MediaProjectionFrameSource`，不探测/启用无障碍/Shizuku/Root。高级后端必须用户显式选择，配置导入与迁移不得静默升权。
- 像素租约：`CapturedFrame` 刻意**不是** data class（防同一缓冲二次归还）；`close()` 幂等；`IntFramePool` 容量耗尽时 `tryAcquire` 返回 null → 丢帧而非阻塞。
- 尺寸边界：`MAX_FRAME_DIMENSION=4096`、`MAX_FRAME_PIXELS≈8MP`，越界丢弃。
- 旋转：同一 MediaProjection token 上 resize + 换 surface，避免 Android 14+ 二次 VirtualDisplay。
- 授权卡住：`REQUEST_PERMISSION_STUCK_MS=45_000ms` 后允许 start 重入。
- 各后端节流：无障碍 140ms、Shizuku 120ms、Root 250ms；无障碍截图 1500ms 回调超时。
- Shizuku/Root：stdout/stderr 限长（32MB/64KB）、超时 destroy、失败返回 null；进程通道与手势同源 `ShellProcessSupport`。
- 线程：Image 回调在专用 `HandlerThread`（DISPLAY 优先级）；状态与通道可跨线程；未投递帧在 Channel 回调中 close。
- 坐标：截图输出为像素缓冲，**不做归一化**；归一化在 domain/vision 与绘制层。
- JinChan canonical 坐标是隔离的像素边界坐标协议；仅允许经 `JinChanCanonicalFrame` 双向换算，方向或旋转无法可靠解释时 fail-closed。

## 改这个包前必读

- 改 `CapturedFrame` / `IntFramePool`：同步 `VisionRuntimeController.runLoop` 的 `frame.use { }` 租约语义与 `DebugFrameRecorder.offer` 的「close 前复制」约束。
- 改 `MediaProjectionFrameSource`：同步 `CapturePermissionActivity`（授权 UI）与 `MediaProjectionCaptureService`（前台服务持有 VirtualDisplay）。
- 改 `AccessibilityFrameSource`：仅在用户显式选择且服务已连接时就绪；AUTO 路径永不启用；API 30+。
- 改 `ShizukuFrameSource` / `RootFrameSource`：AUTO 永不探测 Root；`RootFrameSource.start` 用 `su -c id` 探测，失败 fail-closed。
- 改 `PlaneRgbaReader`：使用 ThreadLocal 行缓冲避免每帧分配；算术先扩宽再做边界检查防溢出。
- 截图后端与手势后端**正交**（`CaptureBackend` 与 `GestureBackend` 独立解析）。

## 测试

- 相关测试：`CaptureBackendResolutionTest`、`FrameSequenceTest`。
- 门禁：`python tools/quality/check_resources.py`、`python tools/quality/check_project.py`、`:app:testDebugUnitTest`。
- 真机验证：MediaProjection 授权、旋转、空帧、超时、资源释放；API 24/26/29/30/33/34+ 分支。
