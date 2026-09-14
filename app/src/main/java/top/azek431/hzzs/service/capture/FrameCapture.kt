package top.azek431.hzzs.service.capture

import android.media.Image
import android.os.SystemClock
import kotlinx.coroutines.flow.StateFlow
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 单帧像素租约：分析完成后必须 [close]，以便缓冲回池复用。
 *
 * 所有权与安全不变量：
 * - 持有可复用 [pixels] 缓冲，禁止跨帧保存底层数组引用。
 * - 刻意不是 data class：拷贝同一租约会把同一缓冲二次归还。
 * - [close] 幂等；仅首次关闭触发 [releaseLease]。
 * - 尺寸与像素数在构造时边界校验，防止异常帧撑爆内存。
 */
class CapturedFrame(
    val sequence: Long,
    val elapsedRealtimeNanos: Long,
    val width: Int,
    val height: Int,
    val pixels: IntArray,
    /** Clockwise rotation needed to display [pixels] upright. Capture backends currently emit 0. */
    val rotationDegrees: Int = 0,
    private val releaseLease: (() -> Unit)? = null,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    init {
        require(sequence >= 0)
        require(width in 1..MAX_CAPTURE_FRAME_DIMENSION && height in 1..MAX_CAPTURE_FRAME_DIMENSION)
        val pixelCount = width.toLong() * height.toLong()
        require(pixelCount <= MAX_CAPTURE_FRAME_PIXELS)
        require(pixelCount == pixels.size.toLong())
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) releaseLease?.invoke()
    }
}

/** 截图源对外状态机：空闲 / 申请授权 / 就绪 / 失败。 */
sealed interface CaptureState {
    data object Idle : CaptureState
    data object RequestingPermission : CaptureState
    data object Ready : CaptureState
    data class Failed(val message: String, val recoverable: Boolean) : CaptureState
}

/**
 * 帧源抽象：生命周期由调用方驱动。
 * [nextFrame] 返回的 [CapturedFrame] 由调用方负责 close，超时/跳过的旧帧必须就地释放。
 */
interface FrameSource {
    val state: StateFlow<CaptureState>
    suspend fun start()
    suspend fun nextFrame(afterSequence: Long): CapturedFrame?
    suspend fun stop()
}

/**
 * 有界、分代感知的 Int 像素池。
 *
 * - 分辨率变化会递增 generation，旧租约关闭后不得回池。
 * - 容量耗尽时 [tryAcquire] 返回 null，调用方应丢帧而非阻塞。
 * - [Lease] 与 [CapturedFrame] 同样要求 close 归还。
 */
class IntFramePool(private val capacity: Int = 3) {
    init { require(capacity >= 2) }

    private data class Buffer(val pixels: IntArray, val generation: Long)

    private val free = ArrayDeque<Buffer>()
    private var allocated = 0
    private var activeSize = 0
    private var generation = 0L

    /** 池缓冲租约；关闭后按 generation 条件回池。 */
    class Lease internal constructor(
        val pixels: IntArray,
        private val releaseBlock: () -> Unit,
    ) : AutoCloseable {
        private val closed = AtomicBoolean(false)
        override fun close() {
            if (closed.compareAndSet(false, true)) releaseBlock()
        }
    }

    @Synchronized
    fun tryAcquire(size: Int): Lease? {
        require(size in 1..MAX_CAPTURE_FRAME_PIXELS.toInt())
        if (activeSize != size) {
            free.clear()
            activeSize = size
            allocated = 0
            generation++
        }

        val currentGeneration = generation
        val buffer = when {
            free.isNotEmpty() -> free.removeFirst()
            allocated < capacity -> Buffer(IntArray(size), currentGeneration).also { allocated++ }
            else -> return null
        }
        return Lease(buffer.pixels) {
            release(buffer, size, currentGeneration)
        }
    }

    @Synchronized
    private fun release(buffer: Buffer, size: Int, leaseGeneration: Long) {
        if (
            leaseGeneration == generation &&
            buffer.generation == generation &&
            size == activeSize &&
            buffer.pixels.size == activeSize &&
            free.size < capacity
        ) {
            free.addLast(buffer)
        }
    }
}

/**
 * 按行拷贝 RGBA_8888 [Image] 平面到 ARGB Int 缓冲。
 *
 * 线程：可在截图工作线程调用；使用 ThreadLocal 行缓冲避免每帧分配。
 * 不假设最后一行含尾部 padding；算术先扩宽再做边界检查，防止溢出。
 */
object PlaneRgbaReader {
    private val rowScratch = ThreadLocal<ByteArray>()

    fun copy(image: Image, output: IntArray) {
        require(image.width > 0 && image.height > 0)
        val pixelCount = image.width.toLong() * image.height.toLong()
        require(pixelCount <= output.size.toLong()) { "Output buffer too small" }

        val plane = image.planes.firstOrNull() ?: error("Image has no planes")
        require(plane.pixelStride >= 4) { "Unsupported pixelStride=${plane.pixelStride}" }
        val buffer = plane.buffer.duplicate()
        val base = buffer.position().toLong()
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val rowBytesLong = image.width.toLong() * pixelStride.toLong()
        require(rowStride.toLong() >= rowBytesLong)
        require(rowBytesLong in 1..Int.MAX_VALUE.toLong()) { "Plane row size overflow" }
        val rowBytes = rowBytesLong.toInt()
        var scratch = rowScratch.get()
        if (scratch == null || scratch.size < rowBytes) {
            scratch = ByteArray(rowBytes)
            rowScratch.set(scratch)
        }

        var out = 0
        for (y in 0 until image.height) {
            val rowStartLong = base + y.toLong() * rowStride.toLong()
            require(rowStartLong >= 0 && rowStartLong + rowBytesLong <= buffer.limit().toLong()) {
                "Plane truncated at y=$y limit=${buffer.limit()}"
            }
            require(rowStartLong <= Int.MAX_VALUE.toLong()) { "Plane offset overflow" }
            buffer.position(rowStartLong.toInt())
            buffer.get(scratch, 0, rowBytes)
            var offset = 0
            repeat(image.width) {
                val r = scratch[offset].toInt() and 0xFF
                val g = scratch[offset + 1].toInt() and 0xFF
                val b = scratch[offset + 2].toInt() and 0xFF
                val a = scratch[offset + 3].toInt() and 0xFF
                output[out++] = (a shl 24) or (r shl 16) or (g shl 8) or b
                offset += pixelStride
            }
        }
    }
}

/** 单调递增帧序号与 elapsedRealtime 时间戳生成器。 */
class FrameSequencer(
    private val clockNanos: () -> Long = SystemClock::elapsedRealtimeNanos,
) {
    private val counter = AtomicLong(0)

    fun next(): Pair<Long, Long> = counter.incrementAndGet() to clockNanos()
}

private const val MAX_CAPTURE_FRAME_DIMENSION = 4_096
private const val MAX_CAPTURE_FRAME_PIXELS = 8_388_608L
