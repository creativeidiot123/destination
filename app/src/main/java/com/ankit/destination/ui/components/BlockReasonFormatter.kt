package com.ankit.destination.ui.components

import com.ankit.destination.policy.EffectiveBlockReason
import java.text.DateFormat
import java.util.Date

object BlockReasonFormatter {
    fun format(reason: EffectiveBlockReason, untilMs: Long? = null): String = when (reason) {
        EffectiveBlockReason.SCHEDULED_BLOCK -> "Blocked - Scheduled block" + formatUntil(untilMs)
        EffectiveBlockReason.HOURLY_CAP -> "Blocked - Hourly cap reached"
        EffectiveBlockReason.DAILY_CAP -> "Blocked - Daily cap reached"
        EffectiveBlockReason.OPENS_CAP -> "Blocked - Daily opens limit reached"
        EffectiveBlockReason.STRICT_INSTALL -> "Blocked - New installs blocked during schedule"
        EffectiveBlockReason.ALWAYS_BLOCKED -> "Always blocked"
        EffectiveBlockReason.ACCESSIBILITY_RECOVERY_LOCKDOWN -> "Blocked - Accessibility recovery required"
        EffectiveBlockReason.USAGE_ACCESS_RECOVERY_LOCKDOWN -> "Blocked - Usage Access recovery required"
        EffectiveBlockReason.NONE -> "Available"
    }

    private fun formatUntil(untilMs: Long?): String {
        if (untilMs == null || untilMs <= 0) return ""
        return " until " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(untilMs))
    }

    fun formatEmergency(untilMs: Long): String {
        return "Temporarily unlocked - Emergency until ${formatTime(untilMs)}"
    }

    fun formatTime(timeMs: Long): String {
        if (timeMs <= 0) return "unknown time"
        return DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(timeMs))
    }
}
