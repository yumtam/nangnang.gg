package com.example.nangnang.db.logmatch


import org.springframework.data.mongodb.core.mapping.Document


@Document("logmatch")
data class LogMatch(
    val matchId: String,
    val startTime: Long,
    val platform: String,
    val mapName: String,
    val gameMode: String,
)