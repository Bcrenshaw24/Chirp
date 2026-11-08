package com.example.chirp.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

private val DarkColorScheme = darkColorScheme(
    primary = Teal300,
    onPrimary = Navy950,
    primaryContainer = Teal700,
    onPrimaryContainer = Color(0xFFCCFBF1),
    secondary = Sky300,
    onSecondary = Navy950,
    secondaryContainer = Navy700,
    onSecondaryContainer = Slate200,
    tertiary = Amber300,
    onTertiary = Navy950,
    tertiaryContainer = Color(0xFF4A3510),
    onTertiaryContainer = Color(0xFFFDE68A),
    error = Coral300,
    onError = Navy950,
    errorContainer = Color(0xFF5C1A1A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Navy950,
    onBackground = Slate200,
    surface = Navy950,
    onSurface = Slate200,
    surfaceVariant = Navy800,
    onSurfaceVariant = Slate400,
    surfaceContainerLowest = Navy950,
    surfaceContainerLow = Navy900,
    surfaceContainer = Navy850,
    surfaceContainerHigh = Navy800,
    surfaceContainerHighest = Navy700,
    outline = Color(0xFF475569),
    outlineVariant = Color(0xFF243552),
)

private val LightColorScheme = lightColorScheme(
    primary = Teal700,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCCFBF1),
    onPrimaryContainer = Color(0xFF042F2E),
    secondary = Sky700,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDBEAFE),
    onSecondaryContainer = Color(0xFF172554),
    tertiary = Amber700,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFEF3C7),
    onTertiaryContainer = Color(0xFF451A03),
    error = Coral700,
    onError = Color.White,
    errorContainer = Color(0xFFFFE4E1),
    onErrorContainer = Color(0xFF410002),
    background = Mist50,
    onBackground = Ink900,
    surface = Mist50,
    onSurface = Ink900,
    surfaceVariant = Mist200,
    onSurfaceVariant = Ink600,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color.White,
    surfaceContainerHigh = Mist100,
    surfaceContainerHighest = Mist200,
    outline = Color(0xFF94A3B8),
    outlineVariant = Color(0xFFD5DEEA),
)

private val ChirpShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun ChirpTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Off by default so the sonar brand colours show; flip on to follow the wallpaper (Android 12+).
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = ChirpShapes,
        content = content
    )
}
