package com.vibeplayer.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * The app's colours come from the system (Material You): on the phone's DerpFest ROM that is the ROM's
 * accent and wallpaper palette, so the app follows its theme and any later change to it. Always dark
 * (a video player); minSdk 37, so dynamic colour is always there.
 */
@Composable
fun VibeTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val scheme = remember(context) { dynamicDarkColorScheme(context) }
    MaterialTheme(colorScheme = scheme, content = content)
}
