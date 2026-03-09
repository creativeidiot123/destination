package com.ankit.destination.policy

internal object EnforcementStateCodecs {
    fun encodeStringSet(values: Set<String>): String {
        return values.asSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
            .sorted()
            .joinToString(separator = "\n")
    }

    fun decodeStringSet(raw: String?): Set<String> {
        if (raw.isNullOrBlank()) return emptySet()
        return raw.lineSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .toCollection(linkedSetOf())
    }

    fun encodeStringMap(values: Map<String, String>): String {
        if (values.isEmpty()) return ""
        return values.entries.asSequence()
            .mapNotNull { (key, value) ->
                val cleanKey = key.trim()
                val cleanValue = value.trim()
                if (cleanKey.isBlank() || cleanValue.isBlank()) null else "$cleanKey=$cleanValue"
            }
            .joinToString(separator = "\n")
    }

    fun decodeStringMap(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        return raw.lineSequence()
            .mapNotNull { line ->
                val idx = line.indexOf('=')
                if (idx <= 0) {
                    null
                } else {
                    val key = line.substring(0, idx).trim()
                    val value = line.substring(idx + 1).trim()
                    if (key.isBlank() || value.isBlank()) null else key to value
                }
            }
            .toMap(linkedMapOf())
    }

    fun encodeReasonSetMap(values: Map<String, Set<String>>): String {
        if (values.isEmpty()) return ""
        return values.entries.asSequence()
            .mapNotNull { (pkg, reasons) ->
                val cleanPkg = pkg.trim()
                if (cleanPkg.isBlank()) return@mapNotNull null
                val cleanReasons = reasons.asSequence()
                    .map(String::trim)
                    .filter(String::isNotBlank)
                    .distinct()
                    .sorted()
                    .toList()
                if (cleanReasons.isEmpty()) null else "$cleanPkg=${cleanReasons.joinToString(",")}"
            }
            .joinToString(separator = "\n")
    }

    fun decodeReasonSetMap(raw: String?): Map<String, Set<String>> {
        if (raw.isNullOrBlank()) return emptyMap()
        return raw.lineSequence()
            .mapNotNull { line ->
                val idx = line.indexOf('=')
                if (idx <= 0) return@mapNotNull null
                val key = line.substring(0, idx).trim()
                val reasons = line.substring(idx + 1)
                    .split(',')
                    .asSequence()
                    .map(String::trim)
                    .filter(String::isNotBlank)
                    .toCollection(linkedSetOf())
                if (key.isBlank() || reasons.isEmpty()) null else key to reasons
            }
            .toMap(linkedMapOf())
    }
}
