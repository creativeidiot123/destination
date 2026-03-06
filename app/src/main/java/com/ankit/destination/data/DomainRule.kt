package com.ankit.destination.data

import androidx.room.Entity

@Entity(
    tableName = "domain_rules",
    primaryKeys = ["domain", "scopeType", "scopeId"]
)
data class DomainRule(
    val domain: String,
    val scopeType: String,
    val scopeId: String,
    val blocked: Boolean
)
