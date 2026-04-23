package com.revvivii.mydentapp.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary   = DentBlue,
    secondary = DentGreen,
    background = BackgroundDark,
    surface    = SurfaceDark,
    onPrimary  = Color.White,
    onBackground = Color.White,
    onSurface    = Color.White
)

@Composable
fun MyDentAppTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content
    )
}
