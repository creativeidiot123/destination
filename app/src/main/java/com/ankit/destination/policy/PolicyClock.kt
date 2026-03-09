package com.ankit.destination.policy

import java.time.ZonedDateTime

internal interface PolicyClock {
    fun now(): ZonedDateTime
    fun nowMs(): Long
}

internal object SystemPolicyClock : PolicyClock {
    override fun now(): ZonedDateTime = ZonedDateTime.now()

    override fun nowMs(): Long = System.currentTimeMillis()
}
