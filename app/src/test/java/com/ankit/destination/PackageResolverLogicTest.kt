package com.ankit.destination

import com.ankit.destination.policy.PackageResolver
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PackageResolverLogicTest {

    @Test
    fun isProtectedPackageName_includesControllerApp() {
        assertTrue(PackageResolver.isProtectedPackageName("com.ankit.destination", "com.ankit.destination"))
        assertTrue(PackageResolver.isProtectedPackageName("android", "com.ankit.destination"))
        assertFalse(PackageResolver.isProtectedPackageName("com.example.notes", "com.ankit.destination"))
    }
}