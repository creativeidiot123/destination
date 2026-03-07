package com.ankit.destination.policy

internal object BlockReasonUtils {
    private const val ALWAYS_BLOCKED = "ALWAYS_BLOCKED"
    private const val USAGE_ACCESS_RECOVERY_LOCKDOWN = "USAGE_ACCESS_RECOVERY_LOCKDOWN"
    private const val STRICT_INSTALL = "STRICT_INSTALL"

    fun derivePrimaryByPackage(blockReasonsByPackage: Map<String, Set<String>>): Map<String, String> {
        return blockReasonsByPackage.entries
            .asSequence()
            .mapNotNull { entry ->
                val pkg = entry.key.trim()
                if (pkg.isBlank()) return@mapNotNull null
                val reason = derivePrimaryReason(entry.value)
                if (reason.isBlank()) return@mapNotNull null
                pkg to reason
            }
            .toMap()
    }

    fun derivePrimaryReason(reasons: Set<String>): String {
        val normalized = reasons
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .map(::normalizeLegacyReason)
            .toSet()

        if (normalized.isEmpty()) return ""
        return normalized
            .sortedWith(
                compareBy<String> { reasonPriority(it) }
                    .thenBy { it }
            )
            .first()
    }

    private fun reasonPriority(reason: String): Int {
        val normalized = reason.uppercase()
        return when {
            normalized == ALWAYS_BLOCKED -> 0
            normalized == USAGE_ACCESS_RECOVERY_LOCKDOWN -> 1
            normalized.contains("SCHEDULED_BLOCK") -> 2
            normalized.endsWith("HOURLY_CAP") -> 3
            normalized.endsWith("DAILY_CAP") -> 4
            normalized.endsWith("OPENS_CAP") -> 5
            normalized == STRICT_INSTALL -> 6
            else -> 7
        }
    }

    private fun normalizeLegacyReason(reason: String): String {
        return when (reason) {
            "SCHEDULE_GROUP" -> "APP:SCHEDULED_BLOCK"
            "BUDGET" -> "APP:USAGE_BLOCK"
            "ALWAYS_BLOCKED" -> ALWAYS_BLOCKED
            "STRICT_INSTALL" -> STRICT_INSTALL
            "USAGE_ACCESS_RECOVERY_LOCKDOWN" -> USAGE_ACCESS_RECOVERY_LOCKDOWN
            else -> reason
        }
    }
}

