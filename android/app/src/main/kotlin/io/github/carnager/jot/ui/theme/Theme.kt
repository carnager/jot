package io.github.carnager.jot.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.github.carnager.jot.R
import io.github.carnager.jot.data.ThemeMode

@OptIn(ExperimentalTextApi::class)
private fun manrope(weight: Int) = Font(
    R.font.manrope,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

val Manrope = FontFamily(manrope(400), manrope(500), manrope(600), manrope(700), manrope(800))

private fun style(size: Double, weight: Int, tracking: Double = 0.0, line: Double = size * 1.32) = TextStyle(
    fontFamily = Manrope,
    fontWeight = FontWeight(weight),
    fontSize = size.sp,
    letterSpacing = tracking.em,
    lineHeight = line.sp,
)

/** A big, bold display voice for headers; a calm medium body. */
private val JotType = Typography(
    displayLarge = style(48.0, 800, -0.03, 54.0),
    displayMedium = style(40.0, 800, -0.03, 46.0),
    displaySmall = style(34.0, 800, -0.025, 40.0),
    headlineLarge = style(30.0, 800, -0.02, 36.0),
    headlineMedium = style(27.0, 800, -0.02, 33.0),
    headlineSmall = style(23.0, 700, -0.015, 29.0),
    titleLarge = style(20.0, 700, -0.01, 26.0),
    titleMedium = style(16.5, 700, -0.005, 22.0),
    titleSmall = style(14.0, 700, 0.0, 20.0),
    bodyLarge = style(16.0, 500, 0.0, 23.0),
    bodyMedium = style(14.5, 500, 0.0, 21.0),
    bodySmall = style(12.5, 500, 0.005, 17.0),
    labelLarge = style(14.0, 700, 0.005, 20.0),
    labelMedium = style(12.5, 700, 0.01, 16.0),
    labelSmall = style(11.0, 700, 0.04, 15.0),
)

/** Brand: a warm marigold, the colour of the yellow check square. */
private val Light = lightColorScheme(
    primary = Color(0xFF7A5900),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDE9C),
    onPrimaryContainer = Color(0xFF261A00),
    inversePrimary = Color(0xFFF5BF48),
    secondary = Color(0xFF6A5D3F),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF3E1BB),
    onSecondaryContainer = Color(0xFF231A04),
    tertiary = Color(0xFF47664A),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFC9ECC9),
    onTertiaryContainer = Color(0xFF04210B),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFFF8F1),
    onBackground = Color(0xFF1F1B13),
    surface = Color(0xFFFFF8F1),
    onSurface = Color(0xFF1F1B13),
    surfaceVariant = Color(0xFFEDE1CF),
    onSurfaceVariant = Color(0xFF4D4639),
    surfaceTint = Color(0xFF7A5900),
    inverseSurface = Color(0xFF353027),
    inverseOnSurface = Color(0xFFF8EFE2),
    outline = Color(0xFF7F7667),
    outlineVariant = Color(0xFFD9CDBA),
    surfaceDim = Color(0xFFE2D9CC),
    surfaceBright = Color(0xFFFFF8F1),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFCF2E5),
    surfaceContainer = Color(0xFFF6ECDF),
    surfaceContainerHigh = Color(0xFFF0E7D9),
    surfaceContainerHighest = Color(0xFFEAE1D4),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFF7BE3D),
    onPrimary = Color(0xFF402D00),
    primaryContainer = Color(0xFF5C4300),
    onPrimaryContainer = Color(0xFFFFDE9C),
    inversePrimary = Color(0xFF7A5900),
    secondary = Color(0xFFD7C5A0),
    onSecondary = Color(0xFF3A2F15),
    secondaryContainer = Color(0xFF4A3F27),
    onSecondaryContainer = Color(0xFFF3E1BB),
    tertiary = Color(0xFFADD0AE),
    onTertiary = Color(0xFF19361F),
    tertiaryContainer = Color(0xFF304D34),
    onTertiaryContainer = Color(0xFFC9ECC9),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF15120C),
    onBackground = Color(0xFFEBE1D4),
    surface = Color(0xFF15120C),
    onSurface = Color(0xFFEDE3D5),
    surfaceVariant = Color(0xFF4D4639),
    onSurfaceVariant = Color(0xFFCFC5B3),
    surfaceTint = Color(0xFFF7BE3D),
    inverseSurface = Color(0xFFEBE1D4),
    inverseOnSurface = Color(0xFF353027),
    outline = Color(0xFF998F7F),
    outlineVariant = Color(0xFF443D31),
    surfaceDim = Color(0xFF15120C),
    surfaceBright = Color(0xFF3D382F),
    surfaceContainerLowest = Color(0xFF100D07),
    surfaceContainerLow = Color(0xFF1E1A13),
    surfaceContainer = Color(0xFF241F17),
    surfaceContainerHigh = Color(0xFF2E2921),
    surfaceContainerHighest = Color(0xFF39342B),
)

val LocalDark = staticCompositionLocalOf { false }

/** The decorative marigold (rings, highlights); `primary` stays the accessible text/icon colour. */
val Marigold = Color(0xFFF2A516)
val MarigoldBright = Color(0xFFF7BE3D)

@Composable
fun JotTheme(mode: ThemeMode, dynamic: Boolean, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val ctx = LocalContext.current
    val scheme = when {
        dynamic && dark -> dynamicDarkColorScheme(ctx)
        dynamic -> dynamicLightColorScheme(ctx)
        dark -> Dark
        else -> Light
    }
    CompositionLocalProvider(LocalDark provides dark) {
        MaterialTheme(
            colorScheme = scheme,
            typography = JotType,
            shapes = Shapes(
                extraSmall = RoundedCornerShape(8.dp),
                small = RoundedCornerShape(12.dp),
                medium = RoundedCornerShape(16.dp),
                large = RoundedCornerShape(20.dp),
                extraLarge = RoundedCornerShape(28.dp),
            ),
            content = content,
        )
    }
}

/**
 * Item colours are accents, never fills: a strip on the card's leading edge, a dot in the editor,
 * a tinted checkbox ring. Surfaces stay neutral.
 */
object NoteColors {
    val names = listOf("", "red", "orange", "yellow", "green", "teal", "blue", "purple", "pink", "brown", "gray")

    private val light = mapOf(
        "red" to 0xFFD93F3F, "orange" to 0xFFE8751A, "yellow" to 0xFFD4A20B, "green" to 0xFF3E9B4F,
        "teal" to 0xFF14958C, "blue" to 0xFF3A78D8, "purple" to 0xFF8556D0, "pink" to 0xFFD2508F,
        "brown" to 0xFF96694A, "gray" to 0xFF7A7F87,
    )
    private val dark = mapOf(
        "red" to 0xFFFF7B72, "orange" to 0xFFFFA15C, "yellow" to 0xFFF2C94C, "green" to 0xFF6BCB77,
        "teal" to 0xFF4FD1C5, "blue" to 0xFF79A8FF, "purple" to 0xFFB392F0, "pink" to 0xFFF285B8,
        "brown" to 0xFFC49A78, "gray" to 0xFFA4A9B1,
    )

    /** The accent for [name], or null for none. */
    fun accent(name: String, dark: Boolean): Color? = (if (dark) this.dark else light)[name]?.let { Color(it) }
}
