package com.example.nangnang

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories


@SpringBootApplication
@EnableMongoRepositories
class NangnangApplication

fun main(args: Array<String>) {
    runApplication<NangnangApplication>(*args)
}

