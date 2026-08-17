package com.tastyradio.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * Dark-first, and dynamic colour where the device offers it — the reference app's playback pill
 * takes its colour from the system wallpaper, and that's most of why it looks good.
 */
@Composable
fun TastyRadioTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val dynamicAvailable = dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    val colorScheme = when {
        dynamicAvailable && darkTheme -> dynamicDarkColorScheme(context)
        dynamicAvailable -> dynamicLightColorScheme(context)
        darkTheme -> FallbackDark
        else -> FallbackLight
    }

    MaterialTheme(colorScheme = colorScheme, content = content)
}

/** Only reached below API 31. Blue, because the reference screenshots happened to be blue. */
private val FallbackDark = darkColorScheme(
    primary = Color(0xFF9FC7FF),
    onPrimary = Color(0xFF00325B),
    primaryContainer = Color(0xFF1F5FBF),
    onPrimaryContainer = Color(0xFFFFFFFF),
    background = Color(0xFF000000),
    surface = Color(0xFF000000),
    surfaceVariant = Color(0xFF16181C),
)

private val FallbackLight = lightColorScheme(
    primary = Color(0xFF1F5FBF),
    primaryContainer = Color(0xFF2C6FD1),
    onPrimaryContainer = Color(0xFFFFFFFF),
)
