package com.ankit.destination.ui.components

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

@Composable
fun ReasonChips(reasons: Map<String, String>) {
    Text(reasons.toString())
}
