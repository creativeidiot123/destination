package com.ankit.destination.ui.components

import android.view.SoundEffectConstants
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

@Composable
fun rememberTapFeedback(): () -> Unit {
    val view = LocalView.current
    return remember(view) {
        { view.playSoundEffect(SoundEffectConstants.CLICK) }
    }
}
