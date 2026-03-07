package com.ankit.destination.vpn

import android.content.Context
import com.ankit.destination.data.DomainRule
import com.ankit.destination.data.FocusDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class DomainPolicySnapshot(
    val blockedDomainsGlobal: Set<String>,
    val allowedDomainsGlobal: Set<String>,
    val blockedDomainsByGroup: Map<String, Set<String>>
)

class DomainPolicyEngine(context: Context) {
    private val appContext = context.applicationContext
    private val db by lazy { FocusDatabase.get(appContext) }

    suspend fun loadSnapshot(): DomainPolicySnapshot = withContext(Dispatchers.IO) {
        val rules = db.budgetDao().getAllDomainRules()
        buildSnapshot(rules)
    }

    fun isDomainBlocked(
        domain: String,
        blockedGroupIds: Set<String>,
        snapshot: DomainPolicySnapshot
    ): Boolean {
        val normalized = normalizeDomain(domain) ?: return false
        if (matchesAny(normalized, snapshot.allowedDomainsGlobal)) return false
        if (matchesAny(normalized, snapshot.blockedDomainsGlobal)) return true
        blockedGroupIds.forEach { groupId ->
            val blocked = snapshot.blockedDomainsByGroup[groupId].orEmpty()
            if (matchesAny(normalized, blocked)) return true
        }
        return false
    }

    companion object {
        private const val MAX_DOMAIN_LENGTH = 253
        private const val MAX_LABEL_LENGTH = 63

        internal fun buildSnapshot(rules: List<DomainRule>): DomainPolicySnapshot {
            val blockedGlobal = linkedSetOf<String>()
            val allowedGlobal = linkedSetOf<String>()
            val blockedByGroup = linkedMapOf<String, MutableSet<String>>()

            rules.forEach { rule ->
                val domain = normalizeDomain(rule.domain) ?: return@forEach
                when (rule.scopeType.uppercase()) {
                    "GLOBAL" -> if (rule.blocked) blockedGlobal += domain else allowedGlobal += domain
                    "GROUP" -> if (rule.blocked) {
                        val normalizedScopeId = rule.scopeId.trim()
                        if (normalizedScopeId.isBlank()) return@forEach
                        blockedByGroup.getOrPut(normalizedScopeId) { linkedSetOf() }.add(domain)
                    }
                }
            }

            return DomainPolicySnapshot(
                blockedDomainsGlobal = blockedGlobal,
                allowedDomainsGlobal = allowedGlobal,
                blockedDomainsByGroup = blockedByGroup.mapValues { it.value.toSet() }
            )
        }

        internal fun normalizeDomain(value: String): String? {
            val normalized = value.trim().lowercase().trim('.')
            if (normalized.isBlank() || normalized.length > MAX_DOMAIN_LENGTH) return null
            val labels = normalized.split('.')
            if (labels.any(::isInvalidLabel)) return null
            return normalized
        }

        internal fun matchesAny(domain: String, patterns: Set<String>): Boolean {
            if (patterns.isEmpty()) return false
            var candidate = domain
            while (true) {
                if (patterns.contains(candidate)) return true
                val nextDot = candidate.indexOf('.')
                if (nextDot < 0 || nextDot == candidate.lastIndex) return false
                candidate = candidate.substring(nextDot + 1)
            }
        }

        private fun isInvalidLabel(label: String): Boolean {
            if (label.isBlank() || label.length > MAX_LABEL_LENGTH) return true
            if (label.first() == '-' || label.last() == '-') return true
            return label.any { ch -> !ch.isLowerCase() && !ch.isDigit() && ch != '-' }
        }
    }
}
