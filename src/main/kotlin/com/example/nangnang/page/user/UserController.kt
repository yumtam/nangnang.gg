package com.example.nangnang.page.user

import com.example.nangnang.db.TelemetryProcessor
import com.example.nangnang.db.logdamage.LogDamageRepository
import com.example.nangnang.db.logdown.LogDownRepository
import com.example.nangnang.db.loggamestat.LogGameStatRepository
import com.example.nangnang.db.loglifetimestat.LogLifeTimeStatRepository
import com.example.nangnang.db.logmatch.LogMatchRepository
import com.example.nangnang.db.loguser.LogUser
import com.example.nangnang.db.loguser.LogUserRepository
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.ui.set
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.math.max

@Controller
class UserController(
    val logMatchRepository: LogMatchRepository,
    val logGameStatRepository: LogGameStatRepository,
    val logDamageRepository: LogDamageRepository,
    val logDownRepository: LogDownRepository,
    val logUserRepository: LogUserRepository,
    val logLifeTimeStatRepository: LogLifeTimeStatRepository,
) {
    val telemetryProcessor = TelemetryProcessor(
        logMatchRepository,
        logGameStatRepository,
        logDamageRepository,
        logDownRepository,
        logLifeTimeStatRepository
    )

    @GetMapping("/user/{userName}")
    fun user(model: Model, @PathVariable userName: String): String {

        var logUser = logUserRepository.findByUserName(userName)
        if (logUser == null) {
            logUser = LogUser(userName, 0)
        }
        val currentUpdateTime = LocalDateTime.now().toEpochSecond(ZoneOffset.UTC)
        if (currentUpdateTime - logUser.lastUpdateTime > 3 * 60) {
            telemetryProcessor.updateMatchesByUserName(userName, 10)
            logUser.lastUpdateTime = currentUpdateTime
            logUserRepository.save(logUser)
        }

        val matchIdList =
            logGameStatRepository.findTop10ByUserNameOrderByMatchStartTimeDesc(userName).map { it.matchId }
        val matchList = matchIdList.map { logMatchRepository.findByMatchId(it) }
        val gameStatList = matchIdList.map { logGameStatRepository.findByMatchIdAndUserName(it, userName) }
        val teamMemberGameStatList =
            gameStatList.map { logGameStatRepository.findByMatchIdAndRosterId(it.matchId, it.rosterId) }
        val teamMaxStatList = teamMemberGameStatList.map {
            hashMapOf(
                "maxDamageDealt" to it.map { it.damageDealt }.maxOf { it }.let { max(it, 100.0) },
                "maxDamageTaken" to it.map { it.damageTaken }.maxOf { it }.let { max(it, 100.0) },
                "maxHealAmount" to it.map { it.healAmount }.maxOf { it }.let { max(it, 100.0) },
            )
        }
        val teamMemberDamageLogList = teamMemberGameStatList.map {
            logDamageRepository.findByMatchIdAndAttackerIn(it[0].matchId, it.map { it.userName })
        }

        model["userName"] = userName
        model["matchList"] = matchList
        model["gameStatList"] = gameStatList
        model["teamMemberGameStatList"] = teamMemberGameStatList
        model["teamMaxStatList"] = teamMaxStatList
        model["teamMemberDamageLogList"] = teamMemberDamageLogList
        model["lastUpdateTime"] = timeToString(logUser.lastUpdateTime)
        model["matchUpdateTimeList"] = matchList.map { timeToString(it!!.startTime + 9*3600) }
        return "pubg_data"
    }

    fun timeToString(epochSecond: Long): String {
        val now = LocalDateTime.now().toEpochSecond(ZoneOffset.UTC)
        return when (
            val t = now - epochSecond) {
            in 0..59 -> "방금 전"
            in 60..3559 -> "${t / 60}분 전"
            in 3600..86399 -> "${t / 3600}시간 전"
            else -> LocalDateTime.ofEpochSecond(epochSecond, 0, ZoneOffset.UTC).toLocalDate().toString()
        }
    }
}
