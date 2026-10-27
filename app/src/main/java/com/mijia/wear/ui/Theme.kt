package com.mijia.wear.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material.ChipColors
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.Colors
import androidx.wear.compose.material.MaterialTheme

/**
 * 全局设计 token：整套界面（黑底圆屏）共用一份配色，避免各页各写各的。
 *
 * 取色原则：
 * - 底色纯黑（AMLED 圆屏省电、边缘无发灰）；
 * - 分层靠灰阶（Surface / SurfaceHigh）而不是加边框；
 * - 强调色统一用米家蓝，选中/进度/滑块/开关都跟着它走；
 * - 米家橙只留给品牌处（首页标题），不做功能色。
 */
object Mj {
    /** 主强调色：选中胶囊、开关、滑块、进度条、页头。 */
    val Accent = Color(0xFF4C8BF5)

    /** 强调色的亮端（渐变、数值高亮）。 */
    val AccentSoft = Color(0xFF8FB8FF)

    /** 米家品牌橙，仅用于首页标题。 */
    val Brand = Color(0xFFFF6700)

    /** 成功 / 在线状态点。 */
    val Success = Color(0xFF3DDC84)

    /** 危险操作（退出登录）。 */
    val Danger = Color(0xFFFF8A80)

    /** 页面底色。 */
    val Bg = Color.Black

    /** 一级表面：卡片、胶囊、图标底圆。 */
    val Surface = Color(0xFF1B1B1D)

    /** 二级表面：按下/选中态。 */
    val SurfaceHigh = Color(0xFF2C2C2E)

    /** 细描边：Toast、图标底圆的轻微分隔。 */
    val Stroke = Color(0x33FFFFFF)

    /** 主文字。 */
    val Text = Color(0xFFF2F2F7)

    /** 次要文字：副标题、说明。 */
    val TextDim = Color(0xFF9E9EA5)

    /** 弱文字：加载提示、占位。 */
    val TextMute = Color(0xFF6E6E73)

    /** 禁用态文字。 */
    val TextDisabled = Color(0xFF777777)
}

/**
 * 应用主题：给 Wear Material 传一套深色配色。
 * 传了 primary 后，滑块/开关/进度条等默认吃 primary 的组件会自动变蓝。
 */
@Composable
fun MiHomeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colors = Colors(
            primary = Mj.Accent,
            secondary = Mj.AccentSoft,
            background = Mj.Bg,
            surface = Mj.Surface,
            error = Mj.Danger,
            onPrimary = Color.White,
            onSecondary = Mj.Bg,
            onBackground = Mj.Text,
            onSurface = Mj.Text,
            onSurfaceVariant = Mj.TextDim,
            onError = Mj.Bg,
        ),
        content = content,
    )
}

/** 统一的条目配色：深色卡片底、白字主标题、灰字副标题，全 App 共用。 */
@Composable
fun mjChipColors(
    background: Color = Mj.Surface,
    content: Color = Mj.Text,
    secondary: Color = Mj.TextDim,
): ChipColors = ChipDefaults.chipColors(
    backgroundColor = background,
    contentColor = content,
    secondaryContentColor = secondary,
    iconColor = content,
    // 禁用态单独给深底 + 灰字，避免默认拿 background（纯黑）当文字色导致看不见
    disabledBackgroundColor = Mj.SurfaceHigh,
    disabledContentColor = Mj.TextDim,
    disabledSecondaryContentColor = Mj.TextMute,
    disabledIconColor = Mj.TextDim,
)
