package com.example.nangnang.db.loggamestat


import org.springframework.data.mongodb.core.mapping.Document


@Document("loggamestat")
data class LogGameStat(
    val matchId: String,
    val matchStartTime: Long,
    val userName: String,
    val winPlace: Int,
    val maxPlace: Int,
    val rosterId: String,
    val timeSurvived: Int,
    val damageDealt: Double,
    val damageDealtToBots: Double,
    val healAmount: Double,
    val kills: Int,
    val assists: Int,
    val downsSelf: Int,
    val downsEnemy: Int,
    val damageTaken: Double,
    val damageTakenByBluezone: Double,
    val revives: Int,
    val useBandage: Int,
    val useFirstAid: Int,
    val useMedkit: Int,
    val useEnergyDrink: Int,
    val usePainKiller: Int,
    val useAdrenalineSyringe: Int,
)