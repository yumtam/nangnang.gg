package com.example.nangnang.db.logdown

import org.springframework.data.mongodb.repository.MongoRepository

interface LogDownRepository : MongoRepository<LogDown, String> {
}