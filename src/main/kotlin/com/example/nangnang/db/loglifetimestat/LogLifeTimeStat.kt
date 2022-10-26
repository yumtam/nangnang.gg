package com.example.nangnang.db.loglifetimestat


import org.springframework.data.mongodb.core.mapping.Document


@Document("loglifetimestat")
data class LogLifeTimeStat(
    val userName: String,
    val wins: Int,
    val kills: Int,
)