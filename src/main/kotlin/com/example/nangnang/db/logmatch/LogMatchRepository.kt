package com.example.nangnang.db.logmatch

import org.springframework.data.mongodb.repository.MongoRepository


interface LogMatchRepository : MongoRepository<LogMatch, String> {
    fun findByMatchId(matchId : String): LogMatch?
}