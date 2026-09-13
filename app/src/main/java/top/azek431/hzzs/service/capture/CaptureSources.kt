package top.azek431.hzzs.service.capture

import android.app.Activity
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.HardwareBuffer
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Process as AndroidProcess
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.ByteArrayOutputStream
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import top.azek431.hzzs.R
import top.azek431.hzzs.core.model.CaptureBackend
import top.azek431.hzzs.service.automation.HzzsAccessibilityService
import top.azek431.hzzs.service.automation.ShellProcessSupport

/** 按 [CaptureBackend] 解析具体帧源实现。 */
interface FrameSourceFactory {
    fun source(backend: CaptureBackend): FrameSource
}

/**
 * 默认帧源工厂：将枚举映射到各后端单例。
 * 安全不变量：工厂本身不做能力探测或权限请求。
 */
@Singleton
class DefaultFrameSourceFactory @Inject constructor(
    private val automatic: AutoFrameSource,
    private val mediaProjection: MediaProjectionFrameSource,
    private val accessibility: AccessibilityFrameSource,
    private val shizuku: ShizukuFrameSource,
    private val root: RootFrameSource,
) : FrameSourceFactory {
    override fun source(backend: CaptureBackend): FrameSource = when (backend) {
        CaptureBackend.AUTO -> automatic
        CaptureBackend.MEDIA_PROJECTION -> mediaProjection
        CaptureBackend.ACCESSIBILITY -> accessibility
        CaptureBackend.SHIZUKU -> shizuku
        CaptureBackend.ROOT -> root
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class CaptureBindings {
    @Binds
    abstract fun bindFactory(impl: DefaultFrameSourceFactory): FrameSourceFactory
}

/**
 * AUTO 路径：仅委托 [MediaProjectionFrameSource]。
 *
 * 安全不变量（硬性）：
 * - 只走低权限公开 MediaProjection，不探测、不启用无障碍 / Shizuku / Root。
 * - 高级后端必须由用户显式选择，配置导入与迁移不得静默升权。
 */
@Singleton
class AutoFrameSource @Inject constructor(
    private val mediaProjection: MediaProjectionFrameSource,
) : FrameSource by mediaProjection

/**
 * MediaProjection 持续帧源。
 *
 * 职责：申请系统录屏授权、前台服务保活、VirtualDisplay + ImageReader 产出 [CapturedFrame]。
 * 线程：Image 回调在专用 HandlerThread；状态与通道可跨线程；未投递帧在 Channel 回调中 close。
 * 所有权：池租约绑定到帧 close；stop/失败时排空通道释放缓冲。
 */
@Singleton
class MediaProjectionFrameSource @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : FrameSource {
    private val mutableState = MutableStateFlow<CaptureState>(CaptureState.Idle)
    override val state: StateFlow<CaptureState> = mutableState
    private val frames = Channel<CapturedFrame>(
        capacity = Channel.CONFLATED,
        onUndeliveredElement = CapturedFrame::close,
    )
    private val pool = IntFramePool(3)
    private val sequencer = FrameSequencer()

    @Volatile
    private var requestingPermissionSinceElapsedMs: Long = 0L

    override suspend fun start() {
        val current = state.value
        if (current == CaptureState.Ready) return
        if (current == CaptureState.RequestingPermission) {
            // 授权对话框卡住/后台限制未回调时允许超时重入，避免永久 RequestingPermission。
            val elapsed = SystemClock.elapsedRealtime() - requestingPermissionSinceElapsedMs
            if (requestingPermissionSinceElapsedMs > 0L &&
                elapsed in 1 until REQUEST_PERMISSION_STUCK_MS
            ) {
                return
            }
            fail("屏幕捕获授权超时或未完成，请重试", recoverable = true)
        }
        mutableState.value = CaptureState.RequestingPermission
        requestingPermissionSinceElapsedMs = SystemClock.elapsedRealtime()
        context.startActivity(
            Intent(context, CapturePermissionActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    internal fun accept(image: Image) {
        val size = safePixelCount(image.width, image.height)
        if (size == null) {
            fail("屏幕尺寸无效：${image.width}×${image.height}", recoverable = false)
            return
        }
        val lease = pool.tryAcquire(size) ?: return
        try {
            PlaneRgbaReader.copy(image, lease.pixels)
            val (sequence, timestamp) = sequencer.next()
            val frame = CapturedFrame(
                sequence = sequence,
                elapsedRealtimeNanos = timestamp,
                width = image.width,
                height = image.height,
                pixels = lease.pixels,
                releaseLease = lease::close,
            )
            if (frames.trySend(frame).isFailure) frame.close()
        } catch (error: Exception) {
            lease.close()
            fail("屏幕帧读取失败：${error.message}", recoverable = true)
        }
    }

    internal fun ready() {
        requestingPermissionSinceElapsedMs = 0L
        mutableState.value = CaptureState.Ready
    }

    internal fun fail(message: String, recoverable: Boolean) {
        // 失败态与缓冲生命周期对齐，避免下一轮 nextFrame 仍吐出陈旧帧。
        requestingPermissionSinceElapsedMs = 0L
        drainFrames()
        mutableState.value = CaptureState.Failed(message, recoverable)
    }

    internal fun idle() {
        requestingPermissionSinceElapsedMs = 0L
        drainFrames()
        mutableState.value = CaptureState.Idle
    }

    override suspend fun nextFrame(afterSequence: Long): CapturedFrame? = withTimeoutOrNull(1_200L) {
        while (true) {
            val frame = frames.receive()
            if (frame.sequence > afterSequence) return@withTimeoutOrNull frame
            frame.close()
        }
        @Suppress("UNREACHABLE_CODE")
        null
    }

    override suspend fun stop() {
        runCatching {
            context.startService(
                Intent(context, MediaProjectionCaptureService::class.java)
                    .setAction(MediaProjectionCaptureService.ACTION_STOP),
            )
        }
        drainFrames()
        idle()
    }

    private fun drainFrames() {
        while (true) frames.tryReceive().getOrNull()?.close() ?: break
    }

    private companion object {
        /** 授权对话框卡住超过此时长后允许 start 重入。 */
        const val REQUEST_PERMISSION_STUCK_MS = 45_000L
    }
}

/**
 * 一次性录屏授权 Activity。
 * 职责：拉起系统 MediaProjection 授权 UI；成功则启动前台捕获服务，取消则回写可恢复失败。
 */
@AndroidEntryPoint
class CapturePermissionActivity : ComponentActivity() {
    @Inject lateinit var source: MediaProjectionFrameSource
    private var launched = false

    private val captureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        handleCaptureResult(result.resultCode, result.data)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        launched = savedInstanceState?.getBoolean(KEY_LAUNCHED) == true
        if (!launched) {
            launched = true
            val manager = getSystemService(MediaProjectionManager::class.java)
            captureLauncher.launch(manager.createScreenCaptureIntent())
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(KEY_LAUNCHED, launched)
        super.onSaveInstanceState(outState)
    }

    private fun handleCaptureResult(resultCode: Int, data: Intent?) {
        if (resultCode == RESULT_OK && data != null) {
            val service = Intent(this, MediaProjectionCaptureService::class.java)
                .setAction(MediaProjectionCaptureService.ACTION_START)
                .putExtra(MediaProjectionCaptureService.EXTRA_RESULT_CODE, resultCode)
                .putExtra(MediaProjectionCaptureService.EXTRA_RESULT_DATA, data)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(service) else startService(service)
        } else {
            source.fail("用户取消了屏幕捕获授权", recoverable = true)
        }
        finish()
    }

    companion object {
        private const val KEY_LAUNCHED = "launched"
    }
}

/**
 * MediaProjection 前台服务：持有 VirtualDisplay / ImageReader 会话。
 *
 * 线程：DISPLAY 优先级 HandlerThread 处理 onImageAvailable；主线程注册 projection 回调。
 * 旋转：同一 projection token 上 resize + 换 surface，避免 Android 14+ 二次 VirtualDisplay。
 * 安全：未知/空 Intent 直接 stopSelf 失败关闭；通知中可一键停止捕获。
 */
@AndroidEntryPoint
class MediaProjectionCaptureService : Service() {
    @Inject lateinit var source: MediaProjectionFrameSource

    private var projection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var thread: HandlerThread? = null
    private var workerHandler: Handler? = null
    private var stopping = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startCapture(intent)
            ACTION_STOP -> cleanup(updateStateToIdle = true, stopProjection = true, stopService = true)
            else -> stopSelf() // 敏感服务对空/未知 Intent 失败关闭
        }
        return START_NOT_STICKY
    }

    private fun startCapture(intent: Intent) {
        // 允许 stop 后在同一 Service 实例上再次 START；旧 stopping 闩若残留会永久空转。
        // 若上一轮 projection 尚未释放（STOP 竞态），先清理再挂新 token，禁止静默 return。
        if (projection != null) {
            cleanup(updateStateToIdle = false, stopProjection = true, stopService = false)
            stopping = false
        }
        stopping = false
        createChannel()
        startForegroundCapture()

        val code = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        @Suppress("DEPRECATION")
        val data = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        if (code != Activity.RESULT_OK || data == null) {
            source.fail("屏幕捕获授权无效", false)
            cleanup(updateStateToIdle = false, stopProjection = false, stopService = true)
            return
        }

        val manager = getSystemService(MediaProjectionManager::class.java)
        val newProjection = manager.getMediaProjection(code, data)
        if (newProjection == null) {
            source.fail("无法创建屏幕捕获会话", true)
            cleanup(updateStateToIdle = false, stopProjection = false, stopService = true)
            return
        }

        val callback = object : MediaProjection.Callback() {
            override fun onStop() {
                if (stopping) return
                source.fail("系统已终止屏幕捕获", true)
                cleanup(updateStateToIdle = false, stopProjection = false, stopService = true)
            }
        }
        projection = newProjection
        projectionCallback = callback
        newProjection.registerCallback(callback, Handler(Looper.getMainLooper()))

        runCatching {
            val worker = HandlerThread("hzzs-capture", AndroidProcess.THREAD_PRIORITY_DISPLAY).also { it.start() }
            thread = worker
            workerHandler = Handler(worker.looper)
            createOrResizeDisplay(newProjection)
            source.ready()
        }.onFailure { error ->
            source.fail("屏幕捕获初始化失败：${error.message}", recoverable = true)
            cleanup(updateStateToIdle = false, stopProjection = true, stopService = true)
        }
    }


    /**
     * 保持同一 MediaProjection / VirtualDisplay 会话，仅在旋转或显示尺寸变化时替换 ImageReader surface。
     * Android 14+ 将 projection token 视为单次捕获会话，禁止用同一 token 再创建第二个 VirtualDisplay。
     */
    private fun createOrResizeDisplay(activeProjection: MediaProjection) {
        val metrics = resources.displayMetrics
        require(safePixelCount(metrics.widthPixels, metrics.heightPixels) != null) {
            "屏幕尺寸无效：${metrics.widthPixels}×${metrics.heightPixels}"
        }
        val handler = requireNotNull(workerHandler) { "截图线程尚未就绪" }
        val replacement = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ImageReader.newInstance(
                metrics.widthPixels,
                metrics.heightPixels,
                PixelFormat.RGBA_8888,
                3,
                HardwareBuffer.USAGE_CPU_READ_OFTEN,
            )
        } else {
            ImageReader.newInstance(
                metrics.widthPixels,
                metrics.heightPixels,
                PixelFormat.RGBA_8888,
                3,
            )
        }
        replacement.setOnImageAvailableListener({ availableReader ->
            availableReader.acquireLatestImage()?.use(source::accept)
        }, handler)

        val existing = display
        if (existing == null) {
            display = requireNotNull(
                activeProjection.createVirtualDisplay(
                    "HZZS-Vision",
                    metrics.widthPixels,
                    metrics.heightPixels,
                    metrics.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    replacement.surface,
                    null,
                    handler,
                ),
            ) { "无法创建虚拟显示" }
        } else {
            existing.resize(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)
            existing.setSurface(replacement.surface)
        }

        val previous = reader
        reader = replacement
        previous?.setOnImageAvailableListener(null, null)
        // createOrResizeDisplay 在 capture worker thread 上运行；
        // 因此旧 reader 的 listener 回调与 close 串行，避免读取中的 Image
        // 被另一线程关闭后触发 "buffer is inaccessible"。
        previous?.close()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val activeProjection = projection ?: return
        if (stopping) return
        val handler = workerHandler ?: return

        handler.post {
            if (stopping || projection !== activeProjection) return@post
            runCatching { createOrResizeDisplay(activeProjection) }
                .onFailure { error ->
                    Handler(Looper.getMainLooper()).post {
                        if (stopping) return@post
                        source.fail("屏幕方向变化后重建截图表面失败：${error.message}", true)
                        cleanup(updateStateToIdle = false, stopProjection = true, stopService = true)
                    }
                }
        }
    }

    private fun cleanup(
        updateStateToIdle: Boolean,
        stopProjection: Boolean,
        stopService: Boolean,
    ) {
        if (stopping) return
        stopping = true

        val localProjection = projection
        val localCallback = projectionCallback
        projection = null
        projectionCallback = null

        display?.release()
        display = null
        reader?.setOnImageAvailableListener(null, null)
        reader?.close()
        reader = null
        workerHandler = null
        thread?.quitSafely()
        thread = null

        if (localProjection != null && localCallback != null) {
            runCatching { localProjection.unregisterCallback(localCallback) }
        }
        if (stopProjection) runCatching { localProjection?.stop() }
        if (updateStateToIdle) source.idle()

        stopForeground(STOP_FOREGROUND_REMOVE)
        if (stopService) stopSelf()
    }

    override fun onDestroy() {
        if (!stopping) {
            source.fail("屏幕捕获服务已结束", true)
            cleanup(updateStateToIdle = false, stopProjection = true, stopService = false)
        }
        super.onDestroy()
    }

    private fun startForegroundCapture() {
        val notification = notification()
        // targetSdk 34+ 必须在 startForeground 传入 type，否则 MissingForegroundServiceTypeException。
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "屏幕视觉分析",
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
    }

    private fun notification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_splash_flame)
        .setContentTitle("火崽崽视觉分析")
        .setContentText("正在本机读取屏幕帧，不会上传截图")
        .setOngoing(true)
        .addAction(
            0,
            "全部停止",
            PendingIntent.getService(
                this,
                1,
                Intent(this, MediaProjectionCaptureService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        .build()

    companion object {
        const val ACTION_START = "top.azek431.hzzs.capture.START"
        const val ACTION_STOP = "top.azek431.hzzs.capture.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val CHANNEL_ID = "vision_capture"
        private const val NOTIFICATION_ID = 431
    }
}

/**
 * 无障碍截图后端（Android 11+）。
 * 安全：仅在用户显式选择且服务已连接时就绪；AUTO 路径永不启用。
 * 线程：回调在主线程协调，像素拷贝在调用方协程；Bitmap 必须 recycle。
 */
@Singleton
class AccessibilityFrameSource @Inject constructor() : FrameSource {
    private val mutableState = MutableStateFlow<CaptureState>(CaptureState.Idle)
    override val state: StateFlow<CaptureState> = mutableState
    private val sequencer = FrameSequencer()
    private val pool = IntFramePool(3)
    private var lastCaptureAtNanos = 0L

    override suspend fun start() {
        mutableState.value = if (Build.VERSION.SDK_INT < 30) {
            CaptureState.Failed("无障碍截图需要 Android 11 或更高版本", false)
        } else if (!HzzsAccessibilityService.isConnected()) {
            CaptureState.Failed("请先启用火崽崽无障碍服务", true)
        } else {
            CaptureState.Ready
        }
    }

    override suspend fun nextFrame(afterSequence: Long): CapturedFrame? {
        if (state.value != CaptureState.Ready || Build.VERSION.SDK_INT < 30) return null
        if (!HzzsAccessibilityService.isConnected()) {
            mutableState.value = CaptureState.Failed("请先启用火崽崽无障碍服务", true)
            return null
        }
        throttle(MIN_ACCESSIBILITY_CAPTURE_INTERVAL_MS, lastCaptureAtNanos)
        lastCaptureAtNanos = SystemClock.elapsedRealtimeNanos()
        val bitmap = withTimeoutOrNull(ACCESSIBILITY_CALLBACK_TIMEOUT_MS) {
            HzzsAccessibilityService.captureBitmap()
        }
        if (bitmap == null) {
            delay(80L)
            return null
        }
        return try {
            val size = safePixelCount(bitmap.width, bitmap.height) ?: return null
            val lease = pool.tryAcquire(size) ?: return null
            try {
                bitmap.getPixels(lease.pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                val (sequence, timestamp) = sequencer.next()
                if (sequence <= afterSequence) {
                    lease.close()
                    null
                } else {
                    CapturedFrame(
                        sequence,
                        timestamp,
                        bitmap.width,
                        bitmap.height,
                        lease.pixels,
                        releaseLease = { lease.close() },
                    )
                }
            } catch (error: Throwable) {
                lease.close()
                throw error
            }
        } finally {
            bitmap.recycle()
        }
    }

    override suspend fun stop() {
        mutableState.value = CaptureState.Idle
    }
}


/**
 * 通过 Shizuku 执行 `screencap -p` 的可选截图后端。
 * 不会在 AUTO 路径启用；需要用户显式选择，并已安装/授权 Shizuku。
 * 输出字节数有上限；失败 fail-closed 返回 null。
 */
@Singleton
class ShizukuFrameSource @Inject constructor() : FrameSource {
    private val mutableState = MutableStateFlow<CaptureState>(CaptureState.Idle)
    override val state: StateFlow<CaptureState> = mutableState
    private val sequencer = FrameSequencer()
    private val pool = IntFramePool(3)
    private var lastCaptureAtNanos = 0L
    private val permissionLock = Any()
    private var permissionListener: Shizuku.OnRequestPermissionResultListener? = null

    override suspend fun start() {
        val ready = ensureShizukuPermission()
        mutableState.value = if (ready) {
            CaptureState.Ready
        } else {
            CaptureState.Failed(
                "Shizuku 不可用：请安装并启动 Shizuku，并授予本应用权限。",
                true,
            )
        }
    }

    override suspend fun nextFrame(afterSequence: Long): CapturedFrame? {
        if (state.value != CaptureState.Ready) return null
        if (!isShizukuAuthorized()) {
            mutableState.value = CaptureState.Failed("Shizuku 权限已失效", true)
            return null
        }
        throttle(MIN_SHIZUKU_CAPTURE_INTERVAL_MS, lastCaptureAtNanos)
        lastCaptureAtNanos = SystemClock.elapsedRealtimeNanos()
        val bytes = runShizukuScreencap() ?: return null
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        return try {
            val size = safePixelCount(bitmap.width, bitmap.height) ?: return null
            val lease = pool.tryAcquire(size) ?: return null
            try {
                bitmap.getPixels(lease.pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                val (sequence, timestamp) = sequencer.next()
                if (sequence <= afterSequence) {
                    lease.close()
                    null
                } else {
                    CapturedFrame(
                        sequence,
                        timestamp,
                        bitmap.width,
                        bitmap.height,
                        lease.pixels,
                        releaseLease = { lease.close() },
                    )
                }
            } catch (error: Throwable) {
                lease.close()
                throw error
            }
        } finally {
            bitmap.recycle()
        }
    }

    override suspend fun stop() {
        clearPermissionListener()
        mutableState.value = CaptureState.Idle
    }

    private suspend fun ensureShizukuPermission(): Boolean = withContext(Dispatchers.Main) {
        if (!runCatching { Shizuku.pingBinder() }.getOrDefault(false)) return@withContext false
        if (isShizukuAuthorized()) return@withContext true
        if (Shizuku.isPreV11()) return@withContext false
        val deferred = CompletableDeferred<Boolean>()
        val listener = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
            deferred.complete(grantResult == PackageManager.PERMISSION_GRANTED)
        }
        synchronized(permissionLock) {
            clearPermissionListener()
            permissionListener = listener
            Shizuku.addRequestPermissionResultListener(listener)
        }
        return@withContext try {
            Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE)
            withTimeoutOrNull(15_000L) { deferred.await() } == true
        } catch (_: Throwable) {
            false
        } finally {
            clearPermissionListener()
        }
    }

    private fun clearPermissionListener() {
        synchronized(permissionLock) {
            permissionListener?.let { Shizuku.removeRequestPermissionResultListener(it) }
            permissionListener = null
        }
    }

    private fun isShizukuAuthorized(): Boolean = runCatching {
        Shizuku.pingBinder() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /**
     * 截屏：优先绝对路径 screencap（PATH 空），失败再试裸命令。
     * 进程通道与手势同源 [ShellProcessSupport.openShizukuProcess]。
     */
    private suspend fun runShizukuScreencap(): ByteArray? = withContext(Dispatchers.IO) {
        val candidates = listOf(
            arrayOf("/system/bin/screencap", "-p"),
            arrayOf("screencap", "-p"),
        )
        for (command in candidates) {
            val process = ShellProcessSupport.openShizukuProcess(command) ?: continue
            val bytes = runCatching {
                readProcessBytes(process, MAX_SCREENSHOT_BYTES, 4_000L)
            }.getOrNull()
            if (bytes != null && bytes.isNotEmpty()) return@withContext bytes
        }
        null
    }

    private suspend fun readProcessBytes(
        process: java.lang.Process,
        maxBytes: Int,
        timeoutMs: Long,
    ): ByteArray? {
        try {
            return coroutineScope {
                val stdout = async(Dispatchers.IO) {
                    runCatching { readLimitedBytes(process.inputStream, maxBytes) }
                        .onFailure { process.destroyCompatLocal() }
                        .getOrNull()
                }
                val stderr = async(Dispatchers.IO) {
                    runCatching { readLimitedBytes(process.errorStream, MAX_ROOT_STDERR_BYTES) }
                        .onFailure { process.destroyCompatLocal() }
                        .getOrNull()
                }
                val exited = waitForExit(process, timeoutMs)
                if (!exited) process.destroyCompatLocal()
                val streams = withTimeoutOrNull(1_000L) { stdout.await() to stderr.await() }
                val exitCode = if (exited) runCatching { process.exitValue() }.getOrNull() else null
                if (exitCode != 0 || streams == null) null else streams.first
            }
        } finally {
            process.destroyCompatLocal()
        }
    }

    private fun readLimitedBytes(input: InputStream, maxBytes: Int): ByteArray {
        input.use { stream ->
            val output = ByteArrayOutputStream(minOf(maxBytes, 1024 * 1024))
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                if (output.size().toLong() + count.toLong() > maxBytes.toLong()) {
                    throw IllegalStateException("Shizuku command output exceeds $maxBytes bytes")
                }
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        }
    }

    private fun java.lang.Process.destroyCompatLocal() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            runCatching { destroyForcibly() }
        } else {
            runCatching { destroy() }
        }
        runCatching { destroy() }
    }

    private companion object {
        const val SHIZUKU_PERMISSION_REQUEST_CODE = 0x53485A4B // 'SHZK'
        const val MIN_SHIZUKU_CAPTURE_INTERVAL_MS = 120L
        const val MAX_SCREENSHOT_BYTES = 32 * 1024 * 1024
        const val MAX_ROOT_STDERR_BYTES = 64 * 1024
    }
}

/**
 * Root 实验后端：`su -c screencap -p`。
 * 安全：仅用户显式选择；AUTO 永不探测 Root。stdout/stderr 有字节上限与超时，超时强制销毁进程。
 */
@Singleton
class RootFrameSource @Inject constructor() : FrameSource {
    private val mutableState = MutableStateFlow<CaptureState>(CaptureState.Idle)
    override val state: StateFlow<CaptureState> = mutableState
    private val sequencer = FrameSequencer()
    private val pool = IntFramePool(2)
    private var lastCaptureAtNanos = 0L

    override suspend fun start() {
        mutableState.value = if (
            runCommand(listOf("su", "-c", "id"), 128 * 1024, 1_500)?.isNotEmpty() == true
        ) {
            CaptureState.Ready
        } else {
            CaptureState.Failed("Root 不可用或未授权", true)
        }
    }

    override suspend fun nextFrame(afterSequence: Long): CapturedFrame? {
        if (state.value != CaptureState.Ready) return null
        throttle(MIN_ROOT_CAPTURE_INTERVAL_MS, lastCaptureAtNanos)
        lastCaptureAtNanos = SystemClock.elapsedRealtimeNanos()
        val bytes = runCommand(
            listOf("su", "-c", "screencap -p"),
            MAX_SCREENSHOT_BYTES,
            4_000,
        ) ?: return null
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        return try {
            val size = safePixelCount(bitmap.width, bitmap.height) ?: return null
            val lease = pool.tryAcquire(size) ?: return null
            try {
                bitmap.getPixels(lease.pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                val (sequence, timestamp) = sequencer.next()
                if (sequence <= afterSequence) {
                    lease.close()
                    null
                } else {
                    CapturedFrame(
                        sequence,
                        timestamp,
                        bitmap.width,
                        bitmap.height,
                        lease.pixels,
                        releaseLease = { lease.close() },
                    )
                }
            } catch (error: Throwable) {
                lease.close()
                throw error
            }
        } finally {
            bitmap.recycle()
        }
    }

    override suspend fun stop() {
        mutableState.value = CaptureState.Idle
    }

    private suspend fun runCommand(
        command: List<String>,
        maxBytes: Int,
        timeoutMs: Long,
    ): ByteArray? = withContext(Dispatchers.IO) {
        require(maxBytes > 0)
        require(timeoutMs > 0)
        val process = runCatching {
            ProcessBuilder(command).start()
        }.getOrNull() ?: return@withContext null

        try {
            coroutineScope {
                val stdout = async(Dispatchers.IO) {
                    runCatching { readLimited(process.inputStream, maxBytes) }
                        .onFailure { process.destroyCompat() }
                        .getOrNull()
                }
                val stderr = async(Dispatchers.IO) {
                    runCatching { readLimited(process.errorStream, MAX_ROOT_STDERR_BYTES) }
                        .onFailure { process.destroyCompat() }
                        .getOrNull()
                }
                val exited = waitForExit(process, timeoutMs)
                if (!exited) process.destroyCompat()
                val streams = withTimeoutOrNull(1_000L) { stdout.await() to stderr.await() }
                val exitCode = if (exited) runCatching { process.exitValue() }.getOrNull() else null
                if (exitCode != 0 || streams == null) null else streams.first
            }
        } finally {
            process.destroyCompat()
        }
    }

    private fun readLimited(input: InputStream, maxBytes: Int): ByteArray {
        input.use { stream ->
            val output = ByteArrayOutputStream(minOf(maxBytes, 1024 * 1024))
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                if (output.size().toLong() + count.toLong() > maxBytes.toLong()) {
                    throw IllegalStateException("Root command output exceeds $maxBytes bytes")
                }
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        }
    }

    companion object {
        private const val MAX_SCREENSHOT_BYTES = 32 * 1024 * 1024
        private const val MAX_ROOT_STDERR_BYTES = 64 * 1024
    }
}


private suspend fun waitForExit(process: java.lang.Process, timeoutMs: Long): Boolean {
    val deadline = SystemClock.elapsedRealtime() + timeoutMs
    while (SystemClock.elapsedRealtime() < deadline) {
        if (runCatching { process.exitValue() }.isSuccess) return true
        delay(20L)
    }
    return runCatching { process.exitValue() }.isSuccess
}

/** Process.destroyForcibly() 在 Android 7.0/7.1 不可用，按 API 分支销毁。 */
private fun java.lang.Process.destroyCompat() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        destroyForcibly()
    } else {
        @Suppress("DEPRECATION")
        destroy()
    }
}

private suspend fun throttle(minimumIntervalMs: Long, lastCaptureAtNanos: Long) {
    if (lastCaptureAtNanos <= 0L) return
    val elapsedNanos = SystemClock.elapsedRealtimeNanos() - lastCaptureAtNanos
    val remainingNanos = minimumIntervalMs * 1_000_000L - elapsedNanos
    if (remainingNanos > 0L) delay((remainingNanos + 999_999L) / 1_000_000L)
}

/** 宽高与像素总量边界校验；越界返回 null 以丢弃异常帧。 */
private fun safePixelCount(width: Int, height: Int): Int? {
    if (width <= 0 || height <= 0 || width > MAX_FRAME_DIMENSION || height > MAX_FRAME_DIMENSION) return null
    val count = width.toLong() * height.toLong()
    return if (count in 1..MAX_FRAME_PIXELS) count.toInt() else null
}

private const val MAX_FRAME_DIMENSION = 4_096
private const val MAX_FRAME_PIXELS = 8_388_608L
private const val MIN_ACCESSIBILITY_CAPTURE_INTERVAL_MS = 140L
private const val ACCESSIBILITY_CALLBACK_TIMEOUT_MS = 1_500L
private const val MIN_ROOT_CAPTURE_INTERVAL_MS = 250L
