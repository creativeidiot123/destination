package com.ankit.destination.policy

import java.net.URI
import java.util.Base64

object ProvisioningConfig {
    const val apkDownloadUrl: String = "https://example.com/destination.apk"
    const val apkSha256Base64: String = "BASE64_SHA256_APK"

    const val extraProvisioningAdminExtrasBundle: String =
        "android.app.extra.PROVISIONING_ADMIN_EXTRAS_BUNDLE"
    const val adminExtraSource: String = "source"
    const val adminExtraEnrollmentId: String = "enrollment_id"
    const val adminExtraSchemaVersion: String = "schema_version"

    private const val defaultEnrollmentId: String = "destination-managed-device"
    private const val defaultSchemaVersion: Int = 1
    private const val sha256ByteLength: Int = 32
    private val componentPattern = Regex("^[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+$")
    private val urlSafeBase64Pattern = Regex("^[A-Za-z0-9_-]+={0,2}$")

    enum class ChecksumType(val extraKey: String) {
        PACKAGE("android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_CHECKSUM"),
        SIGNATURE("android.app.extra.PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM")
    }

    data class ValidationResult(val errors: List<String>) {
        val isReady: Boolean = errors.isEmpty()
    }

    data class AdminExtras(
        val source: String = "qr",
        val enrollmentId: String = defaultEnrollmentId,
        val schemaVersion: Int = defaultSchemaVersion
    ) {
        fun asMap(): Map<String, Any> {
            return linkedMapOf(
                adminExtraSource to source,
                adminExtraEnrollmentId to enrollmentId,
                adminExtraSchemaVersion to schemaVersion
            )
        }
    }

    data class QrConfig(
        val adminComponent: String,
        val apkDownloadUrl: String,
        val checksumBase64: String,
        val checksumType: ChecksumType = ChecksumType.PACKAGE,
        val leaveAllSystemAppsEnabled: Boolean = true,
        val adminExtras: AdminExtras = defaultAdminExtras()
    ) {
        fun validate(): ValidationResult = validateQrConfig(this)

        fun toPayloadMap(): Map<String, Any> {
            val validation = validate()
            require(validation.isReady) {
                "QR provisioning config invalid: ${validation.errors.joinToString("; ")}"
            }
            return linkedMapOf(
                "android.app.extra.PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME" to adminComponent.trim(),
                "android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION" to apkDownloadUrl.trim(),
                checksumType.extraKey to checksumBase64.trim(),
                "android.app.extra.PROVISIONING_LEAVE_ALL_SYSTEM_APPS_ENABLED" to leaveAllSystemAppsEnabled,
                extraProvisioningAdminExtrasBundle to adminExtras.asMap()
            )
        }

        fun toJson(): String = toJson(toPayloadMap())
    }

    fun defaultAdminExtras(): AdminExtras = AdminExtras()

    fun defaultQrConfig(adminComponent: String): QrConfig {
        return QrConfig(
            adminComponent = adminComponent.trim(),
            apkDownloadUrl = apkDownloadUrl.trim(),
            checksumBase64 = apkSha256Base64.trim(),
            adminExtras = defaultAdminExtras()
        )
    }

    fun validateQrProvisioning(adminComponent: String): ValidationResult {
        return defaultQrConfig(adminComponent).validate()
    }

    fun isQrProvisioningReady(adminComponent: String = "com.ankit.destination/.admin.FocusDeviceAdminReceiver"): Boolean {
        return validateQrProvisioning(adminComponent).isReady
    }

    fun buildQrPayload(adminComponent: String): String {
        return defaultQrConfig(adminComponent).toJson()
    }

    private fun validateQrConfig(config: QrConfig): ValidationResult {
        val errors = mutableListOf<String>()
        validateAdminComponent(config.adminComponent)?.let(errors::add)
        validateDownloadUrl(config.apkDownloadUrl)?.let(errors::add)
        validateChecksum(config.checksumBase64)?.let(errors::add)
        validateAdminExtras(config.adminExtras).forEach(errors::add)
        return ValidationResult(errors)
    }

    private fun validateAdminComponent(adminComponent: String): String? {
        val normalized = adminComponent.trim()
        if (normalized.isBlank()) return "Admin component is blank"
        if (!componentPattern.matches(normalized)) {
            return "Admin component must look like com.example/.Receiver"
        }
        val parts = normalized.split('/', limit = 2)
        if (parts.size != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            return "Admin component must include package and receiver"
        }
        return null
    }

    private fun validateDownloadUrl(downloadUrl: String): String? {
        val normalized = downloadUrl.trim()
        if (normalized.isBlank()) return "APK download URL is blank"
        if (normalized.contains("example.com")) return "APK download URL still points at example.com"
        val uri = runCatching { URI(normalized) }.getOrNull() ?: return "APK download URL is not a valid URI"
        if (!uri.isAbsolute || !uri.scheme.equals("https", ignoreCase = true)) {
            return "APK download URL must use HTTPS"
        }
        if (uri.host.isNullOrBlank()) return "APK download URL must include a host"
        return null
    }

    private fun validateChecksum(checksumBase64: String): String? {
        val normalized = checksumBase64.trim()
        if (normalized.isBlank()) return "APK checksum is blank"
        if (normalized.contains("BASE64", ignoreCase = true)) return "APK checksum still contains a placeholder"
        if (!urlSafeBase64Pattern.matches(normalized)) {
            return "APK checksum must be URL-safe base64"
        }
        val decoded = decodeUrlSafeBase64(normalized) ?: return "APK checksum is not valid base64"
        if (decoded.size != sha256ByteLength) {
            return "APK checksum must decode to a 32-byte SHA-256 digest"
        }
        return null
    }

    private fun validateAdminExtras(adminExtras: AdminExtras): List<String> {
        val errors = mutableListOf<String>()
        if (adminExtras.source.isBlank()) errors += "Provisioning source is blank"
        if (adminExtras.enrollmentId.isBlank()) errors += "Enrollment ID is blank"
        if (adminExtras.schemaVersion < 1) errors += "Schema version must be >= 1"
        return errors
    }

    private fun decodeUrlSafeBase64(value: String): ByteArray? {
        val padded = when (value.length % 4) {
            0 -> value
            2 -> "$value=="
            3 -> "$value="
            else -> return null
        }
        return runCatching { Base64.getUrlDecoder().decode(padded) }.getOrNull()
    }

    private fun toJson(value: Any): String = buildString {
        appendJsonValue(this, value, 0)
    }

    private fun appendJsonValue(builder: StringBuilder, value: Any, indent: Int) {
        when (value) {
            is Map<*, *> -> appendJsonObject(builder, value, indent)
            is String -> {
                builder.append('"')
                value.forEach { ch ->
                    when (ch) {
                        '\\' -> builder.append("\\\\")
                        '"' -> builder.append("\\\"")
                        '\b' -> builder.append("\\b")
                        '\u000c' -> builder.append("\\f")
                        '\n' -> builder.append("\\n")
                        '\r' -> builder.append("\\r")
                        '\t' -> builder.append("\\t")
                        else -> {
                            if (ch < ' ') {
                                builder.append("\\u")
                                builder.append(ch.code.toString(16).padStart(4, '0'))
                            } else {
                                builder.append(ch)
                            }
                        }
                    }
                }
                builder.append('"')
            }
            is Boolean, is Number -> builder.append(value.toString())
            else -> error("Unsupported JSON value: ${value::class.java.name}")
        }
    }

    private fun appendJsonObject(builder: StringBuilder, values: Map<*, *>, indent: Int) {
        builder.append('{')
        if (values.isNotEmpty()) builder.append('\n')
        val entries = values.entries.toList()
        entries.forEachIndexed { index, entry ->
            repeat(indent + 2) { builder.append(' ') }
            appendJsonValue(builder, entry.key as String, indent + 2)
            builder.append(":")
            val childValue = requireNotNull(entry.value) { "QR payload does not support null values" }
            if (childValue is Map<*, *>) {
                builder.append('\n')
                appendJsonValue(builder, childValue, indent + 2)
            } else {
                appendJsonValue(builder, childValue, indent + 2)
            }
            if (index != entries.lastIndex) builder.append(',')
            builder.append('\n')
        }
        if (values.isNotEmpty()) repeat(indent) { builder.append(' ') }
        builder.append('}')
    }
}