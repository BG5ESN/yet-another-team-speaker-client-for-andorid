package dev.tsdroid.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/* ───────────────── 配色策略 ─────────────────
 * 点缀色：仅 #5F7BD9（蓝紫）—— primary，用于强调按钮、图标高亮、选中态、启动页标题
 * 其余：全部中性灰/白/黑，不带任何色相偏移（原版"按种子色 HSL 推导整套"的做法已废弃）
 * 语义色：error 保留 Material 默认；状态点色见 UserItem / 悬浮窗，不参与主题推导
 * ─────────────────────────────────────────── */

/** 点缀色（唯一彩色）。想换色只改这一处。 */
private val Accent = Color(0xFF5F7BD9)

private val AppTypography = Typography(
    displayLarge = TextStyle(fontSize = 57.sp, fontWeight = FontWeight.Normal, lineHeight = 64.sp, letterSpacing = (-0.25).sp),
    displayMedium = TextStyle(fontSize = 45.sp, fontWeight = FontWeight.Normal, lineHeight = 52.sp),
    displaySmall = TextStyle(fontSize = 36.sp, fontWeight = FontWeight.Normal, lineHeight = 44.sp),
    headlineLarge = TextStyle(fontSize = 32.sp, fontWeight = FontWeight.Normal, lineHeight = 40.sp),
    headlineMedium = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Normal, lineHeight = 36.sp),
    headlineSmall = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Normal, lineHeight = 32.sp),
    titleLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Medium, lineHeight = 28.sp),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium, lineHeight = 24.sp, letterSpacing = 0.15.sp),
    titleSmall = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, lineHeight = 20.sp, letterSpacing = 0.1.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Normal, lineHeight = 24.sp, letterSpacing = 0.5.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Normal, lineHeight = 20.sp, letterSpacing = 0.25.sp),
    bodySmall = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Normal, lineHeight = 16.sp, letterSpacing = 0.4.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, lineHeight = 20.sp, letterSpacing = 0.1.sp),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, lineHeight = 16.sp, letterSpacing = 0.5.sp),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, lineHeight = 16.sp, letterSpacing = 0.5.sp),
)

private val LightNeutralScheme = lightColorScheme(
    // 点缀
    primary = Accent,
    onPrimary = Color(0xFFFFFFFF),            // 蓝底配白字（对比 3.95:1，图标/大字号达标）
    primaryContainer = Color(0xFFDCE2F8),     // 淡蓝底（选中态/标签）
    onPrimaryContainer = Color(0xFF14224F),
    inversePrimary = Color(0xFFB3C0F2),
    // 次级/三级：灰
    secondary = Color(0xFF5C5C5C),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE6E6E6),
    onSecondaryContainer = Color(0xFF1F1F1F),
    tertiary = Color(0xFF6B6B6B),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFEAEAEA),
    onTertiaryContainer = Color(0xFF242424),
    // 语义
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    // 底/面/字
    background = Color(0xFFFCFCFC),
    onBackground = Color(0xFF1A1A1A),
    surface = Color(0xFFFCFCFC),
    onSurface = Color(0xFF1A1A1A),
    surfaceVariant = Color(0xFFE8E8E8),
    onSurfaceVariant = Color(0xFF4A4A4A),
    outline = Color(0xFF8C8C8C),
    outlineVariant = Color(0xFFD8D8D8),
    inverseSurface = Color(0xFF303030),
    inverseOnSurface = Color(0xFFF2F2F2),
    surfaceDim = Color(0xFFDEDEDE),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7F7F7),
    surfaceContainer = Color(0xFFF2F2F2),
    surfaceContainerHigh = Color(0xFFECECEC),
    surfaceContainerHighest = Color(0xFFE6E6E6),
)

private val DarkNeutralScheme = darkColorScheme(
    // 点缀（深色下同一个蓝，对 #121212 对比 4.8:1）
    primary = Accent,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF2E3C6E),
    onPrimaryContainer = Color(0xFFDCE2F8),
    inversePrimary = Color(0xFF2E3C6E),
    // 次级/三级：灰
    secondary = Color(0xFFC4C4C4),
    onSecondary = Color(0xFF242424),
    secondaryContainer = Color(0xFF2E2E2E),
    onSecondaryContainer = Color(0xFFDCDCDC),
    tertiary = Color(0xFFBDBDBD),
    onTertiary = Color(0xFF242424),
    tertiaryContainer = Color(0xFF2A2A2A),
    onTertiaryContainer = Color(0xFFD6D6D6),
    // 语义
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
    // 底/面/字
    background = Color(0xFF121212),
    onBackground = Color(0xFFE6E6E6),
    surface = Color(0xFF121212),
    onSurface = Color(0xFFE6E6E6),
    surfaceVariant = Color(0xFF3A3A3A),
    onSurfaceVariant = Color(0xFFC4C4C4),
    outline = Color(0xFF8F8F8F),
    outlineVariant = Color(0xFF4A4A4A),
    inverseSurface = Color(0xFFE6E6E6),
    inverseOnSurface = Color(0xFF2A2A2A),
    surfaceDim = Color(0xFF0E0E0E),
    surfaceBright = Color(0xFF3A3A3A),
    surfaceContainerLowest = Color(0xFF0B0B0B),
    surfaceContainerLow = Color(0xFF191919),
    surfaceContainer = Color(0xFF1E1E1E),
    surfaceContainerHigh = Color(0xFF262626),
    surfaceContainerHighest = Color(0xFF303030),
)

@Composable
fun TsDroidTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkNeutralScheme else LightNeutralScheme,
        typography = AppTypography,
        content = content,
    )
}
