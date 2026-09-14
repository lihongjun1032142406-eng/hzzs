package top.azek431.hzzs

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import top.azek431.hzzs.core.logging.AppLog
import top.azek431.hzzs.service.vision.createVisionAnalysisChannel

/**
 * 进程级 Application 入口：启用 Hilt，并安装诊断用未捕获异常钩子。
 *
 * 不承载业务状态或 UI 导航；业务单例由 Hilt 模块在需要时创建。
 * Clean Base：不预装、不下载、不激活任何算法包。
 * 异常钩子只写 [AppLog]，不上传、不弹窗。
 */
@HiltAndroidApp
class HzzsApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        installUncaughtHandler()
        createVisionAnalysisChannel(this)
        AppLog.i("app", "HzzsApplication onCreate")
    }

    private fun installUncaughtHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                AppLog.e(
                    "crash",
                    "Uncaught on ${thread.name}: ${error.javaClass.simpleName}: ${error.message}",
                    error,
                )
            }
            previous?.uncaughtException(thread, error)
        }
    }
}
