package com.ankit.destination.ui

import java.util.Locale

internal fun normalizeSearchQuery(query: String): String {
    return query.trim().lowercase(Locale.ROOT)
}

internal fun buildSearchText(vararg values: String): String {
    return values.joinToString(separator = "\n") { value ->
        value.trim().lowercase(Locale.ROOT)
    }
}
