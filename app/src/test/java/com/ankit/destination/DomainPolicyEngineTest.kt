package com.ankit.destination

import com.ankit.destination.data.DomainRule
import com.ankit.destination.vpn.DomainPolicyEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DomainPolicyEngineTest {

    @Test
    fun normalizeDomain_rejectsMalformedValues() {
        assertNull(DomainPolicyEngine.normalizeDomain(""))
        assertNull(DomainPolicyEngine.normalizeDomain("bad domain.com"))
        assertNull(DomainPolicyEngine.normalizeDomain("-leading.example"))
        assertNull(DomainPolicyEngine.normalizeDomain("trailing-.example"))
        assertNull(DomainPolicyEngine.normalizeDomain("double..dot.example"))
    }

    @Test
    fun normalizeDomain_trimsDotsAndLowercases() {
        assertEquals(
            "sub.example.com",
            DomainPolicyEngine.normalizeDomain(" .Sub.Example.com. ")
        )
    }

    @Test
    fun matchesAny_supportsExactAndParentSuffixMatches() {
        val patterns = setOf("example.com", "allowed.org")

        assertTrue(DomainPolicyEngine.matchesAny("example.com", patterns))
        assertTrue(DomainPolicyEngine.matchesAny("sub.example.com", patterns))
        assertFalse(DomainPolicyEngine.matchesAny("badexample.com", patterns))
        assertFalse(DomainPolicyEngine.matchesAny("example.net", patterns))
    }

    @Test
    fun buildSnapshot_ignoresInvalidDomains_blankGroups_and_groupAllows() {
        val snapshot = DomainPolicyEngine.buildSnapshot(
            listOf(
                DomainRule(domain = "example.com", scopeType = "GLOBAL", scopeId = "", blocked = true),
                DomainRule(domain = "allowed.example.com", scopeType = "GLOBAL", scopeId = "", blocked = false),
                DomainRule(domain = " group.example.com ", scopeType = "GROUP", scopeId = " social ", blocked = true),
                DomainRule(domain = "ignored.example.com", scopeType = "GROUP", scopeId = "", blocked = true),
                DomainRule(domain = "ignored-allow.example.com", scopeType = "GROUP", scopeId = "social", blocked = false),
                DomainRule(domain = "bad domain", scopeType = "GLOBAL", scopeId = "", blocked = true)
            )
        )

        assertEquals(setOf("example.com"), snapshot.blockedDomainsGlobal)
        assertEquals(setOf("allowed.example.com"), snapshot.allowedDomainsGlobal)
        assertEquals(setOf("group.example.com"), snapshot.blockedDomainsByGroup["social"])
        assertFalse(snapshot.blockedDomainsByGroup.containsKey(""))
    }
}
