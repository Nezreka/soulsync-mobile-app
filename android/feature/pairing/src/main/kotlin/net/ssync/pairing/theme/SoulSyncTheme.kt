package net.ssync.pairing.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class SoulSyncAccentSwatch(
    val id: String,
    val label: String,
    val accent: Color,
    val light: Color,
    val neon: Color,
    val onAccent: Color,
)

val SOULSYNC_ACCENT_SWATCHES: List<SoulSyncAccentSwatch> = listOf(
    SoulSyncAccentSwatch("emerald", "Emerald", Color(0xFF1DB954), Color(0xFF1ED760), Color(0xFF22FF6B), Color(0xFF04140A)),
    SoulSyncAccentSwatch("violet", "Violet", Color(0xFFA855F7), Color(0xFFC084FC), Color(0xFFD8B4FE), Color(0xFFFFFFFF)),
    SoulSyncAccentSwatch("ocean", "Ocean", Color(0xFF38BDF8), Color(0xFF7DD3FC), Color(0xFFBAE6FD), Color(0xFF041019)),
    SoulSyncAccentSwatch("amber", "Amber", Color(0xFFF59E0B), Color(0xFFFBBF24), Color(0xFFFDE68A), Color(0xFF190E02)),
    SoulSyncAccentSwatch("rose", "Rose", Color(0xFFF43F5E), Color(0xFFFB7185), Color(0xFFFDA4AF), Color(0xFFFFFFFF)),
    SoulSyncAccentSwatch("crimson", "Crimson", Color(0xFFE50914), Color(0xFFF83842), Color(0xFFFF6B72), Color(0xFFFFFFFF)),
)

/**
 * Official SoulSync design tokens ported 1:1 from `SoulSync/webui/static/tokens.css`.
 * Accent tokens are backed by Compose state so profile accent changes recompose live.
 */
object SoulSyncColors {
    var ActiveAccentId by mutableStateOf("emerald")
        private set
    var Accent by mutableStateOf(Color(0xFF1DB954))        // --accent-rgb: 29, 185, 84
        private set
    var AccentLight by mutableStateOf(Color(0xFF1ED760))   // --accent-light-rgb: 30, 215, 96
        private set
    var AccentNeon by mutableStateOf(Color(0xFF22FF6B))    // --accent-neon-rgb: 34, 255, 107
        private set
    var OnAccent by mutableStateOf(Color(0xFF04140A))      // --help-on-accent: #04140a
        private set

    fun applyAccent(key: String?) {
        val norm = key?.trim()?.lowercase() ?: "emerald"
        val swatch = SOULSYNC_ACCENT_SWATCHES.firstOrNull { it.id == norm }
            ?: SOULSYNC_ACCENT_SWATCHES.first()
        ActiveAccentId = swatch.id
        Accent = swatch.accent
        AccentLight = swatch.light
        AccentNeon = swatch.neon
        OnAccent = swatch.onAccent
    }

    val BgPrimary = Color(0xFF07070A)     // --pl-ink-rgb: 7, 7, 10 (premier /player canvas)
    val Panel = Color(0xFF101017)         // --sa-panel: #101017
    val PanelGlass = Color(0xB816161E)    // --pl-panel: rgba(22, 22, 30, 0.72)
    val PanelSolid = Color(0xFF16161E)    // --pl-panel-solid: #16161e
    val Surface = Color(0xFF121212)       // --bg-surface: #121212
    val Card = Color(0xFF14141C)          // --sa-card
    val CardRaised = Color(0xFF1A1A24)    // --sa-card-raised
    val BgSecondary = Color(0xFF1E1E2E)   // --bg-secondary: #1e1e2e
    val BgRaised2 = Color(0xFF2A2A2A)     // --bg-raised-2: #2a2a2a

    val TextPrimary = Color(0xFFF4F5F7)   // --text-bright: #f4f5f7
    val TextSoft = Color(0xFFB3B3B3)      // --text-soft: #b3b3b3
    val TextSecondary = Color(0xFF9AA0AA) // --text-secondary: #9aa0aa
    val Line = Color(0x14FFFFFF)          // --line: rgba(255,255,255,0.08)
    val LineStrong = Color(0x1FFFFFFF)    // --line-strong: rgba(255,255,255,0.12)

    val Danger = Color(0xFFEF4444)        // --danger: #ef4444
    val Warning = Color(0xFFFBBF24)       // --warning: #fbbf24
    val Success = Color(0xFF4ADE80)       // --success: #4ade80
    val Info = Color(0xFF38BDF8)          // --info: #38bdf8
    val HiRes = Color(0xFFD9F99D)         // --hires: #d9f99d
}

private val SoulSyncDarkColorScheme = darkColorScheme(
    primary = SoulSyncColors.Accent,
    onPrimary = SoulSyncColors.OnAccent,
    primaryContainer = Color(0xFF0D331B),
    onPrimaryContainer = SoulSyncColors.AccentLight,
    secondary = SoulSyncColors.Info,
    onSecondary = SoulSyncColors.BgPrimary,
    secondaryContainer = SoulSyncColors.BgSecondary,
    onSecondaryContainer = SoulSyncColors.TextPrimary,
    tertiary = SoulSyncColors.HiRes,
    onTertiary = SoulSyncColors.OnAccent,
    background = SoulSyncColors.BgPrimary,
    onBackground = SoulSyncColors.TextPrimary,
    surface = SoulSyncColors.Panel,
    onSurface = SoulSyncColors.TextPrimary,
    surfaceVariant = SoulSyncColors.Card,
    onSurfaceVariant = SoulSyncColors.TextSecondary,
    surfaceContainerLow = SoulSyncColors.Panel,
    surfaceContainer = SoulSyncColors.Card,
    surfaceContainerHigh = SoulSyncColors.CardRaised,
    surfaceContainerHighest = SoulSyncColors.BgSecondary,
    outline = SoulSyncColors.LineStrong,
    outlineVariant = SoulSyncColors.Line,
    error = SoulSyncColors.Danger,
    onError = Color.White,
)

private val SoulSyncShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),  // --radius-sm
    small = RoundedCornerShape(10.dp),      // --radius-control
    medium = RoundedCornerShape(14.dp),     // --radius-card
    large = RoundedCornerShape(18.dp),      // --radius-sheet
    extraLarge = RoundedCornerShape(24.dp),
)

private val SoulSyncTypography = Typography(
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 26.sp,
        lineHeight = 32.sp,
        color = SoulSyncColors.TextPrimary,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        color = SoulSyncColors.TextPrimary,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        color = SoulSyncColors.TextPrimary,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        color = SoulSyncColors.TextSoft,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
)

@Composable
fun SoulSyncTheme(content: @Composable () -> Unit) {
    val colorScheme = darkColorScheme(
        primary = SoulSyncColors.Accent,
        onPrimary = SoulSyncColors.OnAccent,
        primaryContainer = SoulSyncColors.Accent.copy(alpha = 0.22f),
        onPrimaryContainer = SoulSyncColors.AccentLight,
        secondary = SoulSyncColors.Info,
        onSecondary = SoulSyncColors.BgPrimary,
        secondaryContainer = SoulSyncColors.BgSecondary,
        onSecondaryContainer = SoulSyncColors.TextPrimary,
        tertiary = SoulSyncColors.HiRes,
        onTertiary = SoulSyncColors.OnAccent,
        background = SoulSyncColors.BgPrimary,
        onBackground = SoulSyncColors.TextPrimary,
        surface = SoulSyncColors.Panel,
        onSurface = SoulSyncColors.TextPrimary,
        surfaceVariant = SoulSyncColors.Card,
        onSurfaceVariant = SoulSyncColors.TextSecondary,
        surfaceContainerLow = SoulSyncColors.Panel,
        surfaceContainer = SoulSyncColors.Card,
        surfaceContainerHigh = SoulSyncColors.CardRaised,
        surfaceContainerHighest = SoulSyncColors.BgSecondary,
        outline = SoulSyncColors.LineStrong,
        outlineVariant = SoulSyncColors.Line,
        error = SoulSyncColors.Danger,
        onError = Color.White,
    )
    MaterialTheme(
        colorScheme = colorScheme,
        shapes = SoulSyncShapes,
        typography = SoulSyncTypography,
        content = content,
    )
}
