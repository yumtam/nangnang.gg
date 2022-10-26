package com.example.nangnang.db.loglifetimestat

import org.springframework.data.mongodb.repository.MongoRepository

interface LogLifeTimeStatRepository : MongoRepository<LogLifeTimeStat, String> {
    fun findByUserName(userName: String): LogLifeTimeStat
}