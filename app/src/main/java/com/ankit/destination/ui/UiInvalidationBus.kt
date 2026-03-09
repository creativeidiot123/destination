package com.ankit.destination.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class UiInvalidation(
    val version: Long = 0L,
    val reason: String? = null
)

object UiInvalidationBus {
    private val _latest = MutableStateFlow(UiInvalidation())

    val latest: StateFlow<UiInvalidation> = _latest.asStateFlow()

    fun invalidate(reason: String) {
        _latest.update { current ->
            UiInvalidation(
                version = current.version + 1L,
                reason = reason
            )
        }
    }
}
