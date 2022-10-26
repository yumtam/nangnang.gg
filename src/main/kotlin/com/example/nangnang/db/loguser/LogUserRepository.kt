package com.example.nangnang.db.loguser

import org.springframework.data.mongodb.repository.MongoRepository


interface LogUserRepository : MongoRepository<LogUser, String> {
    fun findByUserName(userName: String): LogUser?
}