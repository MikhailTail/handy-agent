package dev.mikhailtail.handyagent.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Handy Agent 的「纸·墨·印」视觉体系。
 *
 * 色值、圆角、字号全部照搬上游 `desktop/src/theme/globals.css` 的令牌，
 * 只把**桌面两栏布局**换成了适配手机的单栏 —— 手机上塞不下 280px 侧栏。
 *
 * 上游的设计主张有两条值得记住：
 * 1. **靠底色分层与 1px 细边框建立层次，不靠重阴影**（阴影非常克制）；
 * 2. **主按钮是"墨色实心"**（`t1` 底 + `bg` 字），不是主色 —— 主色（陶土红）只用于强调。
 */

/** 终端 / 代码 / diff 用等宽字体，保证列对齐。 */
val MonoFontFamily: FontFamily = FontFamily.Monospace

val CodeTextStyle: TextStyle = TextStyle(
    fontFamily = MonoFontFamily,
    fontSize = 12.5.sp,
    lineHeight = 17.sp,
)

/** 上游的六套主题。 */
enum class CcThemeId(val label: String, val dark: Boolean) {
    PAPER("纸墨", false),
    WHITE("纯白", false),
    WARM("经典暖色", false),
    CELADON("青瓷", false),
    DARK("墨夜", true),
    INK_BLUE("墨夜蓝", true);

    companion object {
        val DEFAULT = PAPER
        fun fromName(name: String?): CcThemeId =
            entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/**
 * 一套主题的全部颜色。
 *
 * 命名沿用上游的 `--cc-*` 语义：`bg` 页面底、`s0~s2` 三级浮现面、`t1~t3` 三级文字、
 * `ac*` 陶土强调、`on*` 是"底上的字"。**状态色必须成对用**：
 * `ok` 只做描边/图标，`okSoft` 底上的文字要用 `okInk`（否则对比度不足 AA）。
 */
data class CcPalette(
    val bg: Color,
    val s0: Color,
    val s1: Color,
    val s2: Color,
    val border: Color,
    val borderStrongish: Color,
    val borderStrong: Color,
    val t1: Color,
    val t2: Color,
    val t3: Color,
    val accent: Color,
    val accentHover: Color,
    val accentSoft: Color,
    val accentBorder: Color,
    val accentInk: Color,
    val onAccent: Color,
    val ok: Color,
    val okSoft: Color,
    val okInk: Color,
    val warn: Color,
    val warnSoft: Color,
    val warnInk: Color,
    val error: Color,
    val errorSoft: Color,
    val errorInk: Color,
    val teal: Color,
    val code: Color,
    val diffAddBg: Color,
    val diffDelBg: Color,
    val isDark: Boolean,
)

private val PAPER = CcPalette(
    bg = Color(0xFFFBF9F4), s0 = Color(0xFFF3EFE5), s1 = Color(0xFFECE6D8), s2 = Color(0xFFF7F4EC),
    border = Color(0xFFE7E0CF), borderStrongish = Color(0xFFD5C9B0), borderStrong = Color(0xFF938A76),
    t1 = Color(0xFF231D12), t2 = Color(0xFF6E6350), t3 = Color(0xFF796E5B),
    accent = Color(0xFF96442B), accentHover = Color(0xFF7C3620), accentSoft = Color(0xFFF3E6DA),
    accentBorder = Color(0xFFE2C7B3), accentInk = Color(0xFF96442B), onAccent = Color(0xFFFBF6EC),
    ok = Color(0xFF477C52), okSoft = Color(0xFFE8EFE1), okInk = Color(0xFF43754D),
    warn = Color(0xFFA8751F), warnSoft = Color(0xFFF6EED9), warnInk = Color(0xFF8E631A),
    error = Color(0xFFC24532), errorSoft = Color(0xFFF7E7DF), errorInk = Color(0xFFB6412F),
    teal = Color(0xFF3B7068), code = Color(0xFFF5F1E4),
    diffAddBg = Color(0xFFE4F0E5), diffDelBg = Color(0xFFF1E8E4), isDark = false,
)

private val WHITE = CcPalette(
    bg = Color(0xFFFFFFFF), s0 = Color(0xFFF7F7F7), s1 = Color(0xFFF0F0F0), s2 = Color(0xFFFAFAFA),
    border = Color(0xFFEBEBEB), borderStrongish = Color(0xFFD6D6D6), borderStrong = Color(0xFF8C8C8C),
    t1 = Color(0xFF242424), t2 = Color(0xFF575757), t3 = Color(0xFF6E6E6E),
    accent = Color(0xFF96442B), accentHover = Color(0xFF7C3620), accentSoft = Color(0xFFF6ECE5),
    accentBorder = Color(0xFFE6CDBF), accentInk = Color(0xFF96442B), onAccent = Color(0xFFFBF6EC),
    ok = Color(0xFF3E8153), okSoft = Color(0xFFE8F2E9), okInk = Color(0xFF3A784D),
    warn = Color(0xFFA8751F), warnSoft = Color(0xFFF8F0DC), warnInk = Color(0xFF90641B),
    error = Color(0xFFC74436), errorSoft = Color(0xFFFAECE8), errorInk = Color(0xFFBD4133),
    teal = Color(0xFF3B7068), code = Color(0xFFF8F8F8),
    diffAddBg = Color(0xFFE8F3E9), diffDelBg = Color(0xFFFAEEED), isDark = false,
)

private val WARM = CcPalette(
    bg = Color(0xFFF6F0E1), s0 = Color(0xFFEDE5D1), s1 = Color(0xFFE5DBC3), s2 = Color(0xFFF1EBDA),
    border = Color(0xFFDED2B8), borderStrongish = Color(0xFFC8B897), borderStrong = Color(0xFF908369),
    t1 = Color(0xFF251E11), t2 = Color(0xFF6E6146), t3 = Color(0xFF746951),
    accent = Color(0xFF96442B), accentHover = Color(0xFF7C3620), accentSoft = Color(0xFFF0E1D0),
    accentBorder = Color(0xFFDFC0A8), accentInk = Color(0xFF96442B), onAccent = Color(0xFFFBF6EC),
    ok = Color(0xFF3E8153), okSoft = Color(0xFFE8F2E9), okInk = Color(0xFF3A784D),
    warn = Color(0xFFA8751F), warnSoft = Color(0xFFF8F0DC), warnInk = Color(0xFF90641B),
    error = Color(0xFFC74436), errorSoft = Color(0xFFFAECE8), errorInk = Color(0xFFBD4133),
    teal = Color(0xFF3E6B58), code = Color(0xFFEFE8D3),
    diffAddBg = Color(0xFFE9EFDC), diffDelBg = Color(0xFFF6E9E3), isDark = false,
)

private val CELADON = CcPalette(
    bg = Color(0xFFF5F8F5), s0 = Color(0xFFEAF0EB), s1 = Color(0xFFE0E9E2), s2 = Color(0xFFF0F4F0),
    border = Color(0xFFDCE5DE), borderStrongish = Color(0xFFC6D4C9), borderStrong = Color(0xFF818D84),
    t1 = Color(0xFF1C231D), t2 = Color(0xFF5D6B60), t3 = Color(0xFF667268),
    accent = Color(0xFF96442B), accentHover = Color(0xFF7C3620), accentSoft = Color(0xFFF0E7DC),
    accentBorder = Color(0xFFDFC9B6), accentInk = Color(0xFF96442B), onAccent = Color(0xFFFBF6EC),
    ok = Color(0xFF3E8153), okSoft = Color(0xFFE3EFE5), okInk = Color(0xFF39764C),
    warn = Color(0xFFA8751F), warnSoft = Color(0xFFF8F0DC), warnInk = Color(0xFF90641B),
    error = Color(0xFFC74436), errorSoft = Color(0xFFFAECE8), errorInk = Color(0xFFBD4133),
    teal = Color(0xFF2F6E63), code = Color(0xFFEFF4F0),
    diffAddBg = Color(0xFFE6ECD3), diffDelBg = Color(0xFFF2E1D2), isDark = false,
)

private val DARK = CcPalette(
    bg = Color(0xFF201D17), s0 = Color(0xFF1A1813), s1 = Color(0xFF2B271F), s2 = Color(0xFF252119),
    border = Color(0xFF383226), borderStrongish = Color(0xFF4C4433), borderStrong = Color(0xFF756D5C),
    t1 = Color(0xFFEDE6D6), t2 = Color(0xFFA79B83), t3 = Color(0xFF918777),
    // 注意：暗色下主色是**变亮**的（#D07B52），不是变暗
    accent = Color(0xFFD07B52), accentHover = Color(0xFFDE8F66), accentSoft = Color(0xFF3B2A1E),
    accentBorder = Color(0xFF59402C), accentInk = Color(0xFFD2815B), onAccent = Color(0xFF201D17),
    ok = Color(0xFF84B383), okSoft = Color(0xFF28301D), okInk = Color(0xFF84B383),
    warn = Color(0xFFD3A34C), warnSoft = Color(0xFF332916), warnInk = Color(0xFFD3A34C),
    error = Color(0xFFE06B52), errorSoft = Color(0xFF3A241B), errorInk = Color(0xFFE17058),
    teal = Color(0xFF7DD3C7), code = Color(0xFF1A1813),
    diffAddBg = Color(0xFF2B331E), diffDelBg = Color(0xFF3B281F), isDark = true,
)

private val INK_BLUE = CcPalette(
    bg = Color(0xFF1A1D24), s0 = Color(0xFF15181E), s1 = Color(0xFF252A35), s2 = Color(0xFF20242D),
    border = Color(0xFF2F3441), borderStrongish = Color(0xFF434A5B), borderStrong = Color(0xFF6A707E),
    t1 = Color(0xFFE8EAF0), t2 = Color(0xFF9AA1B2), t3 = Color(0xFF858B97),
    accent = Color(0xFFD07B52), accentHover = Color(0xFFDE8F66), accentSoft = Color(0xFF37281F),
    accentBorder = Color(0xFF57402F), accentInk = Color(0xFFD17E56), onAccent = Color(0xFF1A1D24),
    ok = Color(0xFF6FB58C), okSoft = Color(0xFF213026), okInk = Color(0xFF6FB58C),
    warn = Color(0xFFD3A34C), warnSoft = Color(0xFF2F2917), warnInk = Color(0xFFD3A34C),
    error = Color(0xFFE0685E), errorSoft = Color(0xFF37211D), errorInk = Color(0xFFE06A60),
    teal = Color(0xFF7DD3C7), code = Color(0xFF15181E),
    diffAddBg = Color(0xFF233028), diffDelBg = Color(0xFF36282C), isDark = true,
)

fun paletteOf(id: CcThemeId): CcPalette = when (id) {
    CcThemeId.PAPER -> PAPER
    CcThemeId.WHITE -> WHITE
    CcThemeId.WARM -> WARM
    CcThemeId.CELADON -> CELADON
    CcThemeId.DARK -> DARK
    CcThemeId.INK_BLUE -> INK_BLUE
}

val LocalCcPalette = staticCompositionLocalOf { PAPER }
val LocalCcThemeId = staticCompositionLocalOf { CcThemeId.DEFAULT }

/**
 * 语义色的统一入口。
 *
 * 保留 `PocketColors.X` 这个名字是为了不动几百处既有调用点；实现改成从当前调色板读，
 * 所以换主题时它们会跟着变（以前是写死的常量，换不了主题）。
 */
object PocketColors {
    val DiffAddBg: Color @Composable @ReadOnlyComposable get() = LocalCcPalette.current.diffAddBg
    val DiffDelBg: Color @Composable @ReadOnlyComposable get() = LocalCcPalette.current.diffDelBg
    val DiffAddText: Color @Composable @ReadOnlyComposable get() = LocalCcPalette.current.okInk
    val DiffDelText: Color @Composable @ReadOnlyComposable get() = LocalCcPalette.current.errorInk
    val DiffCtxText: Color @Composable @ReadOnlyComposable get() = LocalCcPalette.current.t3

    val CodeBg: Color @Composable @ReadOnlyComposable get() = LocalCcPalette.current.code
    val ToolAccent: Color @Composable @ReadOnlyComposable get() = LocalCcPalette.current.accent
    val ToolError: Color @Composable @ReadOnlyComposable get() = LocalCcPalette.current.error
    val ToolOk: Color @Composable @ReadOnlyComposable get() = LocalCcPalette.current.ok
    val ToolWarn: Color @Composable @ReadOnlyComposable get() = LocalCcPalette.current.warn
    val Muted: Color @Composable @ReadOnlyComposable get() = LocalCcPalette.current.t3

    val TextPrimary: Color @Composable @ReadOnlyComposable get() = LocalCcPalette.current.t1
    val TextSecondary: Color @Composable @ReadOnlyComposable get() = LocalCcPalette.current.t2
    val Border: Color @Composable @ReadOnlyComposable get() = LocalCcPalette.current.border
    val SurfaceSidebar: Color @Composable @ReadOnlyComposable get() = LocalCcPalette.current.s0
    val SurfaceHover: Color @Composable @ReadOnlyComposable get() = LocalCcPalette.current.s1
    val AccentSoft: Color @Composable @ReadOnlyComposable get() = LocalCcPalette.current.accentSoft
    val OnAccentSoft: Color @Composable @ReadOnlyComposable get() = LocalCcPalette.current.accentInk
    val Brand: Color @Composable @ReadOnlyComposable get() = LocalCcPalette.current.accent

    /** 主按钮 = 墨色实心（上游的明确主张，不是主色）。 */
    val BtnPrimaryBg: Color @Composable @ReadOnlyComposable get() = LocalCcPalette.current.t1
    val BtnPrimaryFg: Color @Composable @ReadOnlyComposable get() = LocalCcPalette.current.bg
}

/** 圆角阶梯照搬上游：6 / 10 / 13 / 17 / 20 / 24。 */
val CcRadiusSm = 6.dp
val CcRadiusMd = 10.dp
val CcRadiusLg = 13.dp
val CcRadiusXl = 17.dp
val CcRadius2xl = 20.dp

private fun shapes() = Shapes(
    extraSmall = RoundedCornerShape(CcRadiusSm),
    small = RoundedCornerShape(CcRadiusMd),
    medium = RoundedCornerShape(CcRadiusLg),
    large = RoundedCornerShape(CcRadiusXl),
    extraLarge = RoundedCornerShape(CcRadius2xl),
)

/** 字号阶梯来自上游：正文 14px / 行高 1.625，标签 13·12·11·10。 */
private fun typography() = Typography().let { base ->
    base.copy(
        titleLarge = base.titleLarge.copy(fontSize = 21.sp, lineHeight = 28.sp),
        titleMedium = base.titleMedium.copy(fontSize = 16.5.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold),
        titleSmall = base.titleSmall.copy(fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold),
        bodyLarge = base.bodyLarge.copy(fontSize = 14.sp, lineHeight = 23.sp),
        bodyMedium = base.bodyMedium.copy(fontSize = 14.sp, lineHeight = 23.sp),
        bodySmall = base.bodySmall.copy(fontSize = 13.sp, lineHeight = 20.sp),
        labelLarge = base.labelLarge.copy(fontSize = 13.sp, lineHeight = 18.sp),
        labelMedium = base.labelMedium.copy(fontSize = 12.sp, lineHeight = 16.sp),
        labelSmall = base.labelSmall.copy(fontSize = 11.sp, lineHeight = 15.sp, letterSpacing = 0.2.sp),
    )
}

private fun schemeOf(p: CcPalette) = if (p.isDark) {
    darkColorScheme(
        primary = p.t1,
        onPrimary = p.bg,
        primaryContainer = p.accentSoft,
        onPrimaryContainer = p.accentInk,
        secondary = p.teal,
        onSecondary = p.bg,
        secondaryContainer = p.s1,
        onSecondaryContainer = p.t1,
        tertiary = p.accent,
        onTertiary = p.onAccent,
        background = p.bg,
        onBackground = p.t1,
        surface = p.bg,
        onSurface = p.t1,
        surfaceVariant = p.s1,
        onSurfaceVariant = p.t2,
        outline = p.borderStrongish,
        outlineVariant = p.border,
        error = p.error,
        onError = p.bg,
        errorContainer = p.errorSoft,
        onErrorContainer = p.errorInk,
        scrim = Color(0xCC000000),
    )
} else {
    lightColorScheme(
        primary = p.t1,
        onPrimary = p.bg,
        primaryContainer = p.accentSoft,
        onPrimaryContainer = p.accentInk,
        secondary = p.teal,
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = p.s1,
        onSecondaryContainer = p.t1,
        tertiary = p.accent,
        onTertiary = p.onAccent,
        background = p.bg,
        onBackground = p.t1,
        surface = p.bg,
        onSurface = p.t1,
        surfaceVariant = p.s1,
        onSurfaceVariant = p.t2,
        outline = p.borderStrongish,
        outlineVariant = p.border,
        error = p.error,
        onError = Color(0xFFFFFFFF),
        errorContainer = p.errorSoft,
        onErrorContainer = p.errorInk,
        scrim = Color(0x66000000),
    )
}

@Composable
fun HandyTheme(
    themeId: CcThemeId = CcThemeId.DEFAULT,
    content: @Composable () -> Unit,
) {
    val palette = paletteOf(themeId)
    CompositionLocalProvider(
        LocalCcPalette provides palette,
        LocalCcThemeId provides themeId,
    ) {
        MaterialTheme(
            colorScheme = schemeOf(palette),
            shapes = shapes(),
            typography = typography(),
            content = content,
        )
    }
}
