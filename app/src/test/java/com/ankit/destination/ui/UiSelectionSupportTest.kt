package com.ankit.destination.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiSelectionSupportTest {
    @Test
    fun buildAppOptions_keepsIncludedPackagesOutsideCachedCatalog() {
        val options = buildAppOptions(
            catalogEntries = listOf(
                InstalledAppCatalogEntry("pkg.launchable", "Launchable")
            ),
            includedEntries = listOf(
                InstalledAppCatalogEntry("pkg.hidden", "Hidden")
            ),
            disabledPackageReasons = emptyMap()
        )

        assertEquals(
            listOf("pkg.hidden", "pkg.launchable"),
            options.map(AppOption::packageName)
        )
    }

    @Test
    fun buildAppOptions_appliesDisabledMetadataWithoutChangingLabels() {
        val options = buildAppOptions(
            catalogEntries = listOf(
                InstalledAppCatalogEntry("pkg.one", "One"),
                InstalledAppCatalogEntry("pkg.two", "Two")
            ),
            includedEntries = emptyList(),
            disabledPackageReasons = mapOf("pkg.two" to "Locked")
        )

        val enabledOption = options.first { it.packageName == "pkg.one" }
        val disabledOption = options.first { it.packageName == "pkg.two" }

        assertTrue(enabledOption.isSelectable)
        assertEquals("One", enabledOption.label)
        assertFalse(disabledOption.isSelectable)
        assertEquals("Two", disabledOption.label)
        assertEquals("Locked", disabledOption.supportingTag)
    }
}
