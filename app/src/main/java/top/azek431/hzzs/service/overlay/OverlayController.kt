package top.azek431.hzzs.service.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Choreographer
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import top.azek431.hzzs.core.model.OverlayBlockReason
import top.azek431.hzzs.core.model.OverlayConfig
import top.azek431.hzzs.core.model.OverlayTheme
import top.azek431.hzzs.core.model.RuntimeStatus
import top.azek431.hzzs.core.model.displayName
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 主线程持有的持久悬浮窗控制器（**呈现层**）。
 *
 * Clean Base：悬浮窗只呈现**运行时状态文本**（运行中 / 截图就绪 / FPS / 后端 /
 * 错误与拦截原因）。检测框、检测轮廓、诊断框等“算法结果可视化”已随
 * HZZS 原游戏视觉算法层一起清退，本类不再消费任何检测模型。
 *
 * 线程不变量：所有 WindowManager.add/update/remove 与 View 更新必须在主线程
 *（本类统一 [Dispatchers.Main.immediate]）。
 *
 * 安全：
 * - 默认关闭（[AppConfig.OVERLAY_DEFAULT_ENABLED] = false），需用户在设置页显式开启；
 * - 无悬浮窗权限或配置关闭时立即移除视图；add/update 失败 fail-closed 隐藏；
 * - [OverlayConfig.clickThrough] 默认 true，不拦截游戏手势。
 */
@Singleton
class OverlayController @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private var hudView: StatusOverlayView? = null
    private var hudParams: WindowManager.LayoutParams? = null
    private var currentConfig = OverlayConfig()
    private var positionX = 12
    private var positionY = 96

    /**
     * 显示或更新状态悬浮窗。
     *
     * @param runtimeStatus 当前运行时状态；null 时只显示静态标题行。
     * @return [OverlayShowResult]：是否挂载成功，以及失败原因（若有）。
     */
    suspend fun show(
        config: OverlayConfig,
        runtimeStatus: RuntimeStatus? = null,
    ): OverlayShowResult =
        withContext(Dispatchers.Main.immediate) {
            if (!config.enabled) {
                hideInternal()
                return@withContext OverlayShowResult(
                    visible = false,
                    blockReason = OverlayBlockReason.DISABLED,
                )
            }
            if (!Settings.canDrawOverlays(context)) {
                hideInternal()
                return@withContext OverlayShowResult(
                    visible = false,
                    blockReason = OverlayBlockReason.PERMISSION,
                )
            }

            currentConfig = config
            val params = createParams()
            val view = ensureHud(params)
                ?: run {
                    hideInternal()
                    return@withContext OverlayShowResult(
                        visible = false,
                        blockReason = OverlayBlockReason.ADD_VIEW_FAILED,
                    )
                }
            view.update(config, runtimeStatus)
            OverlayShowResult(visible = true, blockReason = null)
        }

    suspend fun hide() = withContext(Dispatchers.Main.immediate) { hideInternal() }

    /**
     * 截图前临时隐藏已挂载 HUD，但不移除 Window，避免每帧 add/remove 抖动。
     * 等待一次主显示帧提交，调用方随后再排空可能含旧合成层的捕获帧。
     */
    suspend fun suspendForCapture(): Boolean = withContext(Dispatchers.Main.immediate) {
        val view = hudView ?: return@withContext false
        if (view.visibility != View.VISIBLE) return@withContext false
        view.visibility = View.INVISIBLE
        awaitNextDisplayFrame()
        true
    }

    /** 输入缓冲取得后立即恢复上一轮 HUD；截图仍读取独立的干净像素缓冲。 */
    suspend fun resumeAfterCapture(): Boolean = withContext(Dispatchers.Main.immediate) {
        val view = hudView ?: return@withContext false
        view.visibility = View.VISIBLE
        view.invalidate()
        true
    }

    private suspend fun awaitNextDisplayFrame() {
        suspendCancellableCoroutine { continuation ->
            val choreographer = Choreographer.getInstance()
            val callback = Choreographer.FrameCallback {
                if (continuation.isActive) continuation.resume(Unit)
            }
            continuation.invokeOnCancellation { choreographer.removeFrameCallback(callback) }
            choreographer.postFrameCallback(callback)
        }
    }

    private fun ensureHud(params: WindowManager.LayoutParams): StatusOverlayView? {
        hudView?.let { existing ->
            val stored = hudParams
            if (stored != null && stored.layoutSignature() != params.layoutSignature()) {
                runCatching { windowManager.updateViewLayout(existing, params) }
                    .onFailure { return null }
            } else {
                runCatching { windowManager.updateViewLayout(existing, params) }
            }
            hudParams = params
            return existing
        }
        val created = StatusOverlayView(context) { deltaX, deltaY, released ->
            moveWindow(deltaX, deltaY, released)
        }
        return runCatching {
            windowManager.addView(created, params)
            hudView = created
            hudParams = params
            created
        }.getOrNull()
    }

    private fun hideInternal() {
        val view = hudView ?: return
        runCatching { windowManager.removeView(view) }
        hudView = null
        hudParams = null
    }

    private fun createParams(): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = positionX
            y = positionY
            if (currentConfig.clickThrough) {
                flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            }
        }

    private fun moveWindow(deltaX: Int, deltaY: Int, released: Boolean) {
        val view = hudView ?: return
        val params = hudParams ?: return
        if (currentConfig.lockPosition) return

        positionX += deltaX
        positionY += deltaY
        val bounds = context.resources.displayMetrics
        val maxX = max(0, bounds.widthPixels - view.width)
        val maxY = max(0, bounds.heightPixels - view.height)
        positionX = positionX.coerceIn(0, maxX)
        positionY = positionY.coerceIn(0, maxY)
        params.x = positionX
        params.y = positionY
        runCatching { windowManager.updateViewLayout(view, params) }
        if (released && currentConfig.snapToEdge) {
            val snapX = if (positionX + view.width / 2 < bounds.widthPixels / 2) 0 else max(0, maxX)
            if (snapX != positionX) {
                positionX = snapX
                params.x = snapX
                runCatching { windowManager.updateViewLayout(view, params) }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }

    private fun WindowManager.LayoutParams.layoutSignature(): List<Int> =
        listOf(x, y, width, height, flags, gravity)

    /** 悬浮窗挂载结果；[blockReason] 仅在期望显示但失败时非空。 */
    data class OverlayShowResult(
        val visible: Boolean,
        val blockReason: OverlayBlockReason?,
    )

    /**
     * 状态 HUD 视图：只画文本行，不画任何检测框。
     *
     * 触摸用于拖动（clickThrough 时窗口不接收触摸，走不到这里）。
     */
    private class StatusOverlayView(
        context: Context,
        private val onMove: (deltaX: Int, deltaY: Int, released: Boolean) -> Unit,
    ) : View(context) {
        private val density = resources.displayMetrics.density
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 12f * density
        }
        private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(178, 16, 20, 24)
        }
        private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(220, 32, 232, 155)
        }
        private val padding = (8f * density).roundToInt()
        private var lines: List<String> = emptyList()
        private var lastDownX = 0f
        private var lastDownY = 0f

        fun update(config: OverlayConfig, status: RuntimeStatus?) {
            textPaint.textSize = 12f * density * config.textScale.coerceIn(0.75f, 2f)
            backgroundPaint.alpha = (config.backgroundAlpha.coerceIn(0.1f, 1f) * 255).roundToInt()
                .coerceIn(0, 255)
            accentPaint.color = accentColor(config)
            lines = buildLines(config, status)
            requestLayout()
            invalidate()
        }

        private fun buildLines(config: OverlayConfig, status: RuntimeStatus?): List<String> {
            val out = mutableListOf<String>()
            out += "HZZS Clean Base"
            if (status == null) return out
            out += if (status.running) "运行中 · ${if (status.captureReady) "截图就绪" else "等待截图"}" else "已停止"
            out += "截图：${status.activeBackend.displayName()}"
            out += "手势：${status.activeGestureBackend.displayName()}"
            if (config.showFps) out += "FPS：%.1f".format(status.fps)
            status.overlayBlockReason?.let { out += "悬浮窗：${it.name}" }
            status.lastError?.let { out += "错误：$it" }
            out += "真实动作：禁用"
            return out
        }

        private fun accentColor(config: OverlayConfig): Int = when (config.theme) {
            OverlayTheme.CUSTOM -> config.customColor
            OverlayTheme.DARK_GLASS -> Color.rgb(0xE0, 0xE0, 0xE0)
            OverlayTheme.LIGHT_GLASS -> Color.rgb(0x20, 0x20, 0x20)
            OverlayTheme.AMOLED -> Color.WHITE
            else -> Color.argb(220, 32, 232, 155)
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val textWidth = lines.maxOfOrNull { textPaint.measureText(it) } ?: 0f
            val lineHeight = textPaint.textSize * 1.35f
            val desiredWidth = (textWidth + padding * 3).roundToInt()
            val desiredHeight = (lineHeight * lines.size + padding * 2).roundToInt()
            setMeasuredDimension(
                resolveSize(desiredWidth, widthMeasureSpec),
                resolveSize(desiredHeight, heightMeasureSpec),
            )
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val radius = 10f * density
            canvas.drawRoundRect(
                0f,
                0f,
                width.toFloat(),
                height.toFloat(),
                radius,
                radius,
                backgroundPaint,
            )
            val lineHeight = textPaint.textSize * 1.35f
            val textColor = readableTextColor()
            var y = padding + textPaint.textSize
            lines.forEachIndexed { index, line ->
                textPaint.color = if (index == 0) accentPaint.color else textColor
                canvas.drawText(line, padding * 1.5f, y, textPaint)
                y += lineHeight
            }
        }

        private fun readableTextColor(): Int =
            if (backgroundPaint.alpha > 128) Color.WHITE else Color.BLACK

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastDownX = event.rawX
                    lastDownY = event.rawY
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - lastDownX).roundToInt()
                    val dy = (event.rawY - lastDownY).roundToInt()
                    lastDownX = event.rawX
                    lastDownY = event.rawY
                    if (dx != 0 || dy != 0) onMove(dx, dy, false)
                    return true
                }
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    onMove(0, 0, true)
                    return true
                }
            }
            return super.onTouchEvent(event)
        }
    }
}
