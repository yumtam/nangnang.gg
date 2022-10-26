package com.example.nangnang.db.logdamage

import org.springframework.data.mongodb.repository.MongoRepository

interface LogDamageRepository : MongoRepository<LogDamage, String> {
    fun findByMatchIdAndAttackerIn(matchId: String, attacker: Collection<String>) : List<LogDamage>
}