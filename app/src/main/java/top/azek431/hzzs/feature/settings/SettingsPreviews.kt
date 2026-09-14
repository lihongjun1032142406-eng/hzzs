/**
 * 设置相关 Compose Preview 样例。
 *
 * 职责：为首页/分类卡提供静态假数据预览；不接入真实 ViewModel 或仓库。
 * 边界：仅设计时预览，不影响运行时配置与权限型能力。
 *
 * Clean Base：算法卡预览已随算法层清退移除。
 */
package top.azek431.hzzs.feature.settings

import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import top.azek431.hzzs.core.designsystem.HzzsTheme
import top.azek431.hzzs.core.model.AppConfig
import top.azek431.hzzs.core.model.AppThemeMode
import top.azek431.hzzs.core.model.ThemeConfig
import top.azek431.hzzs.feature.settings.components.SettingsCategoryCard
import top.azek431.hzzs.feature.settings.model.SettingsCategory
import top.azek431.hzzs.feature.settings.model.summary
import top.azek431.hzzs.feature.settings.screens.SettingsHomeScreen

@Preview(name = "Settings Home Light", showBackground = true, widthDp = 390, heightDp = 800)
@Composable
private fun PreviewSettingsHomeLight() {
    HzzsTheme(ThemeConfig(mode = AppThemeMode.LIGHT)) {
        Surface {
            SettingsHomeScreen(
                config = AppConfig(),
                onOpen = {},
            )
        }
    }
}

@Preview(name = "Settings Home AMOLED", showBackground = true, widthDp = 390, heightDp = 800)
@Composable
private fun PreviewSettingsHomeAmoled() {
    HzzsTheme(ThemeConfig(mode = AppThemeMode.AMOLED)) {
        Surface {
            SettingsHomeScreen(
                config = AppConfig(),
                onOpen = {},
            )
        }
    }
}

@Preview(name = "Category Card", showBackground = true, widthDp = 390)
@Composable
private fun PreviewCategoryCard() {
    HzzsTheme(ThemeConfig()) {
        Surface {
            SettingsCategoryCard(
                title = stringResource(SettingsCategory.CAPTURE.titleRes),
                description = stringResource(SettingsCategory.CAPTURE.descriptionRes),
                summary = SettingsCategory.CAPTURE.summary(AppConfig()),
                icon = SettingsCategory.CAPTURE.icon,
                onClick = {},
                compact = true,
            )
        }
    }
}

@Preview(name = "Large Font Home", showBackground = true, widthDp = 390, heightDp = 800, fontScale = 1.5f)
@Composable
private fun PreviewSettingsHomeLargeFont() {
    HzzsTheme(ThemeConfig(fontScale = 1.5f)) {
        Surface {
            SettingsHomeScreen(
                config = AppConfig(),
                onOpen = {},
            )
        }
    }
}
