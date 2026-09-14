/**
 * 设置分类首页。
 *
 * 职责：搜索 + 分组列出入口与当前摘要；点击打开子页。
 * 数据流：只读 [config]；不直接改配置。
 * 边界：返回本页不丢配置（草稿预览由 ViewModel 负责）。
 */
package top.azek431.hzzs.feature.settings.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import top.azek431.hzzs.R
import top.azek431.hzzs.core.designsystem.HzzsCallout
import top.azek431.hzzs.core.designsystem.HzzsCalloutTone
import top.azek431.hzzs.core.designsystem.LocalHzzsDimensions
import top.azek431.hzzs.core.model.AppConfig
import top.azek431.hzzs.feature.settings.components.SettingsCategoryCard
import top.azek431.hzzs.feature.settings.components.SettingsEmptyState
import top.azek431.hzzs.feature.settings.model.SettingsCategory
import top.azek431.hzzs.feature.settings.model.SettingsGroup
import top.azek431.hzzs.feature.settings.model.matchesQuery
import top.azek431.hzzs.feature.settings.model.summary

/** 设置首页列表；[selectedRoute] 供宽屏高亮当前分类。 */
@Composable
fun SettingsHomeScreen(
    config: AppConfig,
    onOpen: (SettingsCategory) -> Unit,
    modifier: Modifier = Modifier,
    selectedRoute: String? = null,
) {
    val dimensions = LocalHzzsDimensions.current
    var query by remember { mutableStateOf("") }

    val titleMap = SettingsCategory.entries.associateWith { stringResource(it.titleRes) }
    val descMap = SettingsCategory.entries.associateWith { stringResource(it.descriptionRes) }

    val developerEnabled = config.developer.enabled
    val filtered = remember(query, titleMap, descMap, developerEnabled) {
        SettingsCategory.entries.filter { cat ->
            if (cat == SettingsCategory.DEVELOPER && !developerEnabled) return@filter false
            cat.matchesQuery(query, titleMap.getValue(cat), descMap.getValue(cat))
        }
    }
    val grouped = remember(filtered) {
        SettingsGroup.entries.mapNotNull { group ->
            val items = filtered.filter { it.group == group }
            if (items.isEmpty()) null else group to items
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(dimensions.screenPadding),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text(
                stringResource(R.string.settings_home_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                leadingIcon = {
                    Icon(Icons.Rounded.Search, contentDescription = null)
                },
                placeholder = { Text(stringResource(R.string.settings_home_search_hint)) },
                label = { Text(stringResource(R.string.settings_home_search_label)) },
            )
        }
        item {
            HzzsCallout(
                text = stringResource(R.string.settings_home_preview_callout),
                tone = HzzsCalloutTone.INFO,
            )
        }
        if (grouped.isEmpty()) {
            item {
                SettingsEmptyState(
                    title = stringResource(R.string.settings_home_search_empty_title),
                    body = stringResource(R.string.settings_home_search_empty_body),
                )
            }
        } else {
            grouped.forEach { (group, categories) ->
                item(key = "group-${group.name}") {
                    Text(
                        stringResource(group.titleRes),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
                    )
                }
                items(categories, key = { it.route }) { category ->
                    SettingsCategoryCard(
                        title = titleMap.getValue(category),
                        description = descMap.getValue(category),
                        summary = category.summary(config),
                        icon = category.icon,
                        onClick = { onOpen(category) },
                        selected = selectedRoute == category.route,
                        compact = true,
                    )
                }
            }
        }
    }
}
