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

    private fun buildSnapshot(rules: List<DomainRule>): DomainPolicySnapshot {
        val blockedGlobal = linkedSetOf<String>()
        val allowedGlobal = linkedSetOf<String>()
        val blockedByGroup = linkedMapOf<String, MutableSet<String>>()

        rules.forEach { rule ->
            val domain = normalizeDomain(rule.domain) ?: return@forEach
            when (rule.scopeType.uppercase()) {
                "GLOBAL" -> if (rule.blocked) blockedGlobal += domain else allowedGlobal += domain
                "GROUP" -> if (rule.blocked) {
                    blockedByGroup.getOrPut(rule.scopeId) { linkedSetOf() }.add(domain)
                }
            }
        }

        return DomainPolicySnapshot(
            blockedDomainsGlobal = blockedGlobal,
            allowedDomainsGlobal = allowedGlobal,
            blockedDomainsByGroup = blockedByGroup.mapValues { it.value.toSet() }
        )
    }

    private fun normalizeDomain(value: String): String? {
        val d = value.trim().lowercase().trim('.')
        if (d.isBlank()) return null
        return d
    }

    private fun matchesAny(domain: String, patterns: Set<String>): Boolean {
        return patterns.any { pattern ->
            domain == pattern || domain.endsWith(".$pattern")
        }
    }
}
