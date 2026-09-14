package top.azek431.hzzs.mcp.executor

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton

/**
 * MCP 工具执行器 Hilt 绑定。
 *
 * 所有 [ToolExecutor] 实现通过 [@IntoSet] 注入到 [Set&lt;ToolExecutor&gt;]，
 * [McpActionRegistry] 构造时自动聚合并按 [ToolExecutor.toolNames] 建立工具名→执行器索引。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class ToolExecutorBindings {
    @Binds
    @IntoSet
    @Singleton
    abstract fun bindRuntimeControl(impl: RuntimeControlExecutor): ToolExecutor

    @Binds
    @IntoSet
    @Singleton
    abstract fun bindSettingsWrite(impl: SettingsWriteExecutor): ToolExecutor

    @Binds
    @IntoSet
    @Singleton
    abstract fun bindMcpSelfManagement(impl: McpSelfManagementExecutor): ToolExecutor

    @Binds
    @IntoSet
    @Singleton
    abstract fun bindProfile(impl: ProfileExecutor): ToolExecutor

    @Binds
    @IntoSet
    @Singleton
    abstract fun bindDebugFrame(impl: DebugFrameExecutor): ToolExecutor

    @Binds
    @IntoSet
    @Singleton
    abstract fun bindSystem(impl: SystemExecutor): ToolExecutor
}
