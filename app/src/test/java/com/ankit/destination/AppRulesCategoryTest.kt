package com.ankit.destination

import com.ankit.destination.ui.apprules.AppRuleCategory
import com.ankit.destination.ui.apprules.AppRuleItem
import com.ankit.destination.ui.apprules.belongsTo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppRulesCategoryTest {

    @Test
    fun belongsTo_targetsRequestedCategory_only() {
        val mixedRule = AppRuleItem(
            packageName = "com.example.app",
            label = "Example",
            isAllowlist = true,
            isBlocklist = false,
            isUninstallProtected = true
        )

        assertTrue(mixedRule.belongsTo(AppRuleCategory.ALLOWLIST))
        assertFalse(mixedRule.belongsTo(AppRuleCategory.BLOCKLIST))
        assertTrue(mixedRule.belongsTo(AppRuleCategory.UNINSTALL_PROTECTION))
    }
}
