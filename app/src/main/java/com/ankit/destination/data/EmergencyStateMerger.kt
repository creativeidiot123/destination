package com.ankit.destination.data

data class MergedEmergencyState(
    val targetType: String,
    val targetId: String,
    val unlocksUsedToday: Int,
    val activeUntilEpochMs: Long?
)

object EmergencyStateMerger {
    fun merge(
        dayKey: String,
        nowMs: Long,
        rows: List<EmergencyState>
    ): List<MergedEmergencyState> {
        if (rows.isEmpty()) return emptyList()

        return rows
            .asSequence()
            .filter { it.targetId.isNotBlank() && it.targetType.isNotBlank() }
            .groupBy { it.targetType.trim() to it.targetId.trim() }
            .mapNotNull { (key, states) ->
                val targetType = key.first
                val targetId = key.second
                if (targetType.isBlank() || targetId.isBlank()) return@mapNotNull null

                val todayState = states.firstOrNull { it.dayKey == dayKey }
                val usedToday = (todayState?.unlocksUsedToday ?: 0).coerceAtLeast(0)
                val activeUntil = states
                    .asSequence()
                    .mapNotNull { it.activeUntilEpochMs }
                    .maxOrNull()
                    ?.takeIf { it > nowMs }

                MergedEmergencyState(
                    targetType = targetType,
                    targetId = targetId,
                    unlocksUsedToday = usedToday,
                    activeUntilEpochMs = activeUntil
                )
            }
            .sortedWith(compareBy<MergedEmergencyState> { it.targetType }.thenBy { it.targetId })
    }
}
