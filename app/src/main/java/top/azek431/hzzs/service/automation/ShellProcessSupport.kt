package top.azek431.hzzs.service.automation

import android.content.pm.PackageManager
import android.os.SystemClock
import java.io.ByteArrayOutputStream
import java.io.InputStream

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku

/**
 * Shell 进程辅助（手势 / 截图 Shizuku·Root / 指针位置 共用）。
 *
 * 与 FrameSource 类边界分离，但进程通道同源；须为 public，供
 * `service.capture` 与 `platform.compat` 调用。
 *
 * 安全：stdout/stderr 限长、超时 destroy、失败返回 null；不静默要权。
 */
object ShellProcessSupport {
    fun isShizukuAuthorized(): Boolean = runCatching {
        Shizuku.pingBinder() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /**
     * Shizuku 13+ 将 `newProcess` 标为 private；反射调用，失败 null。
     */
    fun openShizukuProcess(command: Array<String>): Process? = runCatching {
        val method = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java,
        )
        method.isAccessible = true
        method.invoke(null, command, null, null) as Process
    }.getOrNull()

    fun openRootProcess(shellCommand: String): Process? = runCatching {
        ProcessBuilder("su", "-c", shellCommand).redirectErrorStream(false).start()
    }.getOrNull()

    suspend fun runProcess(
        process: Process,
        maxStdoutBytes: Int,
        timeoutMs: Long,
    ): String? = withContext(Dispatchers.IO) {
        try {
            coroutineScope {
                val stdout = async(Dispatchers.IO) {
                    runCatching { readLimited(process.inputStream, maxStdoutBytes) }
                        .onFailure { process.destroyCompat() }
                        .getOrNull()
                }
                val stderr = async(Dispatchers.IO) {
                    runCatching { readLimited(process.errorStream, MAX_STDERR_BYTES) }
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

    suspend fun runShizuku(
        command: Array<String>,
        maxStdoutBytes: Int,
        timeoutMs: Long,
    ): String? {
        if (!isShizukuAuthorized()) return null
        val process = openShizukuProcess(command) ?: return null
        return runProcess(process, maxStdoutBytes, timeoutMs)
    }

    suspend fun runRoot(
        shellCommand: String,
        maxStdoutBytes: Int,
        timeoutMs: Long,
    ): String? {
        val process = openRootProcess(shellCommand) ?: return null
        return runProcess(process, maxStdoutBytes, timeoutMs)
    }

    /**
     * 执行命令并要求 exit 0；stdout 可丢弃（input 类命令）。
     * @return true 表示成功退出
     */
    suspend fun runShizukuOk(command: Array<String>, timeoutMs: Long): Boolean =
        runShizukuResult(command, timeoutMs).ok

    /**
     * 带简短失败原因的 Shizuku 执行（供手势诊断）。
     * [ShellCommandResult.detail] 已截断，可进决策串，不含密钥。
     */
    suspend fun runShizukuResult(command: Array<String>, timeoutMs: Long): ShellCommandResult {
        if (!isShizukuAuthorized()) {
            return ShellCommandResult(ok = false, detail = "shizuku_unauthorized")
        }
        val process = openShizukuProcess(command)
            ?: return ShellCommandResult(ok = false, detail = "newProcess_null")
        return runProcessResult(process, timeoutMs)
    }

    suspend fun runRootOk(shellCommand: String, timeoutMs: Long): Boolean =
        runRootResult(shellCommand, timeoutMs).ok

    suspend fun runRootResult(shellCommand: String, timeoutMs: Long): ShellCommandResult {
        val process = openRootProcess(shellCommand)
            ?: return ShellCommandResult(ok = false, detail = "su_open_fail")
        return runProcessResult(process, timeoutMs)
    }

    private suspend fun runProcessOk(process: Process, timeoutMs: Long): Boolean =
        runProcessResult(process, timeoutMs).ok

    private suspend fun runProcessResult(process: Process, timeoutMs: Long): ShellCommandResult =
        withContext(Dispatchers.IO) {
            try {
                coroutineScope {
                    val stdout = async(Dispatchers.IO) {
                        runCatching { readLimited(process.inputStream, 8 * 1024) }
                            .onFailure { process.destroyCompat() }
                            .getOrNull()
                    }
                    val stderr = async(Dispatchers.IO) {
                        runCatching { readLimited(process.errorStream, MAX_STDERR_BYTES) }
                            .onFailure { process.destroyCompat() }
                            .getOrNull()
                    }
                    val exited = waitForExit(process, timeoutMs)
                    if (!exited) process.destroyCompat()
                    val streams = withTimeoutOrNull(500L) {
                        stdout.await() to stderr.await()
                    }
                    val exitCode = if (exited) {
                        runCatching { process.exitValue() }.getOrNull()
                    } else {
                        null
                    }
                    if (exitCode == 0) {
                        ShellCommandResult(ok = true, detail = null)
                    } else {
                        val err = streams?.second?.trim().orEmpty().take(120)
                        val detail = when {
                            !exited -> "timeout_${timeoutMs}ms"
                            exitCode != null && err.isNotEmpty() -> "exit=$exitCode err=$err"
                            exitCode != null -> "exit=$exitCode"
                            else -> "no_exit"
                        }
                        ShellCommandResult(ok = false, detail = detail)
                    }
                }
            } finally {
                process.destroyCompat()
            }
        }

    /** shell 执行结果（手势/指针等 fail-soft 诊断用）。 */
    data class ShellCommandResult(
        val ok: Boolean,
        val detail: String? = null,
    )

    private fun readLimited(input: InputStream, maxBytes: Int): String {
        input.use { stream ->
            val output = ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                if (output.size().toLong() + count.toLong() > maxBytes.toLong()) {
                    // 截断后仍返回已读内容供 dumpsys 解析；上层 finally 会 destroy 进程，
                    // 避免 stdout 堵管拖死 waitFor。
                    break
                }
                output.write(buffer, 0, count)
            }
            return output.toString(Charsets.UTF_8.name())
        }
    }

    private fun waitForExit(process: Process, timeoutMs: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            val exited = runCatching {
                process.exitValue()
                true
            }.getOrDefault(false)
            if (exited) return true
            SystemClock.sleep(50L)
        }
        return runCatching {
            process.exitValue()
            true
        }.getOrDefault(false)
    }

    private fun Process.destroyCompat() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            runCatching { destroyForcibly() }
        } else {
            runCatching { destroy() }
        }
        runCatching { destroy() }
    }

    private const val MAX_STDERR_BYTES = 64 * 1024
}
