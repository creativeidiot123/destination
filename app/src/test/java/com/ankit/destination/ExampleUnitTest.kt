package com.ankit.destination

import com.ankit.destination.policy.ProvisioningConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class ExampleUnitTest {
    private val validChecksum = Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(ByteArray(32) { index -> index.toByte() })

    @Test
    fun qrPayload_containsExpectedFieldsForValidConfig() {
        val json = ProvisioningConfig.QrConfig(
            adminComponent = "com.example/.Receiver",
            apkDownloadUrl = "https://downloads.example.org/destination.apk",
            checksumBase64 = validChecksum
        ).toJson()

        assertTrue(json.contains("\"android.app.extra.PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME\":\"com.example/.Receiver\""))
        assertTrue(json.contains("\"android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_CHECKSUM\":\"$validChecksum\""))
        assertTrue(json.contains("\"android.app.extra.PROVISIONING_ADMIN_EXTRAS_BUNDLE\""))
        assertTrue(json.contains("\"schema_version\":1"))
    }

    @Test
    fun qrPayload_escapesAdminExtrasStrings() {
        val json = ProvisioningConfig.QrConfig(
            adminComponent = "com.example/.Receiver",
            apkDownloadUrl = "https://downloads.example.org/destination.apk",
            checksumBase64 = validChecksum,
            adminExtras = ProvisioningConfig.AdminExtras(
                source = "pilot\"line",
                enrollmentId = "line1\nline2",
                schemaVersion = 2
            )
        ).toJson()

        assertTrue(json.contains("pilot\\\"line"))
        assertTrue(json.contains("line1\\nline2"))
        assertTrue(json.contains("\"schema_version\":2"))
    }

    @Test
    fun qrPayload_escapesControlCharacters() {
        val json = ProvisioningConfig.QrConfig(
            adminComponent = "com.example/.Receiver",
            apkDownloadUrl = "https://downloads.example.org/destination.apk",
            checksumBase64 = validChecksum,
            adminExtras = ProvisioningConfig.AdminExtras(
                source = "a\bb\u000cc\u0001",
                enrollmentId = "ok",
                schemaVersion = 3
            )
        ).toJson()

        assertTrue(json.contains("a\\bb\\fc\\u0001"))
    }

    @Test
    fun defaultQrConfig_usesDefaultAdminExtras() {
        val config = ProvisioningConfig.defaultQrConfig("com.example/.Receiver")

        assertEquals(ProvisioningConfig.defaultAdminExtras(), config.adminExtras)
    }

    @Test
    fun validate_rejectsNonHttpsUrl() {
        val validation = ProvisioningConfig.QrConfig(
            adminComponent = "com.example/.Receiver",
            apkDownloadUrl = "http://downloads.example.org/destination.apk",
            checksumBase64 = validChecksum
        ).validate()

        assertFalse(validation.isReady)
        assertTrue(validation.errors.any { it.contains("HTTPS") })
    }

    @Test
    fun validate_rejectsChecksumThatIsNotSha256Length() {
        val validation = ProvisioningConfig.QrConfig(
            adminComponent = "com.example/.Receiver",
            apkDownloadUrl = "https://downloads.example.org/destination.apk",
            checksumBase64 = "YWJj"
        ).validate()

        assertFalse(validation.isReady)
        assertTrue(validation.errors.any { it.contains("32-byte SHA-256") })
    }
}