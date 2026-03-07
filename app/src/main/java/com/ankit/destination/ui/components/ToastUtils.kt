package com.ankit.destination.ui.components

import android.content.Context
import android.widget.Toast

fun Context.showShortToast(message: String) {
    Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
}
