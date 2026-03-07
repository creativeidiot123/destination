package com.ankit.destination.budgets

const val MAX_EMERGENCY_MINUTES_PER_UNLOCK = 15
const val MAX_EMERGENCY_UNLOCKS_PER_DAY = 5

fun clampEmergencyMinutesPerUnlock(value: Int): Int =
    value.coerceIn(0, MAX_EMERGENCY_MINUTES_PER_UNLOCK)

fun clampEmergencyUnlocksPerDay(value: Int): Int =
    value.coerceIn(0, MAX_EMERGENCY_UNLOCKS_PER_DAY)
