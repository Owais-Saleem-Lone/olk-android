package com.openlibrarykashmir.olk.core.designsystem.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    primary = OlkPalette.TealDark,
    onPrimary = OlkPalette.White,
    primaryContainer = OlkPalette.TealLight,
    onPrimaryContainer = OlkPalette.Slate,

    secondary = OlkPalette.SlateMuted,
    onSecondary = OlkPalette.White,
    secondaryContainer = OlkPalette.Cream,
    onSecondaryContainer = OlkPalette.Slate,

    tertiary = OlkPalette.Teal,
    onTertiary = OlkPalette.White,

    background = OlkPalette.White,
    onBackground = OlkPalette.Ink,
    surface = OlkPalette.White,
    onSurface = OlkPalette.Ink,
    surfaceVariant = OlkPalette.Cream,
    onSurfaceVariant = OlkPalette.SlateMuted,
    surfaceTint = OlkPalette.TealDark,

    surfaceContainerLowest = OlkPalette.PaperLowest,
    surfaceContainerLow = OlkPalette.PaperLow,
    surfaceContainer = OlkPalette.Paper,
    surfaceContainerHigh = OlkPalette.PaperHigh,
    surfaceContainerHighest = OlkPalette.PaperHighest,

    outline = OlkPalette.Slate400,
    outlineVariant = OlkPalette.Slate200,

    error = OlkPalette.Error,
    onError = OlkPalette.White,
)

private val DarkColors = darkColorScheme(
    primary = OlkPalette.TealLight,
    onPrimary = OlkPalette.Slate,
    primaryContainer = OlkPalette.TealDark,
    onPrimaryContainer = OlkPalette.White,

    secondary = OlkPalette.TealLight,
    onSecondary = OlkPalette.Slate,
    secondaryContainer = OlkPalette.SlateLight,
    onSecondaryContainer = OlkPalette.OffWhite,

    tertiary = OlkPalette.Teal,
    onTertiary = OlkPalette.Slate,

    background = OlkPalette.NearBlack,
    onBackground = OlkPalette.OffWhite,
    surface = OlkPalette.NearBlack,
    onSurface = OlkPalette.OffWhite,
    surfaceVariant = OlkPalette.SlateLight,
    onSurfaceVariant = OlkPalette.OffWhite,
    surfaceTint = OlkPalette.TealLight,

    surfaceContainerLowest = OlkPalette.Slate950,
    surfaceContainerLow = OlkPalette.SlateDeep,
    surfaceContainer = OlkPalette.Slate,
    surfaceContainerHigh = OlkPalette.SlateLight,
    surfaceContainerHighest = OlkPalette.Slate750,

    outline = OlkPalette.Slate500,
    outlineVariant = OlkPalette.SlateMuted,

    error = OlkPalette.ErrorDark,
    onError = OlkPalette.Slate,
)

/**
 * @param dynamicColor opt into Material You wallpaper-derived colour on Android 12+.
 *   Defaults to `false`: OLK's teal is part of its identity, and a book-exchange app
 *   that changes colour with the user's wallpaper reads as generic. Exposed so it can
 *   become a user setting later without touching call sites.
 */
@Composable
fun OlkTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = OlkTypography,
        content = content,
    )
}
