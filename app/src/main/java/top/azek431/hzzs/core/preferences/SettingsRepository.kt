package top.azek431.hzzs.core.preferences

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import top.azek431.hzzs.core.logging.AppLog
import top.azek431.hzzs.core.model.*
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 配置持久化与校验。
 *
 * 职责：
 * - DataStore 读写 [AppConfig]
 * - 内存预览层（可立即作用于主题/悬浮窗）
 * - 导入导出 JSON、旧版 SharedPreferences 安全迁移
 * - 所有写入前执行 [AppConfig.validated]
 *
 * 安全：迁移与导入不得静默开启自动操作 / Root；MCP 强制 loopback。
 *
 * 注意：DataStore 文件名仍为 `hzzs_settings_v5`（历史命名），schema 版本见 [AppConfig.CURRENT_SCHEMA]。
 */
private val Context.settingsDataStore by preferencesDataStore(name = "hzzs_settings_v5")

interface SettingsRepository {
    /** 当前生效配置（预览优先，否则已保存）。 */
    val config: Flow<AppConfig>

    /** 仅已保存配置流（不含 preview）。 */
    val savedConfig: Flow<AppConfig>

    /** 读取已保存快照（不含预览）。 */
    suspend fun snapshot(): AppConfig

    /** 当前生效配置：有预览草稿时返回预览，否则与 [snapshot] 相同。 */
    suspend fun current(): AppConfig

    /** 设置内存预览；不写盘。 */
    suspend fun preview(config: AppConfig)

    /** 丢弃预览，回到已保存配置。 */
    suspend fun clearPreview()

    /** 校验后持久化，并清空预览。 */
    suspend fun save(config: AppConfig)

    /** 仅更新已保存配置，不清空预览。 */
    suspend fun updateSavedPreservingPreview(transform: (AppConfig) -> AppConfig): AppConfig

    /** 解析外部 JSON 并校验。 */
    suspend fun importJson(json: String): AppConfig

    /** 递增应用打开次数并返回新值。 */
    suspend fun incrementOpenCount(): Int

    /** 是否已经显示过捐赠提示。 */
    suspend fun isDonationPromptShown(): Boolean

    /** 标记捐赠提示已显示。 */
    suspend fun markDonationPromptShown()

    /** 导出已校验配置的 JSON 文本。 */
    fun exportJson(config: AppConfig): String

    /** 导出脱敏后的配置 JSON。 */
    fun exportJsonRedacted(config: AppConfig): String
}

/**
 * DataStore 实现：单例，进程内共享。
 *
 * 线程：DataStore 自身串行；预览用 [MutableStateFlow] 即时覆盖。
 */
@Singleton
class DataStoreSettingsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : SettingsRepository {
    private val configKey = stringPreferencesKey("config_json")
    private val legacyMigratedKey = booleanPreferencesKey("legacy_migrated")
    /** 非空时覆盖 stored，供设置页预览。 */
    private val preview = MutableStateFlow<AppConfig?>(null)
    private val openCountKey = intPreferencesKey("app_open_count")
    private val donationPromptShownKey = booleanPreferencesKey("donation_prompt_shown")
    private val migrationMutex = Mutex()
    /**
     * 最近一次已解码的磁盘配置（不含 preview）。
     * MCP tools/call 热路径的 [current]/[snapshot] 优先读此缓存，
     * 避免每次 `stored.first()` 再走一遍 DataStore map。
     */
    private val savedCache = AtomicReference<AppConfig?>(null)

    /** 磁盘配置流；首次收集时触发一次性旧版迁移。 */
    private val stored: Flow<AppConfig> = flow {
        migrateLegacyOnce()
        emitAll(context.settingsDataStore.data.map { preferences ->
            val decoded = preferences[configKey]
                ?.let { raw -> runCatching { ConfigJson.decode(raw) }.getOrElse { AppConfig() } }
                ?: AppConfig()
            savedCache.set(decoded)
            decoded
        })
    }

    override val config: Flow<AppConfig> = combine(stored, preview) { saved, temporary ->
        temporary ?: saved
    }

    override val savedConfig: Flow<AppConfig> = stored

    override suspend fun snapshot(): AppConfig {
        migrateLegacyOnce()
        val config = savedCache.get() ?: stored.first()
        syncLogging(config)
        return config
    }

    override suspend fun current(): AppConfig {
        migrateLegacyOnce()
        val temporary = preview.value
        if (temporary != null) {
            // 预览草稿即时生效；访问日志开关与草稿同步。
            top.azek431.hzzs.mcp.McpAccessLog.setEnabled(temporary.mcp.accessLogEnabled)
            return temporary
        }
        val config = savedCache.get() ?: stored.first()
        syncLogging(config)
        return config
    }

    override suspend fun preview(config: AppConfig) {
        preview.value = config.validated()
    }

    override suspend fun clearPreview() {
        preview.value = null
    }

    override suspend fun save(config: AppConfig) {
        val safe = config.validated()
        context.settingsDataStore.edit { it[configKey] = ConfigJson.encode(safe) }
        preview.value = null
        // 在 DataStore 回流前即可命中缓存，避免紧随 save 的 MCP 调用再等 disk。
        savedCache.set(safe)
        syncLogging(safe)
        AppLog.i("settings", "config saved schema=${safe.schemaVersion} developer=${safe.developer.enabled}")
    }

    override suspend fun updateSavedPreservingPreview(transform: (AppConfig) -> AppConfig): AppConfig {
        val current = snapshot()
        val safe = transform(current).validated()
        context.settingsDataStore.edit { it[configKey] = ConfigJson.encode(safe) }
        savedCache.set(safe)
        val activePreview = preview.value
        // 有草稿时日志仍跟已保存开发者开关，不因 preview 改变 logLevel。
        syncLogging(safe)
        AppLog.i(
            "settings",
            "config saved (preserve preview) schema=${safe.schemaVersion} hadPreview=${activePreview != null}",
        )
        return safe
    }

    /** 将已保存开发者日志策略同步到 [AppLog]（预览不改日志级别）。 */
    private fun syncLogging(config: AppConfig) {
        AppLog.configure(
            enabled = config.developer.enabled,
            level = config.developer.logLevel,
            ringCapacity = config.developer.logRingCapacity,
        )
        // MCP 访问日志开关与配置同步（预览路径 current() 也会调用）。
        top.azek431.hzzs.mcp.McpAccessLog.setEnabled(config.mcp.accessLogEnabled)
    }

    override suspend fun importJson(json: String): AppConfig = ConfigJson.decode(json).validated()

    override suspend fun incrementOpenCount(): Int {
        var next = 0
        context.settingsDataStore.edit { preferences ->
            next = (preferences[openCountKey] ?: 0) + 1
            preferences[openCountKey] = next
        }
        return next
    }

    override suspend fun isDonationPromptShown(): Boolean =
        context.settingsDataStore.data.first()[donationPromptShownKey] ?: false

    override suspend fun markDonationPromptShown() {
        context.settingsDataStore.edit { preferences ->
            preferences[donationPromptShownKey] = true
        }
    }

    override fun exportJson(config: AppConfig): String = ConfigJson.encode(config.validated())

    override fun exportJsonRedacted(config: AppConfig): String {
        val safe = config.validated()
        val redacted = safe.copy(
            mcp = safe.mcp.copy(
                authToken = if (safe.mcp.authToken.isNotBlank()) "***" else "",
            ),
        )
        return ConfigJson.encode(redacted)
    }

    /**
     * 一次性迁移旧 SharedPreferences（`hzzs_runtime_v2`）。
     *
     * 仅迁移低风险项（截图后端、视口、悬浮窗开关）。
     * **永不**通过迁移开启自动操作或 Root，避免升级静默提权。
     */
    private suspend fun migrateLegacyOnce(): Unit = migrationMutex.withLock {
        if (context.settingsDataStore.data.first()[legacyMigratedKey] == true) return@withLock
        val legacy = context.getSharedPreferences("hzzs_runtime_v2", Context.MODE_PRIVATE)
        val migrated = if (legacy.all.isEmpty()) {
            null
        } else {
            val mode = legacy.getString("capture_mode", "AUTO").orEmpty().uppercase()
            AppConfig(
                captureBackend = when {
                    "ACCESS" in mode -> CaptureBackend.ACCESSIBILITY
                    "MEDIA" in mode -> CaptureBackend.MEDIA_PROJECTION
                    else -> CaptureBackend.AUTO
                },
                viewport = parseLegacyViewport(legacy.getString("viewport", null)),
                overlay = OverlayConfig(
                    enabled = legacy.getBoolean("draw_overlay", true),
                    showDiagnostics = legacy.getBoolean("detailed_overlay", false),
                ),
                automation = AutomationConfig(enabled = false),
            ).validated()
        }
        context.settingsDataStore.edit { preferences ->
            if (migrated != null && preferences[configKey] == null) {
                preferences[configKey] = ConfigJson.encode(migrated)
            }
            preferences[legacyMigratedKey] = true
        }
    }

    private fun parseLegacyViewport(raw: String?): ViewportConfig {
        val parts = raw?.split(',')?.mapNotNull(String::toFloatOrNull).orEmpty()
        if (parts.size != 4) return ViewportConfig()
        return ViewportConfig(parts[0], parts[1], parts[2], parts[3]).validated()
    }
}

/**
 * 类事务的设置编辑会话：草稿 + 实时预览 + 显式提交/丢弃。
 *
 * 生命周期：
 * 1. 以 [original] 为 baseline 打开
 * 2. [update] / [replace] 改草稿并触发 [onPreview]
 * 3. [save] 持久化并关闭；失败则重新打开会话
 * 4. [discard] 清除预览并关闭
 *
 * 线程：内部 Mutex 保护 draft；回调由调用方保证线程安全。
 */
class SettingsEditSession(
    original: AppConfig,
    private val onPreview: suspend (AppConfig) -> Unit,
    private val onPersist: suspend (AppConfig) -> Unit,
    private val onClearPreview: suspend () -> Unit,
) {
    private val mutex = Mutex()
    private val baseline = original.validated()
    private var draft = baseline
    private var closed = false

    /** 当前草稿快照。 */
    suspend fun current(): AppConfig = mutex.withLock { draft }

    /**
     * 基于当前草稿做增量变换并预览。
     * 适合单字段修改；连续多字段 debounce 请用 [replace] 提交完整草稿。
     */
    suspend fun update(transform: (AppConfig) -> AppConfig): AppConfig {
        val next = mutex.withLock {
            check(!closed) { "设置编辑会话已关闭" }
            transform(draft).validated().also { draft = it }
        }
        onPreview(next)
        return next
    }

    /**
     * 用完整草稿覆盖会话内容。
     * 避免 debounce 只保留最后一个 transform 导致中间字段丢失。
     */
    suspend fun replace(next: AppConfig): AppConfig {
        val safe = mutex.withLock {
            check(!closed) { "设置编辑会话已关闭" }
            next.validated().also { draft = it }
        }
        onPreview(safe)
        return safe
    }

    /** 校验并持久化；成功后会话关闭。持久化失败会重新打开以便重试。 */
    suspend fun save(): AppConfig {
        val safe = mutex.withLock {
            check(!closed) { "设置编辑会话已关闭" }
            closed = true
            draft.validated()
        }
        runCatching { onPersist(safe) }.onFailure {
            mutex.withLock { closed = false }
        }.getOrThrow()
        return safe
    }

    /** 丢弃草稿、清除预览，恢复 baseline。 */
    suspend fun discard(): AppConfig {
        val discardedDraft = mutex.withLock {
            if (closed) null else draft.also {
                draft = baseline
                closed = true
            }
        }
        if (discardedDraft == null) return baseline
        runCatching { onClearPreview() }.onFailure {
            mutex.withLock {
                draft = discardedDraft
                closed = false
            }
        }.getOrThrow()
        return baseline
    }

    /** 草稿是否相对 baseline 有变化。 */
    suspend fun hasChanges(): Boolean = mutex.withLock { draft != baseline }
}

/**
 * 清洗视口矩形：finite、落在合法区间，且宽高至少 5%。
 * 非法时回退全屏默认。
 */
fun ViewportConfig.validated(): ViewportConfig {
    val l = left.finiteOr(0f).coerceIn(0f, 0.95f)
    val t = top.finiteOr(0f).coerceIn(0f, 0.95f)
    val r = right.finiteOr(1f).coerceIn(0.05f, 1f)
    val b = bottom.finiteOr(1f).coerceIn(0.05f, 1f)
    return if (r - l >= 0.05f && b - t >= 0.05f) {
        ViewportConfig(l, t, r, b)
    } else {
        ViewportConfig()
    }
}

/**
 * 将任意 [AppConfig] 清洗为可安全落盘/生效的快照。
 *
 * 关键策略：
 * - 数值 clamp 到产品允许区间
 * - 自动操作：免责声明版本不足时强制 `enabled=false`
 * - 包名与默认白名单求交
 * - MCP 端口 clamp；`bindLocalhostOnly` 保留用户选择（默认 true）
 * - schema 写回 [AppConfig.CURRENT_SCHEMA]
 *
 * 注意：本函数**不会**单独拦截「已接受免责声明后的 enabled=true」。
 * 外部 JSON / MCP 摄入请再经 [hardenedForExternalIngest]，避免静默开启自动操作、局域网监听或自提 MCP 权限。
 */
fun AppConfig.validated(): AppConfig {
    // 清洗包名；开启限制时列表不能为空，空则回退建议包。不与内置集合求交。
    val packages: Set<String> = automation.allowedPackages
        .asSequence()
        .map(String::trim)
        .filter { it.isNotBlank() && it.length <= 180 && it.none(Char::isWhitespace) }
        .toSet()
        .let { cleaned ->
            if (automation.restrictPackages && cleaned.isEmpty()) {
                AutomationConfig.SUGGESTED_PACKAGES
            } else {
                cleaned
            }
        }
        .take(32)
        .toSet()
    return copy(
        schemaVersion = AppConfig.CURRENT_SCHEMA,
        theme = theme.copy(
            fontScale = theme.fontScale.finiteOr(1f).coerceIn(0.80f, 1.50f),
            cornerScale = theme.cornerScale.finiteOr(1f).coerceIn(0f, 2f),
            spacingScale = theme.spacingScale.finiteOr(1f).coerceIn(0.75f, 1.50f),
            animationScale = theme.animationScale.finiteOr(1f).coerceIn(0f, 2f),
        ),
        viewport = viewport.validated(),
        overlay = overlay.copy(
            backgroundAlpha = overlay.backgroundAlpha.finiteOr(0.70f).coerceIn(0.10f, 1f),
            scale = overlay.scale.finiteOr(1f).coerceIn(0.60f, 2f),
            strokeWidthDp = overlay.strokeWidthDp.finiteOr(2f).coerceIn(0.5f, 8f),
            textScale = overlay.textScale.finiteOr(1f).coerceIn(0.75f, 2f),
        ),
        automation = automation.copy(
            // 免责声明未达当前版本时强制关闭，导入也走同一路径。
            enabled = automation.enabled &&
                automation.disclaimerAcceptedVersion >= AppConfig.DISCLAIMER_VERSION,
            gestureBackend = automation.gestureBackend,
            restrictPackages = automation.restrictPackages,
            allowedPackages = packages,
            maxActionsPerSecond = automation.maxActionsPerSecond.coerceIn(1, 8),
            retryLimit = automation.retryLimit.coerceIn(0, 2),
            disclaimerAcceptedVersion = automation.disclaimerAcceptedVersion.coerceAtLeast(0),
            // 自动复活与障碍连点独立；默认开，导入允许保持（风险低于连跳）。
            autoReviveEnabled = automation.autoReviveEnabled,
        ),
        mcp = mcp.copy(
            port = mcp.port.coerceIn(1024, 65535),
            // 默认 true；false 表示用户明确允许局域网（0.0.0.0）。外部摄入另经 harden。
            bindLocalhostOnly = mcp.bindLocalhostOnly,
            accessLogEnabled = mcp.accessLogEnabled,
            // requireAuth 默认 false；authToken 只保留安全 hex，长度上限防止异常配置。
            authToken = mcp.authToken
                .trim()
                .filter { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }
                .lowercase()
                .take(128),
            // 仅保留已知工具名 + 非 DEFAULT 覆盖；未知键丢弃，防止脏配置膨胀。
            toolPolicies = mcp.toolPolicies
                .filterKeys { name ->
                    name.isNotBlank() &&
                        name.length <= 64 &&
                        name.all { ch -> ch.isLetterOrDigit() || ch == '_' }
                }
                .filterValues { it != McpToolPolicy.DEFAULT }
                .toSortedMap()
                .let { sorted ->
                    // 已知工具名延迟由 MCP 目录校验；此处只做形态收敛，避免 core 依赖 mcp 包。
                    sorted.entries.take(128).associate { it.toPair() }
                },
        ),
        developer = developer.copy(
            frameRateLimit = developer.frameRateLimit.coerceIn(1, 120),
            logLevel = developer.logLevel,
            logRingCapacity = developer.logRingCapacity.coerceIn(500, 3000),
        ),
        onboarding = onboarding.copy(
            acceptedDisclaimerVersion = onboarding.acceptedDisclaimerVersion.coerceAtLeast(0),
        ),
    )
}

/**
 * 外部摄入时用户可显式同意的「升权」项。
 *
 * 默认全 false：导入/MCP 不得静默打开自动操作或局域网 MCP。
 * UI 在检测到导入 JSON 会升权时弹风险确认，再把对应字段设为 true。
 */
data class ExternalIngestElevations(
    /** 允许 candidate 将自动操作从关→开（仍须免责声明版本足够）。 */
    val allowEnableAutomation: Boolean = false,
    /** 允许 candidate 将 MCP 从仅 loopback 升到局域网（`bindLocalhostOnly=false`）。 */
    val allowEnableMcpLan: Boolean = false,
)

/**
 * 检测 [candidate] 相对 [baseline] 在 harden 默认规则下会被挡掉的升权项，供导入 UI 询问。
 */
fun AppConfig.externalIngestElevationsNeeded(baseline: AppConfig): ExternalIngestElevations {
    val base = baseline.validated()
    val candidate = validated()
    return ExternalIngestElevations(
        allowEnableAutomation = candidate.automation.enabled && !base.automation.enabled,
        allowEnableMcpLan = !candidate.mcp.bindLocalhostOnly && base.mcp.bindLocalhostOnly,
    )
}

/**
 * 外部摄入（配置导入、MCP `save_settings`/`preview_settings`）相对 [baseline] 的安全收敛。
 *
 * 硬规则（对齐 CLAUDE / SECURITY）：
 * - 不得静默把自动操作从关→开；若 baseline 已开，可保留；用户确认后可经 [elevations] 放行；
 * - 不得静默打开 MCP 局域网监听；用户确认后可经 [elevations] 放行；
 * - 不得自提 MCP `permissionLevel` / 不得静默打开 `mcp.enabled` / `allowDebugFrames`；
 * - 不得静默放宽 MCP `toolPolicies`（只能更严：DEFAULT→ALWAYS_ASK/DISABLED 等，见 [mergeToolPoliciesStrict]）；
 * - 不得静默打开开发者选项或写入 `forceCaptureBackend`（避免升权截图后端）；
 * - 截图后端不得从低权限静默升到 Root/Shizuku/无障碍（保持 baseline 或更低风险）。
 * - 手势后端不得从低风险静默升到 Shizuku/Root（保持 baseline 或更低风险）。
 *
 * 调用方应先 [validated] 再 harden，或对本函数返回值再 `validated()`。
 */
fun AppConfig.hardenedForExternalIngest(
    baseline: AppConfig,
    elevations: ExternalIngestElevations = ExternalIngestElevations(),
): AppConfig {
    val base = baseline.validated()
    val candidate = validated()

    val automation = candidate.automation.copy(
        // 外部路径不得静默开启；baseline 已开可保持；或用户确认 elevations。
        enabled = candidate.automation.enabled &&
            (base.automation.enabled || elevations.allowEnableAutomation),
        // 默认：免责版本不得高于 baseline（防外部伪造「已接受」）。
        // 用户确认 allowEnableAutomation 时，允许抬到 candidate（否则 validated()
        // 会因 disclaimer < DISCLAIMER_VERSION 再次把 enabled 关掉）。
        disclaimerAcceptedVersion = if (elevations.allowEnableAutomation) {
            maxOf(
                base.automation.disclaimerAcceptedVersion,
                candidate.automation.disclaimerAcceptedVersion,
            )
        } else {
            minOf(
                candidate.automation.disclaimerAcceptedVersion,
                base.automation.disclaimerAcceptedVersion,
            )
        },
        gestureBackend = saferGestureBackend(
            base.automation.gestureBackend,
            candidate.automation.gestureBackend,
        ),
        // 外部不得静默关闭包限制；开启限制时列表不得悄悄扩大。
        restrictPackages = candidate.automation.restrictPackages || base.automation.restrictPackages,
        allowedPackages = if (base.automation.restrictPackages || candidate.automation.restrictPackages) {
            val intersected = candidate.automation.allowedPackages.intersect(base.automation.allowedPackages)
            intersected.ifEmpty {
                base.automation.allowedPackages.ifEmpty { AutomationConfig.SUGGESTED_PACKAGES }
            }
        } else {
            candidate.automation.allowedPackages
        },
    )

    val mcp = candidate.mcp.copy(
        enabled = candidate.mcp.enabled && base.mcp.enabled,
        // 权限级只允许降级或持平，禁止外部自提。
        permissionLevel = minPermission(base.mcp.permissionLevel, candidate.mcp.permissionLevel),
        allowDebugFrames = candidate.mcp.allowDebugFrames && base.mcp.allowDebugFrames,
        // 外部不得静默关闭鉴权；也不得改写/清空配对令牌。
        requireAuth = candidate.mcp.requireAuth || base.mcp.requireAuth,
        authToken = base.mcp.authToken,
        // 默认保持 loopback；仅 baseline 已开局域网，或用户确认 elevations 时允许 false。
        bindLocalhostOnly = when {
            candidate.mcp.bindLocalhostOnly -> true
            !base.mcp.bindLocalhostOnly -> false
            elevations.allowEnableMcpLan -> false
            else -> true
        },
        toolPolicies = mergeToolPoliciesStrict(base.mcp.toolPolicies, candidate.mcp.toolPolicies),
    )

    val developer = candidate.developer.copy(
        enabled = candidate.developer.enabled && base.developer.enabled,
        forceCaptureBackend = if (base.developer.enabled) {
            // 开发者已开时，允许改 force，但仍受运行时 isSupported fail-soft。
            candidate.developer.forceCaptureBackend
        } else {
            null
        },
    )

    val captureBackend = saferCaptureBackend(base.captureBackend, candidate.captureBackend)

    return candidate.copy(
        automation = automation,
        mcp = mcp,
        developer = developer,
        captureBackend = captureBackend,
    ).validated()
}

/** MCP 权限级序：数字越大权限越高。 */
private fun mcpPermissionRank(level: McpPermissionLevel): Int =
    when (level) {
        McpPermissionLevel.READ_ONLY -> 0
        McpPermissionLevel.ASK_EVERY_TIME -> 1
        McpPermissionLevel.TRUSTED_SESSION -> 2
        McpPermissionLevel.FULL_ACCESS -> 3
    }

private fun minPermission(
    baseline: McpPermissionLevel,
    candidate: McpPermissionLevel,
): McpPermissionLevel =
    if (mcpPermissionRank(candidate) <= mcpPermissionRank(baseline)) candidate else baseline

/**
 * 工具策略「宽松度」：数字越大越宽松。
 * 外部摄入只能取更严（数字更小）的一侧。
 */
private fun mcpToolPolicyRank(policy: McpToolPolicy): Int =
    when (policy) {
        McpToolPolicy.DISABLED -> 0
        McpToolPolicy.ALWAYS_ASK -> 1
        McpToolPolicy.DEFAULT -> 2
        McpToolPolicy.ALLOW_WHEN_TRUSTED -> 3
    }

/**
 * 合并工具策略：对每个工具取更严策略；baseline 中已 DISABLED 的不得被外部打开。
 * 仅输出非 DEFAULT 条目。
 */
internal fun mergeToolPoliciesStrict(
    baseline: Map<String, McpToolPolicy>,
    candidate: Map<String, McpToolPolicy>,
): Map<String, McpToolPolicy> {
    val keys = baseline.keys + candidate.keys
    val out = linkedMapOf<String, McpToolPolicy>()
    for (key in keys) {
        val base = baseline[key] ?: McpToolPolicy.DEFAULT
        val cand = candidate[key] ?: McpToolPolicy.DEFAULT
        val strict =
            if (mcpToolPolicyRank(cand) <= mcpToolPolicyRank(base)) cand else base
        if (strict != McpToolPolicy.DEFAULT) {
            out[key] = strict
        }
    }
    return out.toSortedMap()
}

/**
 * 截图后端风险序：AUTO/MP 最低；无障碍中等；Shizuku/Root 最高。
 * 外部摄入不得升到比 baseline 更高风险的后端。
 */
private fun saferCaptureBackend(
    baseline: CaptureBackend,
    candidate: CaptureBackend,
): CaptureBackend {
    fun rank(b: CaptureBackend): Int = when (b) {
        CaptureBackend.AUTO -> 0
        CaptureBackend.MEDIA_PROJECTION -> 1
        CaptureBackend.ACCESSIBILITY -> 2
        CaptureBackend.SHIZUKU -> 3
        CaptureBackend.ROOT -> 4
    }
    return if (rank(candidate) <= rank(baseline)) candidate else baseline
}

/**
 * 手势后端风险序：AUTO 最低；无障碍中等；Shizuku/Root 最高。
 * 外部摄入不得升到比 baseline 更高风险的后端。
 */
internal fun saferGestureBackend(
    baseline: GestureBackend,
    candidate: GestureBackend,
): GestureBackend {
    fun rank(b: GestureBackend): Int = when (b) {
        GestureBackend.AUTO -> 0
        GestureBackend.ACCESSIBILITY -> 1
        GestureBackend.SHIZUKU -> 2
        GestureBackend.ROOT -> 3
    }
    return if (rank(candidate) <= rank(baseline)) candidate else baseline
}

/** 非 finite 浮点回退为默认值。 */
private fun Float.finiteOr(fallback: Float): Float = if (isFinite()) this else fallback

/**
 * 严格、有体积上限的配置 JSON 编解码。
 *
 * 用于备份、导入与 MCP 设置通道。解码后必经 [AppConfig.validated]。
 * 体积上限 [MAX_CONFIG_BYTES]，防止超大 payload。
 */
object ConfigJson {
    fun encode(config: AppConfig): String {
        val safe = config.validated()
        return JSONObject().apply {
            put("schemaVersion", safe.schemaVersion)
            put("theme", themeJson(safe.theme))
            put("overlay", overlayJson(safe.overlay))
            put("captureBackend", safe.captureBackend.name)
            put("viewport", JSONObject().apply {
                put("left", safe.viewport.left.toDouble())
                put("top", safe.viewport.top.toDouble())
                put("right", safe.viewport.right.toDouble())
                put("bottom", safe.viewport.bottom.toDouble())
            })
            put("automation", JSONObject().apply {
                put("enabled", safe.automation.enabled)
                put("disclaimerAcceptedVersion", safe.automation.disclaimerAcceptedVersion)
                put("gestureBackend", safe.automation.gestureBackend.name)
                put("restrictPackages", safe.automation.restrictPackages)
                put("allowedPackages", JSONArray(safe.automation.allowedPackages.sorted()))
                put("maxActionsPerSecond", safe.automation.maxActionsPerSecond)
                put("retryLimit", safe.automation.retryLimit)
                put("autoReviveEnabled", safe.automation.autoReviveEnabled)
            })
            put("mcp", JSONObject().apply {
                put("enabled", safe.mcp.enabled)
                put("permissionLevel", safe.mcp.permissionLevel.name)
                put("port", safe.mcp.port)
                put("bindLocalhostOnly", safe.mcp.bindLocalhostOnly)
                put("allowDebugFrames", safe.mcp.allowDebugFrames)
                put("requireAuth", safe.mcp.requireAuth)
                put("accessLogEnabled", safe.mcp.accessLogEnabled)
                // 配对令牌仅存 DataStore；导出 JSON 同样写入（用户备份），日志路径须脱敏。
                put("authToken", safe.mcp.authToken)
                put(
                    "toolPolicies",
                    JSONObject().apply {
                        safe.mcp.toolPolicies.forEach { (name, policy) ->
                            put(name, policy.name)
                        }
                    },
                )
            })
            put("developer", JSONObject().apply {
                put("enabled", safe.developer.enabled)
                safe.developer.forceCaptureBackend?.let { put("forceCaptureBackend", it.name) }
                put("saveDebugFrames", safe.developer.saveDebugFrames)
                put("showCoordinateGrid", safe.developer.showCoordinateGrid)
                put("frameRateLimit", safe.developer.frameRateLimit)
                put("logLevel", safe.developer.logLevel.name)
                put("logRingCapacity", safe.developer.logRingCapacity)
            })
            put("onboarding", JSONObject().apply {
                put("completed", safe.onboarding.completed)
                put("acceptedDisclaimerVersion", safe.onboarding.acceptedDisclaimerVersion)
            })
            put("update", JSONObject().apply {
                put("channel", safe.update.channel.name)
                put("autoCheck", safe.update.autoCheck)
                put("wifiOnly", safe.update.wifiOnly)
                put("sourcePreference", safe.update.sourcePreference.name)
                safe.update.ignoredVersionCode?.let { put("ignoredVersionCode", it) }
            })
        }.toString(2)
    }

    fun decode(raw: String): AppConfig {
        require(raw.toByteArray(Charsets.UTF_8).size <= MAX_CONFIG_BYTES) { "配置文件过大" }
        val root = JSONObject(raw)
        val defaults = AppConfig()
        val theme = root.optJSONObject("theme")
        val overlay = root.optJSONObject("overlay")
        val viewport = root.optJSONObject("viewport")
        val automation = root.optJSONObject("automation")
        val mcp = root.optJSONObject("mcp")
        val developer = root.optJSONObject("developer")
        val onboarding = root.optJSONObject("onboarding")
        val update = root.optJSONObject("update")
        return defaults.copy(
            theme = defaults.theme.copy(
                mode = enumOr(theme?.optString("mode"), defaults.theme.mode),
                preset = enumOr(theme?.optString("preset"), defaults.theme.preset),
                customSeed = theme?.optInt("customSeed", defaults.theme.customSeed) ?: defaults.theme.customSeed,
                dynamicColorEnabled = theme?.optBoolean("dynamicColorEnabled", true) ?: true,
                fontScale = theme?.optDouble("fontScale", 1.0)?.toFloat() ?: 1f,
                cornerScale = theme?.optDouble("cornerScale", 1.0)?.toFloat() ?: 1f,
                spacingScale = theme?.optDouble("spacingScale", 1.0)?.toFloat() ?: 1f,
                animationScale = theme?.optDouble("animationScale", 1.0)?.toFloat() ?: 1f,
                reduceMotion = theme?.optBoolean("reduceMotion", false) ?: false,
                highContrast = theme?.optBoolean("highContrast", false) ?: false,
            ),
            overlay = defaults.overlay.copy(
                enabled = overlay?.optBoolean("enabled", true) ?: true,
                style = enumOr(overlay?.optString("style"), defaults.overlay.style),
                theme = enumOr(overlay?.optString("theme"), defaults.overlay.theme),
                customColor = overlay?.optInt("customColor", defaults.overlay.customColor) ?: defaults.overlay.customColor,
                backgroundAlpha = overlay?.optDouble("backgroundAlpha", 0.70)?.toFloat() ?: 0.70f,
                scale = overlay?.optDouble("scale", 1.0)?.toFloat() ?: 1f,
                strokeWidthDp = overlay?.optDouble("strokeWidthDp", 2.0)?.toFloat() ?: 2f,
                textScale = overlay?.optDouble("textScale", 1.0)?.toFloat() ?: 1f,
                orientation = enumOr(overlay?.optString("orientation"), defaults.overlay.orientation),
                showBoxes = overlay?.optBoolean("showBoxes", true) ?: true,
                persistBoxes = overlay?.optBoolean("persistBoxes", true) ?: true,
                showText = overlay?.optBoolean("showText", true) ?: true,
                showFps = overlay?.optBoolean("showFps", false) ?: false,
                showConfidence = overlay?.optBoolean("showConfidence", false) ?: false,
                showDiagnostics = overlay?.optBoolean("showDiagnostics", false) ?: false,
                clickThrough = overlay?.optBoolean("clickThrough", true) ?: true,
                snapToEdge = overlay?.optBoolean("snapToEdge", true) ?: true,
                lockPosition = overlay?.optBoolean("lockPosition", false) ?: false,
            ),
            captureBackend = enumOr(root.optString("captureBackend"), defaults.captureBackend),
            viewport = ViewportConfig(
                left = viewport?.optDouble("left", 0.0)?.toFloat() ?: 0f,
                top = viewport?.optDouble("top", 0.0)?.toFloat() ?: 0f,
                right = viewport?.optDouble("right", 1.0)?.toFloat() ?: 1f,
                bottom = viewport?.optDouble("bottom", 1.0)?.toFloat() ?: 1f,
            ),
            automation = defaults.automation.copy(
                enabled = automation?.optBoolean("enabled", false) ?: false,
                disclaimerAcceptedVersion = automation?.optInt("disclaimerAcceptedVersion", 0) ?: 0,
                gestureBackend = enumOr(
                    automation?.optString("gestureBackend"),
                    defaults.automation.gestureBackend,
                ),
                // 缺字段默认 false：与产品「默认不限制包名」一致。
                restrictPackages = automation?.optBoolean("restrictPackages", false) ?: false,
                allowedPackages = automation?.optJSONArray("allowedPackages").toStringSet()
                    .ifEmpty { defaults.automation.allowedPackages },
                maxActionsPerSecond = automation?.optInt("maxActionsPerSecond", 4) ?: 4,
                retryLimit = automation?.optInt("retryLimit", 1) ?: 1,
                autoReviveEnabled = automation?.optBoolean("autoReviveEnabled", true) ?: true,
            ),
            mcp = defaults.mcp.copy(
                enabled = mcp?.optBoolean("enabled", false) ?: false,
                permissionLevel = enumOr(mcp?.optString("permissionLevel"), defaults.mcp.permissionLevel),
                port = mcp?.optInt("port", defaults.mcp.port) ?: defaults.mcp.port,
                bindLocalhostOnly = mcp?.optBoolean("bindLocalhostOnly", true) ?: true,
                allowDebugFrames = mcp?.optBoolean("allowDebugFrames", false) ?: false,
                // 缺字段跟随产品默认 false（同机免鉴权）；已落盘的 true/false 原样读取。
                requireAuth = mcp?.optBoolean("requireAuth", defaults.mcp.requireAuth)
                    ?: defaults.mcp.requireAuth,
                accessLogEnabled = mcp?.optBoolean(
                    "accessLogEnabled",
                    defaults.mcp.accessLogEnabled,
                ) ?: defaults.mcp.accessLogEnabled,
                authToken = mcp?.optString("authToken")?.takeIf { it.isNotBlank() }.orEmpty(),
                toolPolicies = decodeToolPolicies(mcp?.optJSONObject("toolPolicies")),
            ),
            developer = defaults.developer.copy(
                enabled = developer?.optBoolean("enabled", false) ?: false,
                forceCaptureBackend = developer?.optString("forceCaptureBackend")
                    ?.takeIf(String::isNotBlank)
                    ?.let { raw -> CaptureBackend.entries.firstOrNull { it.name == raw } },
                saveDebugFrames = developer?.optBoolean("saveDebugFrames", false) ?: false,
                showCoordinateGrid = developer?.optBoolean("showCoordinateGrid", false) ?: false,
                frameRateLimit = developer?.optInt("frameRateLimit", 60) ?: 60,
                logLevel = developer?.optString("logLevel")
                    ?.takeIf(String::isNotBlank)
                    ?.let { raw -> AppLogLevel.entries.firstOrNull { it.name == raw } }
                    ?: AppLogLevel.INFO,
                logRingCapacity = developer?.optInt(
                    "logRingCapacity",
                    defaults.developer.logRingCapacity,
                ) ?: defaults.developer.logRingCapacity,
            ),
            onboarding = defaults.onboarding.copy(
                completed = onboarding?.optBoolean("completed", false) ?: false,
                acceptedDisclaimerVersion = onboarding?.optInt("acceptedDisclaimerVersion", 0) ?: 0,
            ),
            update = defaults.update.copy(
                channel = enumOr(update?.optString("channel"), defaults.update.channel),
                autoCheck = update?.optBoolean("autoCheck", true) ?: true,
                wifiOnly = update?.optBoolean("wifiOnly", true) ?: true,
                sourcePreference = enumOr(
                    update?.optString("sourcePreference"),
                    defaults.update.sourcePreference,
                ),
                ignoredVersionCode = update?.takeIf { it.has("ignoredVersionCode") }
                    ?.optLong("ignoredVersionCode"),
            ),
        ).validated()
    }

    private fun themeJson(theme: ThemeConfig) = JSONObject().apply {
        put("mode", theme.mode.name)
        put("preset", theme.preset.name)
        put("customSeed", theme.customSeed)
        put("dynamicColorEnabled", theme.dynamicColorEnabled)
        put("fontScale", theme.fontScale.toDouble())
        put("cornerScale", theme.cornerScale.toDouble())
        put("spacingScale", theme.spacingScale.toDouble())
        put("animationScale", theme.animationScale.toDouble())
        put("reduceMotion", theme.reduceMotion)
        put("highContrast", theme.highContrast)
    }

    private fun overlayJson(overlay: OverlayConfig) = JSONObject().apply {
        put("enabled", overlay.enabled)
        put("style", overlay.style.name)
        put("theme", overlay.theme.name)
        put("customColor", overlay.customColor)
        put("backgroundAlpha", overlay.backgroundAlpha.toDouble())
        put("scale", overlay.scale.toDouble())
        put("strokeWidthDp", overlay.strokeWidthDp.toDouble())
        put("textScale", overlay.textScale.toDouble())
        put("orientation", overlay.orientation.name)
        put("showBoxes", overlay.showBoxes)
        put("persistBoxes", overlay.persistBoxes)
        put("showText", overlay.showText)
        put("showFps", overlay.showFps)
        put("showConfidence", overlay.showConfidence)
        put("showDiagnostics", overlay.showDiagnostics)
        put("clickThrough", overlay.clickThrough)
        put("snapToEdge", overlay.snapToEdge)
        put("lockPosition", overlay.lockPosition)
    }

    private fun decodeToolPolicies(obj: JSONObject?): Map<String, McpToolPolicy> {
        if (obj == null) return emptyMap()
        val out = linkedMapOf<String, McpToolPolicy>()
        val keys = obj.keys()
        var count = 0
        while (keys.hasNext() && count < 128) {
            val name = keys.next()
            if (name.isNullOrBlank() || name.length > 64) continue
            if (!name.all { ch -> ch.isLetterOrDigit() || ch == '_' }) continue
            val policy = enumOr(obj.optString(name), McpToolPolicy.DEFAULT)
            if (policy != McpToolPolicy.DEFAULT) {
                out[name] = policy
            }
            count++
        }
        return out.toSortedMap()
    }

    private fun JSONArray?.toStringSet(): Set<String> = buildSet {
        val array = this@toStringSet ?: return@buildSet
        repeat(minOf(array.length(), 64)) {
            array.optString(it).trim().takeIf(String::isNotBlank)?.let(::add)
        }
    }

    private inline fun <reified T : Enum<T>> enumOr(raw: String?, fallback: T): T =
        enumValues<T>().firstOrNull { it.name == raw } ?: fallback

    private const val MAX_CONFIG_BYTES = 256 * 1024
}

/**
 * 生成两份配置的人类可读差异标签（中文）。
 * 供导入确认与 MCP 审计界面使用，不返回字段级 diff。
 */
fun AppConfig.diff(other: AppConfig): List<String> = buildList {
    if (theme != other.theme) add("外观主题")
    if (overlay != other.overlay) add("悬浮窗")
    if (captureBackend != other.captureBackend) add("截图方式")
    if (viewport != other.viewport) add("游戏画面区域")
    if (automation != other.automation) add("自动操作")
    if (mcp != other.mcp) add("MCP 服务")
    if (developer != other.developer) add("开发者设置")
    if (update != other.update) add("更新设置")
}

@Module
@InstallIn(SingletonComponent::class)
abstract class SettingsBindings {
    @Binds
    abstract fun bindSettingsRepository(impl: DataStoreSettingsRepository): SettingsRepository
}
