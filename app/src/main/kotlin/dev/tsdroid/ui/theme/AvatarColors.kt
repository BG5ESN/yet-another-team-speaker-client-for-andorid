package dev.tsdroid.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.absoluteValue

/** 无头像时的首字底色候选。挑色规则见 [avatarColorFor]。 */
private val avatarColors = listOf(
    Color(0xFF5C6BC0), // indigo
    Color(0xFF26A69A), // teal
    Color(0xFFEF5350), // red
    Color(0xFFAB47BC), // purple
    Color(0xFF42A5F5), // blue
    Color(0xFFFF7043), // deep orange
    Color(0xFF66BB6A), // green
    Color(0xFFEC407A), // pink
)

/**
 * 昵称 → 首字头像底色。
 *
 * 原先这段 `hashCode().absoluteValue % size` 在三个地方各抄了一遍
 * （用户列表、悬浮窗说话人、悬浮窗成员列表），而调色板又困在 UI 组件文件里，
 * 导致 Service 层为了取个颜色得反向 import `ui.component`。集中到这里之后：
 *  · 一处调色板，三处共用，不会再出现"改了颜色漏改一处"；
 *  · 同一个人在任何界面都是同一个颜色（都是 hash 出来的，稳定）。
 */
fun avatarColorFor(nickname: String): Color =
    avatarColors[nickname.hashCode().absoluteValue % avatarColors.size]
