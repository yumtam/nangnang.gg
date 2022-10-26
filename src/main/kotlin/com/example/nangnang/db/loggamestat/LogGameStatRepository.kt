package com.example.nangnang.db.loggamestat

import org.springframework.data.mongodb.repository.MongoRepository

interface LogGameStatRepository : MongoRepository<LogGameStat, String> {
    fun findByMatchIdAndUserName(matchId: String, userName: String) : LogGameStat
    fun findByMatchIdAndRosterId(matchId: String, rosterId: String) : List<LogGameStat>
    fun findTop10ByUserNameOrderByMatchStartTimeDesc(userName: String) : List<LogGameStat>
}