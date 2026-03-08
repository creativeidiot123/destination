package com.ankit.destination.policy

/**
 * Converts the internal block-reason sets into a single stable primary reason token per package.
 *
 * Contract (must remain stable):
 * - Global tokens: ALWAYS_BLOCKED, STRICT_INSTALL, USAGE_ACCESS_RECOVERY_LOCKDOWN, BUDGET
 * - Scoped tokens: GROUP_<EffectiveBlockReason>, APP_<EffectiveBlockReason>
 *
 * This utility is intentionally tolerant of legacy/internal formats:
 * - GROUP:<groupId>:<REASON>
 * - APP:<REASON>
 * - GROUP_<REASON>, APP_<REASON>
 * - SCHEDULE_GROUP (legacy)
 * - APP:USAGE_BLOCK (legacy mapping)
 */
internal object BlockReasonUtils {
    private data class Candidate(
        val priority: Int,
        val primaryToken: String
    )

    fun derivePrimaryByPackage(blockReasonsByPackage: Map<String, Set<String>>): Map<String, String> {
        return blockReasonsByPackage.entries
            .asSequence()
            .mapNotNull { (rawPkg, reasons) ->
                val pkg = rawPkg.trim()
                if (pkg.isBlank()) return@mapNotNull null
                val reason = derivePrimaryReason(reasons)
                if (reason.isBlank()) return@mapNotNull null
                pkg to reason
            }
            .toMap()
    }

    fun derivePrimaryReason(reasons: Set<String>): String {
        val candidates = reasons
            .asSequence()
            .mapNotNull(::toCandidate)
            .toList()

        if (candidates.isEmpty()) return ""

        return candidates
            .sortedWith(compareBy<Candidate> { it.priority }.thenBy { it.primaryToken })
            .first()
            .primaryToken
    }

    private fun toCandidate(raw: String): Candidate? {
        val reason = raw.trim()
        if (reason.isBlank()) return null

        val upper = reason.uppercase()

        when (upper) {
            EffectiveBlockReason.ALWAYS_BLOCKED.name ->
                return Candidate(priority = 0, primaryToken = EffectiveBlockReason.ALWAYS_BLOCKED.name)

            EffectiveBlockReason.USAGE_ACCESS_RECOVERY_LOCKDOWN.name ->
                return Candidate(priority = 1, primaryToken = EffectiveBlockReason.USAGE_ACCESS_RECOVERY_LOCKDOWN.name)

            EffectiveBlockReason.STRICT_INSTALL.name ->
                return Candidate(priority = 2, primaryToken = EffectiveBlockReason.STRICT_INSTALL.name)

            "BUDGET" ->
                return Candidate(priority = 7, primaryToken = "BUDGET")

            // Legacy: schedule-as-global label.
            "SCHEDULE_GROUP" ->
                return Candidate(priority = 3, primaryToken = "GROUP_${EffectiveBlockReason.SCHEDULED_BLOCK.name}")
        }

        // Legacy: some builds used APP:USAGE_BLOCK to represent budget.
        if (upper.contains("USAGE_BLOCK")) {
            return Candidate(priority = 7, primaryToken = "BUDGET")
        }

        // Structured evaluator reasons:
        //   GROUP:<groupId>:<REASON>
        //   APP:<REASON>
        // We intentionally discard the groupId in the primary token to keep UI stable.
        runCatching {
            val parts = reason.split(':')
            if (parts.size >= 2) {
                val scope = parts.first().trim().uppercase()
                val tail = parts.last().trim().uppercase()
                val blockReason = parseEffectiveReason(tail) ?: parseLooseEffectiveReason(upper)
                if (blockReason != null) {
                    val primary = when (scope) {
                        "GROUP" -> "GROUP_${blockReason.name}"
                        "APP" -> "APP_${blockReason.name}"
                        else -> blockReason.name
                    }
                    return Candidate(priorityFor(blockReason), primary)
                }
            }
        }

        if (upper.startsWith("GROUP_") || upper.startsWith("APP_")) {
            val scope = upper.substringBefore('_')
            val tail = upper.substringAfter('_')
            val blockReason = parseEffectiveReason(tail) ?: parseLooseEffectiveReason(upper)
            if (blockReason != null) {
                return Candidate(priorityFor(blockReason), "${scope}_${
                    blockReason.name
                }")
            }
            return Candidate(priority = 100, primaryToken = reason)
        }

        // Loose/fallback: infer a reason class from the token.
        val loose = parseLooseEffectiveReason(upper) ?: return Candidate(priority = 100, primaryToken = reason)
        val scope = if (upper.contains("GROUP")) "GROUP" else "APP"
        return Candidate(priorityFor(loose), "${scope}_${loose.name}")
    }

    private fun parseEffectiveReason(token: String): EffectiveBlockReason? {
        return runCatching { EffectiveBlockReason.valueOf(token) }.getOrNull()
    }

    private fun parseLooseEffectiveReason(valueUpper: String): EffectiveBlockReason? {
        return when {
            valueUpper.contains("SCHEDULED_BLOCK_ALL_APPS") ||
                valueUpper.contains("SCHEDULED_BLOCK") ||
                valueUpper.contains("SCHEDULE_GROUP") ->
                EffectiveBlockReason.SCHEDULED_BLOCK

            valueUpper.contains("HOURLY_CAP") ->
                EffectiveBlockReason.HOURLY_CAP

            valueUpper.contains("DAILY_CAP") ->
                EffectiveBlockReason.DAILY_CAP

            valueUpper.contains("OPENS_CAP") ->
                EffectiveBlockReason.OPENS_CAP

            else -> null
        }
    }

    private fun priorityFor(reason: EffectiveBlockReason): Int {
        return when (reason) {
            EffectiveBlockReason.ALWAYS_BLOCKED -> 0
            EffectiveBlockReason.USAGE_ACCESS_RECOVERY_LOCKDOWN -> 1
            EffectiveBlockReason.STRICT_INSTALL -> 2
            EffectiveBlockReason.SCHEDULED_BLOCK -> 3
            EffectiveBlockReason.HOURLY_CAP -> 4
            EffectiveBlockReason.DAILY_CAP -> 5
            EffectiveBlockReason.OPENS_CAP -> 6
            EffectiveBlockReason.NONE -> 99
        }
    }
}
