/**
 * 设置模块 Compose 入口与嵌套导航。
 *
 * 职责：首页 + 分类子页共享同一 [SettingsViewModel]；改动进入草稿预览，
 * 顶栏右上角「保存并应用」才落盘；离开模块时若 dirty 弹窗。
 * 数据流：订阅 draft/dirty/update/algorithm；子页经 [SettingsViewModel.update] 改草稿。
 * 边界：不直接 JNI/权限运行时。
 */
package top.azek431.hzzs.feature.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.delay
import top.azek431.hzzs.R
import top.azek431.hzzs.core.designsystem.HzzsBreakpoints
import top.azek431.hzzs.core.designsystem.LocalHzzsMotion
import top.azek431.hzzs.core.designsystem.sharedAxisXEnter
import top.azek431.hzzs.core.designsystem.sharedAxisXExit
import top.azek431.hzzs.core.designsystem.sharedAxisXPopEnter
import top.azek431.hzzs.core.designsystem.sharedAxisXPopExit
import top.azek431.hzzs.feature.settings.model.SettingsCategory
import top.azek431.hzzs.feature.settings.model.SettingsRoutes
import top.azek431.hzzs.feature.settings.screens.AppearanceSettingsScreen
import top.azek431.hzzs.feature.settings.screens.AutomationSettingsScreen
import top.azek431.hzzs.feature.settings.screens.CaptureSettingsScreen
import top.azek431.hzzs.feature.settings.screens.DeveloperSettingsScreen
import top.azek431.hzzs.feature.settings.screens.LogViewerScreen
import top.azek431.hzzs.feature.settings.screens.McpAccessLogViewerScreen
import top.azek431.hzzs.feature.settings.screens.McpSettingsScreen
import top.azek431.hzzs.feature.settings.screens.NetworkUpdateSettingsScreen
import top.azek431.hzzs.feature.settings.screens.OverlaySettingsScreen
import top.azek431.hzzs.feature.settings.screens.SettingsHomeScreen

/**
 * 设置模块入口：首页 + 分类子页共享同一 [SettingsViewModel]。
 * 配置改动为草稿预览；右上角「保存并应用」落盘；离开时未保存弹窗。
 * 宽屏左侧常驻首页目录，右侧为分类内容。分类间切换保留草稿。
 * [initialSubRoute] 供 MCP 深链打开指定分类（如 mcp / developer / log_viewer）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onExit: () -> Unit,
    exitCoordinator: SettingsExitCoordinator? = null,
    initialSubRoute: String? = null,
    onInitialSubRouteConsumed: (String) -> Unit = {},
    vm: SettingsViewModel = hiltViewModel(),
) {
    val config by vm.draft.collectAsState()
    val dirty by vm.dirty.collectAsState()
    val updateState by vm.updateState.collectAsState()
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route ?: SettingsRoutes.HOME
    val onHome = route == SettingsRoutes.HOME
    var message by remember { mutableStateOf<String?>(null) }
    var pendingLeave by remember { mutableStateOf<(() -> Unit)?>(null) }

    val currentOnExit by rememberUpdatedState(onExit)
    val currentDirty by rememberUpdatedState(dirty)

    fun leave(action: () -> Unit = currentOnExit) {
        if (currentDirty) {
            pendingLeave = action
        } else {
            action()
        }
    }

    DisposableEffect(exitCoordinator, vm) {
        val registration = exitCoordinator?.attach { onDone -> leave(onDone) }
        onDispose {
            registration?.dispose()
            vm.onLeaveComposition()
        }
    }

    // MCP / 外部深链：打开设置后进入指定子页。
    LaunchedEffect(initialSubRoute) {
        val target = initialSubRoute?.trim()?.takeIf { it.isNotEmpty() } ?: return@LaunchedEffect
        val dest = when (target) {
            SettingsRoutes.HOME, "home" -> SettingsRoutes.HOME
            SettingsRoutes.LOG_VIEWER, "logs", "log" -> SettingsRoutes.LOG_VIEWER
            SettingsRoutes.ALGORITHM_PIPELINE, "pipeline" -> SettingsRoutes.ALGORITHM_PIPELINE
            SettingsRoutes.MCP_ACCESS_LOG, "mcp_log", "access_log" -> SettingsRoutes.MCP_ACCESS_LOG
            else -> SettingsCategory.entries.firstOrNull { it.route == target }?.route
        }
        if (dest != null && dest != route) {
            if (dest == SettingsRoutes.HOME) {
                nav.popBackStack(SettingsRoutes.HOME, inclusive = false)
            } else {
                nav.navigate(dest) { launchSingleTop = true }
            }
        }
        onInitialSubRouteConsumed(target)
    }

    BackHandler {
        if (!onHome) {
            nav.popBackStack()
        } else {
            leave()
        }
    }

    val settingsLabel = stringResource(R.string.nav_settings)
    val savedMessage = stringResource(R.string.settings_saved)

    // 关闭开发者后设置首页隐藏入口；若仍停在开发者路由则回首页。
    LaunchedEffect(config.developer.enabled, route) {
        if (!config.developer.enabled &&
            (
                route == SettingsCategory.DEVELOPER.route ||
                    route == SettingsRoutes.LOG_VIEWER ||
                    route == SettingsRoutes.ALGORITHM_PIPELINE
                )
        ) {
            nav.popBackStack(SettingsRoutes.HOME, inclusive = false)
        }
    }

    val title = when (route) {
        SettingsRoutes.HOME -> settingsLabel
        SettingsCategory.APPEARANCE.route -> stringResource(SettingsCategory.APPEARANCE.titleRes)
        SettingsCategory.CAPTURE.route -> stringResource(SettingsCategory.CAPTURE.titleRes)
        SettingsCategory.OVERLAY.route -> stringResource(SettingsCategory.OVERLAY.titleRes)
        SettingsCategory.AUTOMATION.route -> stringResource(SettingsCategory.AUTOMATION.titleRes)
        SettingsCategory.NETWORK.route -> stringResource(SettingsCategory.NETWORK.titleRes)
        SettingsCategory.MCP.route -> stringResource(SettingsCategory.MCP.titleRes)
        SettingsCategory.DEVELOPER.route -> stringResource(SettingsCategory.DEVELOPER.titleRes)
        SettingsRoutes.LOG_VIEWER -> stringResource(R.string.log_viewer_title)
        SettingsRoutes.MCP_ACCESS_LOG -> stringResource(R.string.mcp_access_log_viewer_title)
        else -> settingsLabel
    }

    if (pendingLeave != null) {
        AlertDialog(
            onDismissRequest = { pendingLeave = null },
            title = { Text(stringResource(R.string.settings_unsaved_title)) },
            text = { Text(stringResource(R.string.settings_unsaved_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        val action = pendingLeave
                        vm.save { success ->
                            if (success) {
                                pendingLeave = null
                                message = savedMessage
                                action?.invoke()
                            } else {
                                message = "保存失败，请重试"
                            }
                        }
                    },
                ) {
                    Text(stringResource(R.string.settings_save_and_leave))
                }
            },
            dismissButton = {
                Row {
                    TextButton(
                        onClick = {
                            val action = pendingLeave
                            pendingLeave = null
                            vm.discard {
                                action?.invoke()
                            }
                        },
                    ) {
                        Text(stringResource(R.string.action_discard))
                    }
                    TextButton(onClick = { pendingLeave = null }) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (onHome) leave() else nav.popBackStack()
                        },
                    ) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                actions = {
                    if (dirty) {
                        TextButton(
                            onClick = {
                                vm.save { success ->
                                    message = if (success) savedMessage else "保存失败，请重试"
                                }
                            },
                        ) {
                            Icon(
                                Icons.Rounded.Save,
                                contentDescription = null,
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.settings_save_and_apply))
                        }
                    }
                },
            )
        },
        snackbarHost = {
            message?.let { text ->
                LaunchedEffect(text) {
                    delay(3_000)
                    message = null
                }
                Snackbar { Text(text) }
            }
        },
    ) { padding ->
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            val wide = maxWidth >= HzzsBreakpoints.SettingsTwoPane
            if (wide) {
                Row(Modifier.fillMaxSize()) {
                    Box(
                        Modifier
                            .widthIn(max = 360.dp)
                            .weight(0.38f),
                    ) {
                        SettingsHomeScreen(
                            config = config,
                            onOpen = { category ->
                                if (route != category.route) {
                                    nav.navigate(category.route) {
                                        launchSingleTop = true
                                    }
                                }
                            },
                            selectedRoute = route,
                        )
                    }
                    SettingsNavHost(
                        nav = nav,
                        vm = vm,
                        config = config,
                        updateState = updateState,
                        onMessage = { message = it },
                        modifier = Modifier.weight(1f),
                        startAtHome = false,
                    )
                }
            } else {
                SettingsNavHost(
                    nav = nav,
                    vm = vm,
                    config = config,
                    updateState = updateState,
                    onMessage = { message = it },
                    modifier = Modifier.fillMaxSize(),
                    startAtHome = true,
                )
            }
        }
    }
}

/** 设置内嵌 NavHost：把共享配置与即时任务回调分发给各分类屏。 */
@Composable
private fun SettingsNavHost(
    nav: NavHostController,
    vm: SettingsViewModel,
    config: top.azek431.hzzs.core.model.AppConfig,
    updateState: UpdateUiState,
    onMessage: (String) -> Unit,
    modifier: Modifier,
    startAtHome: Boolean,
) {
    val motion = LocalHzzsMotion.current
    NavHost(
        navController = nav,
        startDestination = if (startAtHome) SettingsRoutes.HOME else SettingsCategory.APPEARANCE.route,
        modifier = modifier,
        enterTransition = { motion.sharedAxisXEnter() },
        exitTransition = { motion.sharedAxisXExit() },
        popEnterTransition = { motion.sharedAxisXPopEnter() },
        popExitTransition = { motion.sharedAxisXPopExit() },
    ) {
        composable(SettingsRoutes.HOME) {
            SettingsHomeScreen(
                config = config,
                onOpen = { category ->
                    nav.navigate(category.route) { launchSingleTop = true }
                },
            )
        }
        composable(SettingsCategory.APPEARANCE.route) {
            AppearanceSettingsScreen(
                config = config,
                update = vm::update,
                exportTheme = vm::exportTheme,
                importTheme = vm::importTheme,
                onMessage = onMessage,
            )
        }
        composable(SettingsCategory.CAPTURE.route) {
            CaptureSettingsScreen(
                config = config,
                capabilities = vm.capabilities,
                update = vm::update,
            )
        }
        composable(SettingsCategory.OVERLAY.route) {
            OverlaySettingsScreen(config = config, update = vm::update)
        }
        composable(SettingsCategory.AUTOMATION.route) {
            AutomationSettingsScreen(
                config = config,
                gestureCapabilities = vm.gestureCapabilities,
                update = vm::update,
            )
        }
        composable(SettingsCategory.NETWORK.route) {
            NetworkUpdateSettingsScreen(
                config = config,
                updateState = updateState,
                update = vm::update,
                onCheckApp = vm::checkForUpdates,
                onDownloadApp = vm::downloadAvailableUpdate,
                onInstallApp = vm::installDownloadedUpdate,
                onIgnoreApp = vm::ignoreAvailableUpdate,
            )
        }
        composable(SettingsCategory.MCP.route) {
            val mcpState by vm.mcpState.collectAsState()
            McpSettingsScreen(
                config = config,
                update = vm::update,
                mcpState = mcpState,
                onMessage = onMessage,
                onOpenAccessLog = {
                    nav.navigate(SettingsRoutes.MCP_ACCESS_LOG) { launchSingleTop = true }
                },
            )
        }
        composable(SettingsCategory.DEVELOPER.route) {
            val debugFrameCount by vm.debugFrameCount.collectAsState()
            DeveloperSettingsScreen(
                developerEnabled = config.developer.enabled,
                config = config,
                update = vm::update,
                debugFrameCount = debugFrameCount,
                onRefreshDebugFrames = vm::refreshDebugFrameCount,
                onClearDebugFrames = vm::clearDebugFrames,
                onBuildDiagnostics = vm::buildDiagnosticsReport,
                onOpenLogViewer = {
                    nav.navigate(SettingsRoutes.LOG_VIEWER) { launchSingleTop = true }
                },
                onMessage = onMessage,
            )
        }
        composable(SettingsRoutes.LOG_VIEWER) {
            LogViewerScreen(
                onBack = { nav.popBackStack() },
                onMessage = onMessage,
            )
        }
        composable(SettingsRoutes.MCP_ACCESS_LOG) {
            McpAccessLogViewerScreen(onMessage = onMessage)
        }
    }
}