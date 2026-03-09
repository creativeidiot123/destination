package com.ankit.destination.policy

enum class ApplyTriggerCategory {
    BOOT,
    USER_UNLOCKED,
    PROCESS_START,
    APP_FOREGROUND,
    ACTIVITY_RESUME,
    ACCESSIBILITY,
    USAGE_ACCESS,
    SCHEDULE,
    PACKAGE_CHANGE,
    STRICT_INSTALL_STAGE,
    MODE_CHANGE,
    POLICY_MUTATION,
    PROVISIONING,
    DIAGNOSTICS,
    MANUAL,
    UNKNOWN
}

data class ApplyTrigger(
    val category: ApplyTriggerCategory,
    val source: String,
    val detail: String? = null,
    val packages: Set<String> = emptySet(),
    val stagedStrictInstall: Boolean = false,
    val atMs: Long = System.currentTimeMillis()
) {
    fun compatibilityLabel(): String {
        val cleanSource = source.trim().ifBlank { category.name.lowercase() }
        val cleanDetail = detail?.trim().orEmpty()
        return if (cleanDetail.isBlank()) cleanSource else "$cleanSource:$cleanDetail"
    }
}

data class ApplyTriggerBatch(
    val triggers: List<ApplyTrigger>
) {
    val categoryCounts: Map<ApplyTriggerCategory, Int> = triggers
        .groupingBy(ApplyTrigger::category)
        .eachCount()
        .toSortedMap(compareBy(Enum<*>::ordinal))

    val containsStrictInstallStage: Boolean = triggers.any { it.stagedStrictInstall || it.category == ApplyTriggerCategory.STRICT_INSTALL_STAGE }

    val compatibilityLabel: String = triggers
        .asSequence()
        .map(ApplyTrigger::compatibilityLabel)
        .filter(String::isNotBlank)
        .distinct()
        .take(MAX_COMPATIBILITY_LABELS)
        .toList()
        .let { labels ->
            when {
                labels.isEmpty() -> "unknown"
                triggers.size <= MAX_COMPATIBILITY_LABELS -> labels.joinToString(",")
                else -> "coalesced(${triggers.size}):${labels.joinToString(",")}"
            }
        }

    fun mergedWith(other: ApplyTriggerBatch): ApplyTriggerBatch = ApplyTriggerBatch(triggers + other.triggers)

    companion object {
        private const val MAX_COMPATIBILITY_LABELS = 5

        fun single(trigger: ApplyTrigger): ApplyTriggerBatch = ApplyTriggerBatch(listOf(trigger))
    }
}

enum class ScheduleTargetDiagnosticCode {
    NONE,
    NO_CONFIGURED_TARGETS,
    NO_CONFIGURED_GROUP_TARGETS,
    NO_CONFIGURED_APP_TARGETS,
    NO_EFFECTIVE_GROUP_TARGETS,
    NO_EFFECTIVE_APP_TARGETS,
    NO_EFFECTIVE_GROUP_AND_APP_TARGETS
}
