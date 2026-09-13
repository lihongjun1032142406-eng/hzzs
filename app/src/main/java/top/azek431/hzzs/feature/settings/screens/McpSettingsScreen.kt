/**
 * MCP 本地服务设置页。
 *
 * 职责：MCP 开关、可选局域网绑定、权限级、工具策略（弹窗管理）、连接信息复制、运行状态。
 * 数据流：mcp 字段经 [update] 草稿预览；MainActivity 订阅配置流同步前台服务。
 * 边界：不启动 MCP 服务本体；调试帧元数据需开发者选项 + allowDebugFrames 同时开启。
 * 安全：默认只绑 127.0.0.1；「允许局域网」须风险确认后写 bindLocalhostOnly=false（0.0.0.0）。
 */
package top.azek431.hzzs.feature.settings.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import top.azek431.hzzs.R
import top.azek431.hzzs.core.designsystem.LocalHzzsDimensions
import top.azek431.hzzs.core.model.McpPermissionLevel
import top.azek431.hzzs.core.model.McpToolPolicy
import top.azek431.hzzs.core.model.displayName
import top.azek431.hzzs.core.platform.ClipboardHelper
import top.azek431.hzzs.feature.settings.components.SettingsNavigationRow
import top.azek431.hzzs.feature.settings.components.SettingsRadioCard
import top.azek431.hzzs.feature.settings.components.SettingsSectionCard
import top.azek431.hzzs.feature.settings.components.SettingsStatusChip
import top.azek431.hzzs.feature.settings.components.SettingsSwitchRow
import top.azek431.hzzs.feature.settings.components.SettingsWarningCard
import top.azek431.hzzs.mcp.McpAccessLog
import top.azek431.hzzs.mcp.McpClientImportDialect
import top.azek431.hzzs.mcp.McpEndpointDisplayMode
import top.azek431.hzzs.mcp.McpServerState
import top.azek431.hzzs.mcp.McpToolCatalog
import top.azek431.hzzs.mcp.McpToolDescriptor
import top.azek431.hzzs.mcp.McpToolLabels
import top.azek431.hzzs.mcp.McpToolRisk
import top.azek431.hzzs.mcp.generateMcpAuthToken
import top.azek431.hzzs.mcp.listLanIpv4Addresses

/**
 * MCP 本地服务设置页。
 *
 * 同机 RikkaHub / 电脑 ADB / 可选局域网（0.0.0.0）与 Claude Code 导入方言。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun McpSettingsScreen(
    config: top.azek431.hzzs.core.model.AppConfig,
    update: ((top.azek431.hzzs.core.model.AppConfig) -> top.azek431.hzzs.core.model.AppConfig) -> Unit,
    mcpState: McpServerState,
    onMessage: (String) -> Unit = {},
    onOpenAccessLog: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val dimensions = LocalHzzsDimensions.current
    val context = LocalContext.current
    val copyFailedMsg = stringResource(R.string.mcp_copy_failed)
    val turnOnFirstMsg = stringResource(R.string.mcp_turn_on_first)
    val copiedUrl = stringResource(R.string.mcp_copied_url)
    val copiedJson = stringResource(R.string.mcp_copied_import_json)
    val copiedToken = stringResource(R.string.mcp_copied_token)
    val copiedAll = stringResource(R.string.mcp_copied_connection_info)
    val copiedAdb = stringResource(R.string.mcp_copied_adb_forward)
    val rotatedTokenMsg = stringResource(R.string.mcp_rotated_token)

    var displayMode by rememberSaveable {
        mutableStateOf(
            if (!config.mcp.bindLocalhostOnly) {
                McpEndpointDisplayMode.LAN.name
            } else {
                McpEndpointDisplayMode.LOOPBACK.name
            },
        )
    }
    var clientDialect by rememberSaveable { mutableStateOf(McpClientImportDialect.RIKKAHUB.name) }
    var customHost by rememberSaveable { mutableStateOf("") }
    var preferredLanHost by rememberSaveable { mutableStateOf("") }
    var lanRiskDialog by remember { mutableStateOf(false) }
    var uiLanIps by remember { mutableStateOf(emptyList<String>()) }
    var toolsDialogOpen by rememberSaveable { mutableStateOf(false) }
    var accessLogRevision by remember { mutableStateOf(0L) }

    LaunchedEffect(config.mcp.bindLocalhostOnly, mcpState.running, mcpState.lanAddresses) {
        // 始终把 127.0.0.1 放在可选主机最前；再附 LAN 地址（开局域网时优先用服务探测结果）。
        val lanOnly = when {
            mcpState.running && mcpState.lanAddresses.isNotEmpty() -> mcpState.lanAddresses
            !config.mcp.bindLocalhostOnly -> listLanIpv4Addresses(includeLoopback = false)
            else -> emptyList()
        }
        uiLanIps = listOf("127.0.0.1") + lanOnly.filter { it != "127.0.0.1" }
        if (preferredLanHost.isNotBlank() && preferredLanHost !in uiLanIps) {
            preferredLanHost = uiLanIps.firstOrNull().orEmpty()
        }
        if (preferredLanHost.isBlank()) {
            preferredLanHost = uiLanIps.firstOrNull().orEmpty()
        }
    }

    val mode = remember(displayMode) {
        runCatching { McpEndpointDisplayMode.valueOf(displayMode) }
            .getOrDefault(McpEndpointDisplayMode.LOOPBACK)
    }
    val dialect = remember(clientDialect) {
        runCatching { McpClientImportDialect.valueOf(clientDialect) }
            .getOrDefault(McpClientImportDialect.RIKKAHUB)
    }
    val displayHost = remember(mode, customHost, preferredLanHost, mcpState.lanAddresses, uiLanIps) {
        val stateForResolve = mcpState.copy(
            lanAddresses = mcpState.lanAddresses.ifEmpty { uiLanIps },
        )
        stateForResolve.resolveDisplayHost(mode, customHost, preferredLanHost)
    }
    val displayUrl = remember(mcpState.port, displayHost, mcpState.running, config.mcp.port) {
        if (mcpState.running) {
            mcpState.endpointUrl(displayHost)
        } else {
            "http://$displayHost:${config.mcp.port}/mcp"
        }
    }
    val importJson = remember(mcpState, dialect, displayHost, config.mcp.port) {
        if (mcpState.running) {
            mcpState.clientImportJson(dialect = dialect, host = displayHost)
        } else {
            val type = when (dialect) {
                McpClientImportDialect.RIKKAHUB -> "streamable_http"
                McpClientImportDialect.CLAUDE_CODE -> "http"
            }
            """
            {
              "mcpServers": {
                "hzzs": {
                  "type": "$type",
                  "url": "http://$displayHost:${config.mcp.port}/mcp"
                }
              }
            }
            """.trimIndent()
        }
    }

    fun copy(label: String, text: String, okMsg: String) {
        val ok = ClipboardHelper.copyText(context, label, text)
        onMessage(if (ok) okMsg else copyFailedMsg)
    }

    fun ensureAuthToken(current: top.azek431.hzzs.core.model.AppConfig): String {
        val existing = current.mcp.authToken
        if (existing.isNotBlank()) return existing
        return generateMcpAuthToken()
    }

    fun rotateAuthToken() {
        val next = generateMcpAuthToken()
        update { it.copy(mcp = it.mcp.copy(requireAuth = true, authToken = next)) }
        onMessage(rotatedTokenMsg)
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(dimensions.screenPadding),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.38f),
                ),
            ) {
                Column(Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (mcpState.running) Icons.Rounded.PlayArrow else Icons.Rounded.WarningAmber,
                            contentDescription = null,
                            tint = if (mcpState.running) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                            modifier = Modifier.size(28.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            if (mcpState.running) "MCP 正在运行" else "MCP 未运行",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    val effectivePort = if (mcpState.running && mcpState.port > 0) {
                        mcpState.port
                    } else {
                        config.mcp.port
                    }
                    val bindLocalOnly = if (mcpState.running) {
                        mcpState.bindLocalhostOnly
                    } else {
                        config.mcp.bindLocalhostOnly
                    }
                    val bindLabel = if (bindLocalOnly) {
                        "绑定 127.0.0.1:$effectivePort"
                    } else {
                        "绑定 0.0.0.0:$effectivePort"
                    }
                    val loopbackUrl = "http://127.0.0.1:$effectivePort/mcp"
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SettingsStatusChip(bindLabel)
                        SettingsStatusChip(
                            if ((if (mcpState.running) mcpState.requireAuth else config.mcp.requireAuth)) {
                                "Bearer 鉴权"
                            } else {
                                "免鉴权"
                            },
                            emphasis = false,
                        )
                        if (!bindLocalOnly) {
                            SettingsStatusChip("局域网", emphasis = true)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.mcp_status_loopback_label),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    CodeBlock(loopbackUrl)
                    if (displayUrl != loopbackUrl) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            stringResource(R.string.mcp_status_display_url_label),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        CodeBlock(displayUrl)
                    }
                    if (mcpState.running) {
                        if (mcpState.requireAuth && mcpState.token.isNotBlank()) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "Token（前 12 位）",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                mcpState.token.take(12) + "…",
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            )
                        }
                        Text(
                            stringResource(R.string.mcp_status_running_body),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    } else {
                        Text(
                            stringResource(R.string.mcp_status_not_running_body),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    mcpState.lastError?.let { error ->
                        Text(
                            error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }

        item {
            SettingsSectionCard(
                title = stringResource(R.string.mcp_lan_section_title),
                description = stringResource(R.string.mcp_lan_section_desc),
            ) {
                SettingsSwitchRow(
                    title = stringResource(R.string.mcp_lan_switch),
                    subtitle = stringResource(R.string.mcp_lan_switch_subtitle),
                    checked = !config.mcp.bindLocalhostOnly,
                    onCheckedChange = { enabled ->
                        if (enabled) {
                            lanRiskDialog = true
                        } else {
                            update { it.copy(mcp = it.mcp.copy(bindLocalhostOnly = true)) }
                            if (displayMode == McpEndpointDisplayMode.LAN.name) {
                                displayMode = McpEndpointDisplayMode.LOOPBACK.name
                            }
                        }
                    },
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.mcp_lan_ips_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stringResource(R.string.mcp_lan_ips_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    uiLanIps.forEach { ip ->
                        FilterChip(
                            selected = preferredLanHost == ip,
                            onClick = {
                                preferredLanHost = ip
                                displayMode = if (ip == "127.0.0.1") {
                                    McpEndpointDisplayMode.LOOPBACK.name
                                } else {
                                    McpEndpointDisplayMode.LAN.name
                                }
                            },
                            label = {
                                Text(
                                    if (ip == "127.0.0.1") {
                                        stringResource(R.string.mcp_lan_ip_loopback_chip)
                                    } else {
                                        ip
                                    },
                                )
                            },
                        )
                    }
                }
                if (uiLanIps.none { it != "127.0.0.1" } && !config.mcp.bindLocalhostOnly) {
                    Text(
                        stringResource(R.string.mcp_lan_ips_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }

        item {
            SettingsSectionCard(
                title = stringResource(R.string.mcp_endpoint_section_title),
                description = stringResource(R.string.mcp_endpoint_section_desc),
            ) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = mode == McpEndpointDisplayMode.LOOPBACK,
                        onClick = { displayMode = McpEndpointDisplayMode.LOOPBACK.name },
                        label = { Text(stringResource(R.string.mcp_endpoint_mode_loopback)) },
                    )
                    FilterChip(
                        selected = mode == McpEndpointDisplayMode.ADB_FORWARD,
                        onClick = { displayMode = McpEndpointDisplayMode.ADB_FORWARD.name },
                        label = { Text(stringResource(R.string.mcp_endpoint_mode_adb)) },
                    )
                    FilterChip(
                        selected = mode == McpEndpointDisplayMode.LAN,
                        onClick = { displayMode = McpEndpointDisplayMode.LAN.name },
                        enabled = !config.mcp.bindLocalhostOnly || uiLanIps.isNotEmpty(),
                        label = { Text(stringResource(R.string.mcp_endpoint_mode_lan)) },
                    )
                    FilterChip(
                        selected = mode == McpEndpointDisplayMode.CUSTOM,
                        onClick = { displayMode = McpEndpointDisplayMode.CUSTOM.name },
                        label = { Text(stringResource(R.string.mcp_endpoint_mode_custom)) },
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    when (mode) {
                        McpEndpointDisplayMode.LOOPBACK ->
                            stringResource(R.string.mcp_endpoint_hint_loopback)
                        McpEndpointDisplayMode.ADB_FORWARD ->
                            stringResource(R.string.mcp_endpoint_hint_adb)
                        McpEndpointDisplayMode.LAN ->
                            stringResource(R.string.mcp_endpoint_hint_lan)
                        McpEndpointDisplayMode.CUSTOM ->
                            stringResource(R.string.mcp_endpoint_hint_custom)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (mode == McpEndpointDisplayMode.CUSTOM) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = customHost,
                        onValueChange = { customHost = it.trim() },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text(stringResource(R.string.mcp_endpoint_custom_host_label)) },
                        placeholder = { Text(stringResource(R.string.mcp_endpoint_custom_host_placeholder)) },
                        supportingText = {
                            Text(stringResource(R.string.mcp_endpoint_custom_host_support))
                        },
                    )
                }
                if (mode == McpEndpointDisplayMode.ADB_FORWARD && mcpState.running) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.mcp_adb_forward_label),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    CodeBlock(mcpState.adbForwardCommand())
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            copy(
                                "HZZS adb forward",
                                mcpState.adbForwardCommand(),
                                copiedAdb,
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.mcp_copy_adb_forward))
                    }
                }
            }
        }

        item {
            SettingsSectionCard(
                title = stringResource(R.string.mcp_client_dialect_title),
                description = stringResource(R.string.mcp_client_dialect_desc),
            ) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = dialect == McpClientImportDialect.RIKKAHUB,
                        onClick = { clientDialect = McpClientImportDialect.RIKKAHUB.name },
                        label = { Text(stringResource(R.string.mcp_client_dialect_rikkahub)) },
                    )
                    FilterChip(
                        selected = dialect == McpClientImportDialect.CLAUDE_CODE,
                        onClick = { clientDialect = McpClientImportDialect.CLAUDE_CODE.name },
                        label = { Text(stringResource(R.string.mcp_client_dialect_claude_code)) },
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    when (dialect) {
                        McpClientImportDialect.RIKKAHUB ->
                            stringResource(R.string.mcp_client_dialect_hint_rikkahub)
                        McpClientImportDialect.CLAUDE_CODE ->
                            stringResource(R.string.mcp_client_dialect_hint_claude_code)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            if (mcpState.running) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            copy("HZZS MCP JSON", importJson, copiedJson)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.mcp_copy_import_json))
                    }
                    OutlinedButton(
                        onClick = {
                            copy("HZZS MCP URL", displayUrl, copiedUrl)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.mcp_copy_url))
                    }
                    if (mcpState.requireAuth && mcpState.token.isNotBlank()) {
                        OutlinedButton(
                            onClick = {
                                copy("HZZS MCP Token", mcpState.token, copiedToken)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.mcp_copy_token))
                        }
                    }
                    OutlinedButton(
                        onClick = {
                            val typeLabel = when (dialect) {
                                McpClientImportDialect.RIKKAHUB -> "streamable_http"
                                McpClientImportDialect.CLAUDE_CODE -> "http"
                            }
                            val all = buildString {
                                appendLine(displayUrl)
                                appendLine("type: $typeLabel")
                                appendLine(
                                    if (mcpState.bindLocalhostOnly) {
                                        "bind: 127.0.0.1:${mcpState.port} (loopback only)"
                                    } else {
                                        "bind: 0.0.0.0:${mcpState.port} (LAN)"
                                    },
                                )
                                // 始终写出回环 URL：同机 / ADB 场景必用；勿被 LAN 展示主机淹没。
                                appendLine("loopback: http://127.0.0.1:${mcpState.port}/mcp")
                                when (mode) {
                                    McpEndpointDisplayMode.ADB_FORWARD -> {
                                        appendLine("adb: ${mcpState.adbForwardCommand()}")
                                    }
                                    McpEndpointDisplayMode.LAN -> {
                                        appendLine("lanHosts: ${uiLanIps.joinToString()}")
                                        appendLine(
                                            "hint: prefer 192.168.x / same Wi-Fi; " +
                                                "10.x may be cellular; 100.x is Tailscale",
                                        )
                                    }
                                    McpEndpointDisplayMode.CUSTOM -> {
                                        appendLine("displayHost: $displayHost (custom)")
                                    }
                                    McpEndpointDisplayMode.LOOPBACK -> Unit
                                }
                                if (mcpState.requireAuth) {
                                    appendLine("Authorization: Bearer ${mcpState.token}")
                                } else {
                                    appendLine("auth: off")
                                }
                                appendLine()
                                append(importJson)
                            }
                            copy("HZZS MCP", all, copiedAll)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.mcp_copy_connection_info))
                    }
                }
            } else {
                OutlinedButton(
                    onClick = { onMessage(turnOnFirstMsg) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.mcp_turn_on_and_connect))
                }
            }
        }

        item {
            SettingsSectionCard(
                title = stringResource(R.string.mcp_section_enable_title),
                description = stringResource(R.string.mcp_section_enable_desc),
            ) {
                SettingsSwitchRow(
                    title = stringResource(R.string.mcp_enable_switch),
                    subtitle = stringResource(R.string.mcp_enable_subtitle),
                    checked = config.mcp.enabled,
                    onCheckedChange = { value ->
                        update { it.copy(mcp = it.mcp.copy(enabled = value)) }
                    },
                )
                SettingsSwitchRow(
                    title = stringResource(R.string.mcp_require_auth_switch),
                    subtitle = stringResource(R.string.mcp_require_auth_subtitle),
                    checked = config.mcp.requireAuth,
                    onCheckedChange = { value ->
                        if (value) {
                            val token = ensureAuthToken(config)
                            update {
                                it.copy(
                                    mcp = it.mcp.copy(
                                        requireAuth = true,
                                        authToken = it.mcp.authToken.ifBlank { token },
                                    ),
                                )
                            }
                        } else {
                            update { it.copy(mcp = it.mcp.copy(requireAuth = false)) }
                        }
                    },
                )
                if (config.mcp.requireAuth) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.mcp_token_stable_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { rotateAuthToken() },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.mcp_rotate_token))
                    }
                }
            }
        }

        item {
            val accessEntries = remember(accessLogRevision, config.mcp.accessLogEnabled) {
                McpAccessLog.snapshot(limit = 30, newestFirst = true)
            }
            SettingsSectionCard(
                title = stringResource(R.string.mcp_access_log_section_title),
                description = stringResource(R.string.mcp_access_log_section_desc),
            ) {
                SettingsSwitchRow(
                    title = stringResource(R.string.mcp_access_log_switch),
                    subtitle = stringResource(R.string.mcp_access_log_switch_subtitle),
                    checked = config.mcp.accessLogEnabled,
                    onCheckedChange = { value ->
                        update { it.copy(mcp = it.mcp.copy(accessLogEnabled = value)) }
                        McpAccessLog.setEnabled(value)
                    },
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.mcp_access_log_count, accessEntries.size),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                if (accessEntries.isEmpty()) {
                    Text(
                        stringResource(R.string.mcp_access_log_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    accessEntries.take(12).forEach { entry ->
                        Text(
                            entry.formatLine(),
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                            ),
                            modifier = Modifier.padding(vertical = 2.dp),
                        )
                    }
                    if (accessEntries.size > 12) {
                        Text(
                            "…共 ${accessEntries.size} 条，可用 get_mcp_access_log 拉取完整 ring",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                SettingsNavigationRow(
                    title = stringResource(R.string.mcp_access_log_open_full),
                    subtitle = stringResource(R.string.mcp_access_log_open_full_subtitle),
                    onClick = onOpenAccessLog,
                )
                val accessLogClearedMessage = stringResource(R.string.mcp_access_log_cleared)
                OutlinedButton(
                    onClick = {
                        McpAccessLog.clear()
                        accessLogRevision = McpAccessLog.revision()
                        onMessage(accessLogClearedMessage)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = accessEntries.isNotEmpty(),
                ) {
                    Text(stringResource(R.string.mcp_access_log_clear))
                }
                // 轮询 revision，避免额外 ViewModel
                LaunchedEffect(config.mcp.enabled, config.mcp.accessLogEnabled) {
                    while (true) {
                        val rev = McpAccessLog.revision()
                        if (rev != accessLogRevision) accessLogRevision = rev
                        delay(1_200)
                    }
                }
            }
        }

        item {
            SettingsSectionCard(
                title = stringResource(R.string.mcp_section_permission_title),
                description = stringResource(R.string.mcp_section_permission_desc),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    McpPermissionLevel.entries.forEach { level ->
                        SettingsRadioCard(
                            title = level.displayName(),
                            subtitle = permissionDescription(level),
                            selected = config.mcp.permissionLevel == level,
                            onClick = {
                                update { it.copy(mcp = it.mcp.copy(permissionLevel = level)) }
                            },
                            trailing = if (config.mcp.permissionLevel == level) {
                                stringResource(R.string.mcp_current)
                            } else {
                                null
                            },
                        )
                    }
                }
            }
        }

        item {
            SettingsSectionCard(
                title = stringResource(R.string.mcp_section_debug_frames_title),
                description = null,
            ) {
                SettingsSwitchRow(
                    title = stringResource(R.string.mcp_debug_frames_switch),
                    checked = config.mcp.allowDebugFrames,
                    enabled = config.developer.enabled,
                    onCheckedChange = { value ->
                        update { it.copy(mcp = it.mcp.copy(allowDebugFrames = value)) }
                    },
                )
                Text(
                    if (!config.developer.enabled) {
                        stringResource(R.string.mcp_debug_frames_dev_required)
                    } else {
                        stringResource(R.string.mcp_debug_frames_desc)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            val policies = config.mcp.toolPolicies
            val disabledCount = policies.count { it.value == McpToolPolicy.DISABLED }
            val alwaysAskCount = policies.count { it.value == McpToolPolicy.ALWAYS_ASK }
            val allowTrustedCount = policies.count { it.value == McpToolPolicy.ALLOW_WHEN_TRUSTED }
            SettingsSectionCard(
                title = stringResource(R.string.mcp_section_tools_title),
                description = stringResource(R.string.mcp_section_tools_desc),
            ) {
                Text(
                    if (policies.isEmpty()) {
                        stringResource(R.string.mcp_tools_summary_none)
                    } else {
                        stringResource(
                            R.string.mcp_tools_summary_overrides,
                            policies.size,
                            disabledCount,
                            alwaysAskCount,
                            allowTrustedCount,
                        )
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(
                        R.string.mcp_tools_count,
                        McpToolCatalog.tools.size,
                        policies.size,
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = { toolsDialogOpen = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Rounded.Tune, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.mcp_tools_manage_button))
                }
            }
        }

        item {
            SettingsSectionCard(
                title = stringResource(R.string.mcp_how_to_connect_title),
                description = stringResource(R.string.mcp_how_to_connect_desc),
            ) {
                Text(stringResource(R.string.mcp_step_1), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.mcp_step_2), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(4.dp))
                CodeBlock(importJson)
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.mcp_step_3), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.mcp_step_4), style = MaterialTheme.typography.bodyMedium)
            }
        }

        item {
            SettingsWarningCard(
                title = stringResource(R.string.mcp_security_title),
                body = stringResource(R.string.mcp_security_body),
            )
        }

        item { Spacer(Modifier.height(24.dp)) }
    }

    if (lanRiskDialog) {
        McpLanRiskDialog(
            onDismiss = { lanRiskDialog = false },
            onConfirm = {
                lanRiskDialog = false
                update { it.copy(mcp = it.mcp.copy(bindLocalhostOnly = false)) }
                displayMode = McpEndpointDisplayMode.LAN.name
            },
        )
    }

    if (toolsDialogOpen) {
        val toolsResetDoneMessage = stringResource(R.string.mcp_tools_reset_done)
        McpToolPolicyDialog(
            toolPolicies = config.mcp.toolPolicies,
            onPolicyChange = { toolName, policy ->
                update { cfg ->
                    val next = cfg.mcp.toolPolicies.toMutableMap()
                    if (policy == McpToolPolicy.DEFAULT) {
                        next.remove(toolName)
                    } else {
                        next[toolName] = policy
                    }
                    cfg.copy(mcp = cfg.mcp.copy(toolPolicies = next))
                }
            },
            onClearAll = {
                update { it.copy(mcp = it.mcp.copy(toolPolicies = emptyMap())) }
                onMessage(toolsResetDoneMessage)
            },
            onDismiss = { toolsDialogOpen = false },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun McpToolPolicyDialog(
    toolPolicies: Map<String, McpToolPolicy>,
    onPolicyChange: (toolName: String, policy: McpToolPolicy) -> Unit,
    onClearAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("ALL") }
    var selectedTool by rememberSaveable { mutableStateOf<String?>(null) }

    val filtered = remember(query, filter, toolPolicies) {
        val q = query.trim().lowercase()
        McpToolCatalog.tools.filter { tool ->
            val policy = toolPolicies[tool.name] ?: McpToolPolicy.DEFAULT
            val passFilter = when (filter) {
                "WRITE" -> tool.risk != McpToolRisk.READ
                "OVERRIDE" -> policy != McpToolPolicy.DEFAULT
                else -> true
            }
            if (!passFilter) return@filter false
            if (q.isEmpty()) return@filter true
            val title = McpToolLabels.titleZh(tool.name).lowercase()
            tool.name.lowercase().contains(q) ||
                title.contains(q) ||
                tool.description.lowercase().contains(q)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.96f)
                .fillMaxHeight(0.9f),
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp,
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    stringResource(R.string.mcp_tools_dialog_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(
                        R.string.mcp_tools_count,
                        McpToolCatalog.tools.size,
                        toolPolicies.size,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    leadingIcon = {
                        Icon(Icons.Rounded.Search, contentDescription = null)
                    },
                    label = { Text(stringResource(R.string.mcp_tools_search_label)) },
                    placeholder = { Text(stringResource(R.string.mcp_tools_search_hint)) },
                )
                Spacer(Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = filter == "ALL",
                        onClick = { filter = "ALL" },
                        label = { Text(stringResource(R.string.mcp_tools_filter_all)) },
                    )
                    FilterChip(
                        selected = filter == "WRITE",
                        onClick = { filter = "WRITE" },
                        label = { Text(stringResource(R.string.mcp_tools_filter_write)) },
                    )
                    FilterChip(
                        selected = filter == "OVERRIDE",
                        onClick = { filter = "OVERRIDE" },
                        label = { Text(stringResource(R.string.mcp_tools_filter_override)) },
                    )
                }
                if (toolPolicies.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = onClearAll,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.mcp_tools_reset_all))
                    }
                }
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                if (filtered.isEmpty()) {
                    Text(
                        stringResource(R.string.mcp_tools_search_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentPadding = PaddingValues(vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(filtered, key = { it.name }) { tool ->
                            val policy = toolPolicies[tool.name] ?: McpToolPolicy.DEFAULT
                            McpToolPolicyRow(
                                tool = tool,
                                policy = policy,
                                expanded = selectedTool == tool.name,
                                onToggleExpand = {
                                    selectedTool = if (selectedTool == tool.name) null else tool.name
                                },
                                onPolicyChange = { next -> onPolicyChange(tool.name, next) },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.mcp_tools_dialog_close))
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun McpToolPolicyRow(
    tool: McpToolDescriptor,
    policy: McpToolPolicy,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onPolicyChange: (McpToolPolicy) -> Unit,
) {
    val riskLabel = when (tool.risk) {
        McpToolRisk.READ -> stringResource(R.string.mcp_tools_risk_read)
        McpToolRisk.WRITE -> stringResource(R.string.mcp_tools_risk_write)
        McpToolRisk.HIGH_RISK -> stringResource(R.string.mcp_tools_risk_high)
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggleExpand),
        shape = MaterialTheme.shapes.medium,
        color = if (policy == McpToolPolicy.DISABLED) {
            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
        } else if (expanded) {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
        } else {
            MaterialTheme.colorScheme.surface
        },
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                McpToolLabels.titleZh(tool.name),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                tool.name,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                SettingsStatusChip(riskLabel, emphasis = tool.risk == McpToolRisk.HIGH_RISK)
                SettingsStatusChip(policy.displayName(), emphasis = policy != McpToolPolicy.DEFAULT)
            }
            if (expanded) {
                Spacer(Modifier.height(8.dp))
                Text(
                    tool.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    McpToolPolicy.entries.forEach { option ->
                        FilterChip(
                            selected = policy == option,
                            onClick = { onPolicyChange(option) },
                            label = { Text(option.displayName()) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun McpLanRiskDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    var remaining by remember { mutableIntStateOf(4) }
    var checked by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (remaining > 0) {
            delay(1_000)
            remaining--
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Warning, contentDescription = null) },
        title = { Text(stringResource(R.string.mcp_lan_risk_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.mcp_lan_risk_body))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = checked, onCheckedChange = { checked = it })
                    Text(stringResource(R.string.mcp_lan_risk_checkbox))
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = checked && remaining <= 0,
            ) {
                Text(
                    if (remaining > 0) {
                        stringResource(R.string.mcp_lan_risk_wait, remaining)
                    } else {
                        stringResource(R.string.mcp_lan_risk_confirm)
                    },
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}

private fun permissionDescription(level: McpPermissionLevel): String = when (level) {
    McpPermissionLevel.READ_ONLY -> "仅允许 AI 读取运行状态和配置，不可修改任何设置"
    McpPermissionLevel.ASK_EVERY_TIME -> "每次写操作需要你在手机上确认（推荐默认）"
    McpPermissionLevel.TRUSTED_SESSION ->
        "信任当前 MCP 握手会话的普通写操作（服务重启或会话过期后失效，不持久化）"
    McpPermissionLevel.FULL_ACCESS -> "允许 AI 执行应用内所有功能（仍不能绕过系统权限对话框）"
}

@Composable
private fun CodeBlock(content: String) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        ),
    ) {
        Text(
            content,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.padding(12.dp),
        )
    }
}
