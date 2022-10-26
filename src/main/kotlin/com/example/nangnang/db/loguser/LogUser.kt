package com.example.nangnang.db.loguser


import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document


@Document("loguser")
data class LogUser(
    @Id
    val userName: String,
    var lastUpdateTime: Long,
)