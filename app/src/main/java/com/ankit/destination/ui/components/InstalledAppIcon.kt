package com.ankit.destination.ui.components

import androidx.compose.foundation.Image
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext

@Composable
fun InstalledAppIcon(
    packageName: String,
    modifier: Modifier = Modifier,
    contentDescription: String? = null
) {
    val context = LocalContext.current
    val imageBitmap by produceState<ImageBitmap?>(initialValue = null, packageName) {
        value = null
        value = InstalledAppIconLoader.loadIcon(context, packageName)
    }

    if (imageBitmap == null) {
        Icon(
            imageVector = Icons.Default.Android,
            contentDescription = contentDescription,
            modifier = modifier
        )
    } else {
        Image(
            bitmap = imageBitmap!!,
            contentDescription = contentDescription,
            modifier = modifier
        )
    }
}
