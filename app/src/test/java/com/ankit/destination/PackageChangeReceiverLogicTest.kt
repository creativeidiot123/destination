package com.ankit.destination

import com.ankit.destination.packages.PackageChangeReceiver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PackageChangeReceiverLogicTest {

    @Test
    fun installedPackageNamesForStrictSchedule_selectsOnlyPackageAddedEvents() {
        val packages = PackageChangeReceiver.installedPackageNamesForStrictSchedule(
            listOf(
                PackageChangeReceiver.Companion.StrictScheduleInstallEvent(
                    action = "android.intent.action.PACKAGE_ADDED",
                    packageName = "pkg.one",
                    sawPackageAdded = true
                ),
                PackageChangeReceiver.Companion.StrictScheduleInstallEvent(
                    action = "android.intent.action.PACKAGE_REMOVED",
                    packageName = "pkg.one",
                    sawPackageAdded = false
                ),
                PackageChangeReceiver.Companion.StrictScheduleInstallEvent(
                    action = "android.intent.action.PACKAGE_REPLACED",
                    packageName = "pkg.two",
                    sawPackageAdded = false
                ),
                PackageChangeReceiver.Companion.StrictScheduleInstallEvent(
                    action = "android.intent.action.PACKAGE_ADDED",
                    packageName = "pkg.three",
                    sawPackageAdded = true
                )
            )
        )

        assertEquals(setOf("pkg.one", "pkg.three"), packages)
    }

    @Test
    fun installedPackageNamesForStrictSchedule_trimsAndDeduplicatesPackages() {
        val packages = PackageChangeReceiver.installedPackageNamesForStrictSchedule(
            listOf(
                PackageChangeReceiver.Companion.StrictScheduleInstallEvent(
                    action = "android.intent.action.PACKAGE_ADDED",
                    packageName = " pkg.one ",
                    sawPackageAdded = true
                ),
                PackageChangeReceiver.Companion.StrictScheduleInstallEvent(
                    action = "android.intent.action.PACKAGE_ADDED",
                    packageName = "",
                    sawPackageAdded = true
                ),
                PackageChangeReceiver.Companion.StrictScheduleInstallEvent(
                    action = "android.intent.action.PACKAGE_ADDED",
                    packageName = "pkg.one",
                    sawPackageAdded = true
                )
            )
        )

        assertEquals(setOf("pkg.one"), packages)
    }

    @Test
    fun installedPackageNamesForStrictSchedule_preservesAddedPackagesAfterBatchOverwrite() {
        val packages = PackageChangeReceiver.installedPackageNamesForStrictSchedule(
            listOf(
                PackageChangeReceiver.Companion.StrictScheduleInstallEvent(
                    action = "android.intent.action.PACKAGE_REPLACED",
                    packageName = "pkg.one",
                    sawPackageAdded = true
                )
            )
        )

        assertEquals(setOf("pkg.one"), packages)
    }

    @Test
    fun installedPackageNamesForStrictSchedule_includesNormalizedReplacedPackages() {
        val packages = PackageChangeReceiver.installedPackageNamesForStrictSchedule(
            listOf(
                PackageChangeReceiver.Companion.StrictScheduleInstallEvent(
                    action = "android.intent.action.PACKAGE_REPLACED",
                    packageName = "pkg.one",
                    sawPackageAdded = false,
                    normalizedFinalState = PackageChangeReceiver.Companion.NormalizedPackageEventState.REPLACED
                ),
                PackageChangeReceiver.Companion.StrictScheduleInstallEvent(
                    action = "android.intent.action.PACKAGE_REMOVED",
                    packageName = "pkg.two",
                    sawPackageAdded = false,
                    normalizedFinalState = PackageChangeReceiver.Companion.NormalizedPackageEventState.REMOVED
                )
            )
        )

        assertEquals(setOf("pkg.one"), packages)
    }

    @Test
    fun normalizedFinalState_mapsAddRemoveReplaceSemantics() {
        assertEquals(
            PackageChangeReceiver.Companion.NormalizedPackageEventState.INSTALLED,
            PackageChangeReceiver.normalizedFinalState(
                sawAdded = true,
                sawRemoved = false,
                sawReplaced = false
            )
        )
        assertEquals(
            PackageChangeReceiver.Companion.NormalizedPackageEventState.REPLACED,
            PackageChangeReceiver.normalizedFinalState(
                sawAdded = false,
                sawRemoved = false,
                sawReplaced = true
            )
        )
        assertEquals(
            PackageChangeReceiver.Companion.NormalizedPackageEventState.REMOVED,
            PackageChangeReceiver.normalizedFinalState(
                sawAdded = false,
                sawRemoved = true,
                sawReplaced = false
            )
        )
        assertNull(
            PackageChangeReceiver.normalizedFinalState(
                sawAdded = false,
                sawRemoved = false,
                sawReplaced = false
            )
        )
    }
}
