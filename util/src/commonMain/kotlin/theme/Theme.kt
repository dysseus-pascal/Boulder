package theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import com.russhwolf.settings.Settings
import coreapp.util.generated.resources.Res
import coreapp.util.generated.resources.black
import coreapp.util.generated.resources.dark
import coreapp.util.generated.resources.light
import coreapp.util.generated.resources.system
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.jetbrains.compose.resources.StringResource
import org.koin.compose.koinInject
import theme.CoreAppTheme.Companion.asCoreAppTheme

// Boulder: Moosgruen, die Farbe des Hintergrunds im App-Symbol.
val boulderGruen = Color(0xFF4E7D5B)
// Boulder: dasselbe Gruen, hell genug fuer Text auf den dunklen Schemata.
// Das Markengruen erreicht auf #333333 nur 2,65:1 - Titel und Buttons waren
// ausgewaschen und unter AA. Auf dem hellen Gruen steht dunkler Text; die
// Kontraste haelt DunkleSchemataKontrastTest fest.
val boulderGruenHell = Color(0xFF7DBB8A)
val aufBoulderGruenHell = Color(0xFF0B2412)
private val gruenContainerDunkel = Color(0xFF2F5A3A)
private val aufGruenContainerDunkel = Color(0xFFD9F0DC)
// Boulder: Fehlerfarben aus Material 3; die geerbte #FA6B66 kam mit Weiss auf 2,85:1.
private val errorDunkel = Color(0xFFFF8A80)
private val onErrorDunkel = Color(0xFF3A0A08)
private val errorContainerDunkel = Color(0xFF93000A)
private val onErrorContainerDunkel = Color(0xFFFFDAD6)
private val errorHell = Color(0xFFB3261E)
private val errorContainerHell = Color(0xFFF9DEDC)
private val onErrorContainerHell = Color(0xFF410E0B)
val coreGrey = Color(0xFF333333)
val coreDarkGrey = Color(0xFF2B2930)
val coreDarkGreen = Color(0xFF157a30)
private val error = Color(0xFFFA6B66)
val greyScheme = darkColorScheme(
    primary = boulderGruenHell,
    onPrimary = aufBoulderGruenHell,
    primaryContainer = boulderGruen,
    onPrimaryContainer = Color.White,
    secondary = boulderGruenHell,
    onSecondary = aufBoulderGruenHell,
    secondaryContainer = boulderGruen,
    onSecondaryContainer = Color.White,
    tertiary = boulderGruenHell,
    onTertiary = aufBoulderGruenHell,
    tertiaryContainer = gruenContainerDunkel,
    onTertiaryContainer = aufGruenContainerDunkel,
    error = errorDunkel,
    onError = onErrorDunkel,
    errorContainer = errorContainerDunkel,
    onErrorContainer = onErrorContainerDunkel,
    background = coreGrey,
    onBackground = onBackgroundDark,
    surface = coreGrey,
    onSurface = Color(0xFFE6E0E9),
    surfaceVariant = surfaceVariantDark,
    onSurfaceVariant = onSurfaceVariantDark,
    outline = outlineDark,
    outlineVariant = outlineVariantDark,
    scrim = coreGrey,
    surfaceDim = Color(0xFF262626),
    surfaceBright = Color(0xFF4D4D4D),
    surfaceContainerLowest = Color(0xFF1A1A1A),
    surfaceContainerLow = Color(0xFF262626),
    surfaceContainer = Color(0xFF3D3D3D),
    surfaceContainerHigh = Color(0xFF474747),
    surfaceContainerHighest = Color(0xFF525252),
)

// Containers must ascend lowest -> highest, and surfaceContainer must clear `surface`: it is what
// menus and popups paint themselves, so matching the page behind them leaves them with no edge.
val blackScheme = greyScheme.copy(
    background = Color.Black,
    surface = Color.Black,
    scrim = Color.Black,
    surfaceDim = Color.Black,
    surfaceBright = coreDarkGrey,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF0D0D0D),
    surfaceContainer = Color(0xFF141414),
    surfaceContainerHigh = Color(0xFF1F1F1F),
    surfaceContainerHighest = coreDarkGrey,
)

val lightScheme = lightColorScheme(
    primary = boulderGruen,
    onPrimary = Color.White,
    primaryContainer = boulderGruen,
    onPrimaryContainer = Color.Black,
    secondary = boulderGruen,
    onSecondary = Color.Black,
    secondaryContainer = boulderGruen,
    onSecondaryContainer = Color.White,
    tertiary = boulderGruen,
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = tertiaryContainerLight,
    onTertiaryContainer = onTertiaryContainerLight,
    error = errorHell,
    onError = Color.White,
    errorContainer = errorContainerHell,
    onErrorContainer = onErrorContainerHell,
    background = Color.White,
    onBackground = onBackgroundLight,
    surface = Color.White,
    onSurface = Color.Black,
    surfaceVariant = surfaceVariantLight,
    onSurfaceVariant = onSurfaceVariantLight,
    outline = outlineLight,
    outlineVariant = outlineVariantLight,
    scrim = Color.White,
    // Boulder: Menues und Popups zeichnen sich auf surfaceContainer - gleich der Seite
    // haetten sie keine Kante (wie bei blackScheme oben).
    surfaceContainer = Color(0xFFF3F3F3),
    surfaceContainerHighest = Color(0xFFF0F0F0),
    surfaceContainerHigh = Color(0xFFF5F5F5),
    surfaceContainerLow = Color(0xFFFAFAFA),
    surfaceDim = Color(0xFFE0E0E0),
    surfaceBright = Color(0xFFFFFBFF),
)

val onboardingScheme = lightColorScheme(
    primary = Color.White,
    onPrimary = Color.Black,
    primaryContainer = Color.White,
    onPrimaryContainer = boulderGruen,
    secondary = Color.White,
    onSecondary = boulderGruen,
    secondaryContainer = Color.White,
    onSecondaryContainer = boulderGruen,
    tertiary = Color.White,
    onTertiary = boulderGruen,
    background = boulderGruen,
    onBackground = Color.White,
    surface = boulderGruen,
    onSurface = Color.White,
    surfaceVariant = boulderGruen,
    onSurfaceVariant = Color.White.copy(alpha = 0.8f),
    outline = Color.White.copy(alpha = 0.5f),
    outlineVariant = Color.White.copy(alpha = 0.3f),
    scrim = boulderGruen,
    surfaceContainer = boulderGruen,
    surfaceContainerHighest = Color.White.copy(alpha = 0.15f),
    error = error,
    onError = Color.White,
    errorContainer = error,
    onErrorContainer = Color.White,
)

val lightExtendedColors = ExtendedColors(
    primary20 = primary20Light,
    onPrimary20 = onPrimaryLight,
    warning = warningLight,
    onWarning = onWarningLight,
    success = successLight,
    onSuccess = onSuccessLight,
)

val darkExtendedColors = ExtendedColors(
    primary20 = primary20Dark,
    onPrimary20 = onPrimaryDark,
    warning = warningDark,
    onWarning = onWarningDark,
    success = successDark,
    onSuccess = onSuccessDark,
)

@Immutable
data class ColorFamily(
    val color: Color,
    val onColor: Color,
    val colorContainer: Color,
    val onColorContainer: Color
)

enum class CoreAppTheme(val resource: StringResource, val key: String) {
    Light(Res.string.light, "light"),
    Dark(Res.string.dark, "dark"),
    Black(Res.string.black, "black"),
    System(Res.string.system, "system"),
    ;

    companion object {
        fun String?.asCoreAppTheme(): CoreAppTheme =
            entries.firstOrNull { it.key == this } ?: System
    }
}

enum class CoreAppColorScheme(val isDark: Boolean) {
    Light(false),
    Grey(true),
    Black(true),
}

@Composable
fun currentColorScheme(): CoreAppColorScheme {
    val themeProvider: ThemeProvider = koinInject()
    val theme by themeProvider.theme.collectAsState()
    val systemInDarkTheme = isSystemInDarkTheme()
    val colorScheme = remember(theme, systemInDarkTheme) {
        when (theme) {
            CoreAppTheme.Light -> CoreAppColorScheme.Light
            CoreAppTheme.Dark -> CoreAppColorScheme.Grey
            CoreAppTheme.Black -> CoreAppColorScheme.Black
            CoreAppTheme.System -> if (systemInDarkTheme) CoreAppColorScheme.Grey else CoreAppColorScheme.Light
        }
    }
    return colorScheme
}

@Composable
fun AppTheme(
    content: @Composable() () -> Unit
) {
    val colorScheme = currentColorScheme()
    val extendedColors = if (colorScheme.isDark) darkExtendedColors else lightExtendedColors
    setStatusBarTheme(colorScheme)
    val materialColorScheme = when (colorScheme) {
        CoreAppColorScheme.Light -> lightScheme
        CoreAppColorScheme.Grey -> greyScheme
        CoreAppColorScheme.Black -> blackScheme
    }
    CompositionLocalProvider(LocalExtendedColors provides extendedColors) {
        MaterialTheme(
            colorScheme = materialColorScheme,
            typography = AppTypography(),
            content = content
        )
    }
}

expect @Composable fun setStatusBarTheme(colorScheme: CoreAppColorScheme)

interface ThemeProvider {
    val theme: StateFlow<CoreAppTheme>
    fun setTheme(theme: CoreAppTheme)
}

class RealThemeProvider(
    private val settings: Settings,
) : ThemeProvider {
    private val _theme = MutableStateFlow(getTheme())
    override val theme: StateFlow<CoreAppTheme> = _theme.asStateFlow()

    private fun getTheme(): CoreAppTheme {
        val key = settings.getStringOrNull(THEME_SETTINGS_KEY)
        return key.asCoreAppTheme()
    }

    override fun setTheme(theme: CoreAppTheme) {
        settings.putString(THEME_SETTINGS_KEY, theme.key)
        _theme.value = theme
    }

    companion object {
        private const val THEME_SETTINGS_KEY = "current_theme"
    }
}

object ExtendedTheme {
    val colors: ExtendedColors
        @Composable
        get() = LocalExtendedColors.current
}