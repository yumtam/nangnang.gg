package com.example.nangnang.db.logdamage


import org.springframework.data.mongodb.core.mapping.Document


@Document("logdamage")
data class LogDamage(
    val matchId: String,
    val time: Long,
    val phase: Double,
    val attacker: String,
    val victim: String,
    val reason: String,
    val damage: Double,
    val attackerHealth: Double,
    val victimHealth: Double,
    val distance: Int,
    val isVictimBot: Boolean,
)