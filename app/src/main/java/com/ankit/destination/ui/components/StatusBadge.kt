package com.ankit.destination.ui.components

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

enum class StatusBadgeType {
    ALLOWED, BLOCKED, SCHEDULED, STRICT, EMERGENCY,
    PROTECTED, LOCKED, IN_GROUP, INDIVIDUAL_RULE,
    ALWAYS_ALLOWED, ALWAYS_BLOCKED, UNINSTALL_PROTECTED
}

@Composable
fun StatusBadge(type: StatusBadgeType) {
    Text(type.name)
}
