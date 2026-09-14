package top.azek431.hzzs

import android.annotation.SuppressLint

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import top.azek431.hzzs.core.designsystem.HzzsBreakpoints
import top.azek431.hzzs.core.designsystem.HzzsTheme
import top.azek431.hzzs.core.designsystem.LocalHzzsMotion
import top.azek431.hzzs.core.designsystem.fadeThroughEnter
import top.azek431.hzzs.core.designsystem.fadeThroughExit
import top.azek431.hzzs.core.model.AppConfig
import top.azek431.hzzs.core.preferences.SettingsRepository
import top.azek431.hzzs.feature.about.AboutScreen
import top.azek431.hzzs.feature.about.DonationKind
import top.azek431.hzzs.feature.home.HomeScreen
import top.azek431.hzzs.feature.onboarding.OnboardingScreen
import top.azek431.hzzs.feature.runtime.RuntimeScreen
import top.azek431.hzzs.feature.settings.SettingsExitCoordinator
import top.azek431.hzzs.feature.settings.SettingsScreen
import top.azek431.hzzs.mcp.McpApprovalRequest
import top.azek431.hzzs.mcp.McpForegroundService
import top.azek431.hzzs.mcp.McpUiBridge
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * 唯一导出的 Activity：应用导航壳、首次引导与 MCP 审批 UI 的宿主。
 *
 * 职责：
 * - 承载 Compose 根树与底部/侧栏导航，不把进程级生命周期下沉到 feature 包。
 * - 按配置启停本地 MCP 前台服务（见 [syncMcpService]）。
 * - 展示 MCP 写操作确认对话框；审批结果回写 [McpUiBridge]。
 *
 * 线程：Activity 生命周期与 Compose 在主线程；配置/MCP 状态经 StateFlow 收集。
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var repository: SettingsRepository

    private var pendingDonation: DonationKind? = null
    private val storagePermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val pending = pendingDonation
        pendingDonation = null
        if (granted && pending != null) {
            saveDonationImage(pending)
        } else if (!granted) {
            Toast.makeText(this, getString(R.string.permission_save_image_denied), Toast.LENGTH_LONG).show()
        }
    }
    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* 拒绝时前台服务通知可能不显示；仍允许用户使用应用 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        maybeRequestPostNotifications()
        if (savedInstanceState == null) handleAppOpenCount()
        setContent { HzzsRoot(onSaveDonation = ::saveDonationImage) }
    }

    /** 处理打开计数：第 10 次打开后只提示一次捐赠入口。 */
    private fun handleAppOpenCount() {
        lifecycleScope.launch {
            runCatching {
                val openCount = repository.incrementOpenCount()
                if (openCount >= 10 && !repository.isDonationPromptShown()) {
                    showDonationPromptDialog()
                    repository.markDonationPromptShown()
                }
            }.onFailure { error ->
                android.util.Log.e("HZZS_OpenCount", "Failed to handle app open count", error)
            }
        }
    }

    private fun showDonationPromptDialog() {
        val options = arrayOf(
            getString(R.string.about_donation_prompt_wechat),
            getString(R.string.about_donation_prompt_alipay),
            getString(R.string.about_donation_prompt_iefdian),
        )
        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.about_donation_prompt_title)
            .setMessage(R.string.about_donation_prompt_body)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> saveDonationImage(DonationKind.WECHAT)
                    1 -> saveDonationImage(DonationKind.ALIPAY)
                    2 -> openIEFDianLink()
                }
            }
            .setNegativeButton(R.string.about_donation_prompt_cancel, null)
            .show()
    }

    private fun openIEFDianLink() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.ifdian.net/a/Azek431"))
        if (intent.resolveActivity(packageManager) != null) {
            startActivity(intent)
        } else {
            Toast.makeText(this, R.string.about_open_link_failed, Toast.LENGTH_SHORT).show()
        }
    }

    /** API 33+ 请求通知权限；拒绝不阻塞主流程。 */
    private fun maybeRequestPostNotifications() {
        if (Build.VERSION.SDK_INT < 33) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    @SuppressLint("ResourceType")
    private fun saveDonationImage(kind: DonationKind) {
        if (kind == DonationKind.IEF_DIAN) {
            openIEFDianLink()
            return
        }
        if (
            Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            pendingDonation = kind
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }

        val resId = if (kind == DonationKind.WECHAT) {
            R.drawable.donation_wechat
        } else {
            R.drawable.donation_alipay
        }
        val extension = if (kind == DonationKind.WECHAT) "png" else "jpg"
        val mime = if (extension == "png") "image/png" else "image/jpeg"
        val name = "Azek_${kind.name.lowercase()}_${
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        }.$extension"

        var inserted: android.net.Uri? = null
        runCatching {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, mime)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/HZZS")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }
            inserted = requireNotNull(
                contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values),
            ) { "系统未创建图片记录" }
            contentResolver.openOutputStream(requireNotNull(inserted), "w")?.use { output ->
                resources.openRawResource(resId).use { input -> input.copyTo(output) }
            } ?: error("无法写入图片")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentResolver.update(
                    requireNotNull(inserted),
                    ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                    null,
                    null,
                )
            }
        }.onSuccess {
            Toast.makeText(this, "赞赏码已保存到相册 HZZS", Toast.LENGTH_SHORT).show()
        }.onFailure { error ->
            inserted?.let { contentResolver.delete(it, null, null) }
            Toast.makeText(
                this,
                "保存失败：${error.message ?: error.javaClass.simpleName}",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

}

/**
 * 根级 ViewModel：聚合全局配置、MCP 审批/导航桥与静默更新检查。
 *
 * 不直接持有截图或自动化会话；仅协调设置仓库与 UI 桥接。
 */
@HiltViewModel
class AppViewModel @Inject constructor(
    private val repository: SettingsRepository,
    val mcpUiBridge: McpUiBridge,
    private val updateRepository: top.azek431.hzzs.core.update.UpdateRepository,
    private val captureCapabilityResolver: top.azek431.hzzs.platform.compat.CaptureCapabilityResolver,
) : ViewModel() {
    val config: StateFlow<AppConfig> = repository.config.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        AppConfig(),
    )

    /**
     * 仅已落盘配置：MCP 服务启停必须跟 saved，避免设置草稿未保存就重启 loopback socket。
     */
    val savedConfig: StateFlow<AppConfig> = repository.savedConfig.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        AppConfig(),
    )

    /** 首次引导截图步用：与设置同源的能力快照（含支持度/推荐文案）。 */
    fun onboardingCaptureCapabilities(): List<top.azek431.hzzs.platform.compat.CaptureCapability> =
        captureCapabilityResolver.all()

    /** 启动后按配置尝试静默检查应用更新；失败只写日志，不打扰用户。 */
    fun maybeAutoCheckUpdates() {
        viewModelScope.launch {
            val snapshot = repository.snapshot()
            if (snapshot.update.autoCheck) {
                runCatching {
                    updateRepository.check(
                        beta = snapshot.update.channel == top.azek431.hzzs.core.model.UpdateChannel.BETA,
                        sourcePreference = snapshot.update.sourcePreference,
                    )
                }
            }
        }
    }
    val approval: StateFlow<McpApprovalRequest?> = mcpUiBridge.approval
    val mcpNavigation: StateFlow<String?> = mcpUiBridge.navigation

    fun preview(config: AppConfig) {
        viewModelScope.launch { repository.preview(config) }
    }

    fun completeOnboarding(config: AppConfig) {
        // save 内部 validated；完成引导时若草稿已开自动操作，须带合法免责版本，否则会被洗回关。
        viewModelScope.launch { repository.save(config) }
    }

    fun resolveApproval(request: McpApprovalRequest, approved: Boolean) {
        mcpUiBridge.resolveApproval(request.id, approved)
    }

    fun consumeMcpNavigation(route: String) {
        mcpUiBridge.consumeNavigation(route)
    }
}

private enum class Destination(val route: String, val labelRes: Int, val icon: ImageVector) {
    HOME("home", R.string.nav_home, Icons.Rounded.Home),
    RUNTIME("runtime", R.string.nav_runtime, Icons.Rounded.PlayCircle),
    SETTINGS("settings", R.string.nav_settings, Icons.Rounded.Settings),
    ABOUT("about", R.string.nav_about, Icons.Rounded.Info),
}

/** 主题、引导分流、主导航与 MCP 审批叠层的根 Composable。 */
@Composable
private fun HzzsRoot(
    onSaveDonation: (DonationKind) -> Unit,
    vm: AppViewModel = hiltViewModel(),
) {
    val config by vm.config.collectAsState()
    val savedConfig by vm.savedConfig.collectAsState()
    val approval by vm.approval.collectAsState()
    val mcpNavigation by vm.mcpNavigation.collectAsState()
    val mcpSettingsSubRoute by vm.mcpUiBridge.settingsSubRoute.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current

    LaunchedEffect(
        savedConfig.mcp.enabled,
        savedConfig.mcp.port,
        savedConfig.mcp.requireAuth,
        savedConfig.mcp.authToken,
        savedConfig.mcp.bindLocalhostOnly,
    ) {
        // 只跟已保存配置：草稿预览改 MCP 开关/端口不得重启服务。
        // 指纹不含 permissionLevel / toolPolicies：二者在 tools/call 时读 current()，
        // 变更无需重启 socket（重启会清会话表，RikkaHub 易 -32003）。
        syncMcpService(
            context = context,
            enabled = savedConfig.mcp.enabled,
            fingerprint =
                "${savedConfig.mcp.enabled}:${savedConfig.mcp.port}:" +
                    "${savedConfig.mcp.requireAuth}:${savedConfig.mcp.authToken}:" +
                    "${savedConfig.mcp.bindLocalhostOnly}",
        )
    }
    LaunchedEffect(Unit) {
        vm.maybeAutoCheckUpdates()
    }

    HzzsTheme(config.theme) {
        if (!config.onboarding.completed) {
            OnboardingScreen(
                initial = config,
                captureCapabilities = vm.onboardingCaptureCapabilities(),
                onPreview = vm::preview,
                onComplete = vm::completeOnboarding,
            )
        } else {
            MainNavigation(
                onSaveDonation = onSaveDonation,
                requestedRoute = mcpNavigation,
                onRouteConsumed = vm::consumeMcpNavigation,
                settingsSubRoute = mcpSettingsSubRoute,
                onSettingsSubRouteConsumed = { route ->
                    vm.mcpUiBridge.consumeSettingsSubRoute(route)
                },
            )
        }

        approval?.let { request ->
            McpApprovalDialog(
                request = request,
                onResolve = { approved -> vm.resolveApproval(request, approved) },
            )
        }
    }
}

/**
 * 主界面导航壳：窄屏底部栏 / 宽屏侧栏，并消费 MCP 请求的语义路由。
 */
@Composable
private fun MainNavigation(
    onSaveDonation: (DonationKind) -> Unit,
    requestedRoute: String?,
    onRouteConsumed: (String) -> Unit,
    settingsSubRoute: String? = null,
    onSettingsSubRouteConsumed: (String) -> Unit = {},
) {
    val nav = rememberNavController()
    val settingsExitCoordinator = remember { SettingsExitCoordinator() }
    val entry by nav.currentBackStackEntryAsState()
    val current = entry?.destination?.route

    fun openDestination(route: String) {
        if (route == current) return
        val navigate = { nav.open(route) }
        if (current == Destination.SETTINGS.route) {
            settingsExitCoordinator.request(navigate)
        } else {
            navigate()
        }
    }

    LaunchedEffect(requestedRoute, current) {
        requestedRoute?.let { route ->
            openDestination(route)
            onRouteConsumed(route)
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= HzzsBreakpoints.NavigationExpanded
        if (wide) {
            Row {
                NavigationRail {
                    Spacer(Modifier.height(12.dp))
                    Destination.entries.forEach { destination ->
                        val label = stringResource(destination.labelRes)
                        NavigationRailItem(
                            selected = current == destination.route,
                            onClick = { openDestination(destination.route) },
                            icon = { Icon(destination.icon, contentDescription = label) },
                            label = { Text(label) },
                        )
                    }
                }
                HorizontalDivider(Modifier.fillMaxHeight().width(1.dp))
                AppNavHost(
                    nav = nav,
                    onSaveDonation = onSaveDonation,
                    settingsExitCoordinator = settingsExitCoordinator,
                    settingsSubRoute = settingsSubRoute,
                    onSettingsSubRouteConsumed = onSettingsSubRouteConsumed,
                    modifier = Modifier.weight(1f),
                )
            }
        } else {
            Scaffold(
                bottomBar = {
                    NavigationBar {
                        Destination.entries.forEach { destination ->
                            val label = stringResource(destination.labelRes)
                            NavigationBarItem(
                                selected = current == destination.route,
                                onClick = { openDestination(destination.route) },
                                icon = { Icon(destination.icon, contentDescription = label) },
                                label = { Text(label) },
                            )
                        }
                    }
                },
            ) { padding ->
                AppNavHost(
                    nav = nav,
                    onSaveDonation = onSaveDonation,
                    settingsExitCoordinator = settingsExitCoordinator,
                    settingsSubRoute = settingsSubRoute,
                    onSettingsSubRouteConsumed = onSettingsSubRouteConsumed,
                    modifier = Modifier.padding(padding),
                )
            }
        }
    }
}

@Composable
private fun AppNavHost(
    nav: NavHostController,
    onSaveDonation: (DonationKind) -> Unit,
    settingsExitCoordinator: SettingsExitCoordinator,
    settingsSubRoute: String? = null,
    onSettingsSubRouteConsumed: (String) -> Unit = {},
    modifier: Modifier,
) {
    val motion = LocalHzzsMotion.current
    NavHost(
        navController = nav,
        startDestination = Destination.HOME.route,
        modifier = modifier,
        enterTransition = { motion.fadeThroughEnter() },
        exitTransition = { motion.fadeThroughExit() },
        popEnterTransition = { motion.fadeThroughEnter() },
        popExitTransition = { motion.fadeThroughExit() },
    ) {
        composable(Destination.HOME.route) {
            HomeScreen(
                onOpenRuntime = { nav.navigate(Destination.RUNTIME.route) },
                onOpenSettings = { nav.navigate(Destination.SETTINGS.route) },
            )
        }
        composable(Destination.RUNTIME.route) { RuntimeScreen() }
        composable(Destination.SETTINGS.route) {
            SettingsScreen(
                exitCoordinator = settingsExitCoordinator,
                onExit = { nav.popBackStack() },
                initialSubRoute = settingsSubRoute,
                onInitialSubRouteConsumed = onSettingsSubRouteConsumed,
            )
        }
        composable(Destination.ABOUT.route) {
            AboutScreen(
                versionName = BuildConfig.VERSION_NAME,
                onSaveQr = onSaveDonation,
            )
        }
    }
}

/** MCP「每次确认」模式下的一次性写操作审批对话框。 */
@Composable
private fun McpApprovalDialog(
    request: McpApprovalRequest,
    onResolve: (Boolean) -> Unit,
) {
    AlertDialog(
        onDismissRequest = { onResolve(false) },
        icon = { Icon(Icons.Rounded.SmartToy, contentDescription = null) },
        title = { Text(stringResource(R.string.mcp_approval_title)) },
        text = {
            Text(
                stringResource(
                    R.string.mcp_approval_body,
                    request.tool,
                    request.summary,
                ),
            )
        },
        confirmButton = {
            Button(onClick = { onResolve(true) }) {
                Text(stringResource(R.string.action_allow_once))
            }
        },
        dismissButton = {
            OutlinedButton(onClick = { onResolve(false) }) {
                Text(stringResource(R.string.action_deny))
            }
        },
    )
}

private fun NavHostController.open(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/** 上次成功启动 MCP 服务时的配置指纹，用于避免无意义的重复启停。 */
private var lastMcpFingerprint: String? = null

/**
 * 将设置中的 MCP 开关/端口/权限级同步到 [McpForegroundService]。
 * 指纹变化时先 STOP 再 START，确保旧 loopback socket 关闭。
 */
private fun syncMcpService(context: Context, enabled: Boolean, fingerprint: String) {
    if (enabled) {
        if (lastMcpFingerprint == fingerprint) return
        // 端口/权限变化：先发 ACTION_STOP，确保旧 socket 关闭后再 START。
        if (lastMcpFingerprint != null) {
            context.startService(
                Intent(context, McpForegroundService::class.java)
                    .setAction(McpForegroundService.ACTION_STOP),
            )
            context.stopService(Intent(context, McpForegroundService::class.java))
        }
        val intent = Intent(context, McpForegroundService::class.java)
            .setAction(McpForegroundService.ACTION_START)
        ContextCompat.startForegroundService(context, intent)
        lastMcpFingerprint = fingerprint
    } else {
        context.startService(
            Intent(context, McpForegroundService::class.java)
                .setAction(McpForegroundService.ACTION_STOP),
        )
        context.stopService(Intent(context, McpForegroundService::class.java))
        lastMcpFingerprint = null
    }
}
