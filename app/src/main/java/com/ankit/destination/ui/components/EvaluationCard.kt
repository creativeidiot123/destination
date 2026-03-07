package com.ankit.destination.ui.components

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

@Composable
fun EvaluationCard(
    baselineBlocked: Boolean,
    baselineReason: String,
    emergencyActive: Boolean,
    emergencyUntil: String?,
    effectiveBlocked: Boolean,
    strictInstallActive: Boolean
) {
    Text("Baseline Blocked: $baselineBlocked, Reason: $baselineReason")
}
