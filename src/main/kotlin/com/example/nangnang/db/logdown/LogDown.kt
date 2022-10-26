package com.example.nangnang.db.logdown

import org.springframework.data.mongodb.core.mapping.Document

@Document("logdown")
data class LogDown (
    val matchId: String,
    val time: Long,
    val attacker: String,
    val victim: String,
    val damageReason: String,
    val attackerHealth: Double,
    val victimWeapon: String,
    val distance: Int,
    val isVictimBot: Boolean,
)