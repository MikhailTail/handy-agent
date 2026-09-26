package dev.pocket.agent.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 终端 / 代码 / diff 用等宽字体，保证列对齐。 */
val MonoFontFamily: FontFamily = FontFamily.Monospace

val CodeTextStyle: TextStyle = TextStyle(
    fontFamily = MonoFontFamily,
    fontSize = 12.5.sp,
    lineHeight = 17.sp,
)

/** diff 与终端着色。刻意挑低饱和底色，深色下长时间阅读不刺眼。 */
object PocketColors {
    val DiffAddBg = Color(0xFF12301C)
    val DiffDelBg = Color(0xFF35161A)
    val DiffAddText = Color(0xFF7EE787)
    val DiffDelText = Color(0xFFFF9AA0)
    val DiffCtxText = Color(0xFF9BA7B4)

    val CodeBg = Color(0xFF0B0F14)
    val ToolAccent = Color(0xFF7DD3FC)
    val ToolError = Color(0xFFF87171)
    val ToolOk = Color(0xFF6EE7A8)
    val ToolWarn = Color(0xFFFBBF24)
    val Muted = Color(0xFF8B98A8)
}

private val PocketDarkScheme = darkColorScheme(
    primary = Color(0xFF7DD3FC),
    onPrimary = Color(0xFF05202E),
    primaryContainer = Color(0xFF10394F),
    onPrimaryContainer = Color(0xFFCDEBFF),
    secondary = Color(0xFFA5B4FC),
    onSecondary = Color(0xFF141A3A),
    secondaryContainer = Color(0xFF232A55),
    onSecondaryContainer = Color(0xFFDDE1FF),
    tertiary = Color(0xFF6EE7A8),
    onTertiary = Color(0xFF04281A),
    background = Color(0xFF0E1116),
    onBackground = Color(0xFFE6EDF3),
    surface = Color(0xFF161B22),
    onSurface = Color(0xFFE6EDF3),
    surfaceVariant = Color(0xFF1F2733),
    onSurfaceVariant = Color(0xFFB7C2CE),
    outline = Color(0xFF30363D),
    outlineVariant = Color(0xFF232A33),
    error = Color(0xFFF87171),
    onError = Color(0xFF3B0A0A),
    errorContainer = Color(0xFF4A1717),
    onErrorContainer = Color(0xFFFFDAD6),
    scrim = Color(0xCC000000),
)

private val PocketShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

private val PocketTypography = Typography().let { base ->
    base.copy(
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelSmall = base.labelSmall.copy(letterSpacing = 0.4.sp),
    )
}

@Composable
fun PocketAgentTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = PocketDarkScheme,
        shapes = PocketShapes,
        typography = PocketTypography,
        content = content,
    )
}
