package com.example.nangnang.db

import com.example.nangnang.db.logdamage.LogDamage
import com.example.nangnang.db.logdamage.LogDamageRepository
import com.example.nangnang.db.logdown.LogDown
import com.example.nangnang.db.logdown.LogDownRepository
import com.example.nangnang.db.loggamestat.LogGameStat
import com.example.nangnang.db.loggamestat.LogGameStatRepository
import com.example.nangnang.db.loglifetimestat.LogLifeTimeStatRepository
import com.example.nangnang.db.logmatch.LogMatch
import com.example.nangnang.db.logmatch.LogMatchRepository
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.core.io.ClassPathResource
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory
import org.springframework.web.client.RestTemplate
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneOffset

class TelemetryProcessor (
    private val logMatchRepository: LogMatchRepository,
    private val logGameStatRepository: LogGameStatRepository,
    private val logDamageRepository: LogDamageRepository,
    private val logDownRepository: LogDownRepository,
    private val logLifeTimeStatRepository: LogLifeTimeStatRepository,
    ) {

    private val key = ClassPathResource("token").file.readText()

    private val restTemplate = RestTemplate(
        HttpComponentsClientHttpRequestFactory()
            .also { it.setConnectTimeout(5000) }
            .also { it.setReadTimeout(5000) }
    )

    private val httpEntity = HttpEntity<Map<String, Any>>(
        HttpHeaders()
            .also { it.set("Authorization", "Bearer $key") }
            .also { it.set("Accept", "application/vnd.api+json") }
    )


    fun updateMatchesByUserName(userName: String, count: Int) {
        var processedMatchCount = 0
        val jsonNode = apiCall("https://api.pubg.com/shards/kakao/players?filter[playerNames]=$userName")
        val matchList = jsonNode["data"][0]["relationships"]["matches"]["data"]
        matchList.forEach {
            if (processedMatchCount >= count) { return@forEach }
            val matchId = it["id"].asText()
            val matchDataJsonNode = apiCall("https://api.pubg.com/shards/kakao/matches/$matchId")
            val matchType = matchDataJsonNode["data"]["attributes"]["matchType"].asText()
            if (matchType != "official") { return@forEach }
            updateMatch(matchDataJsonNode)
            processedMatchCount += 1
        }
    }

    private fun updateMatch(matchDataJsonNode: JsonNode) {
        val matchId = matchDataJsonNode["data"]["id"].asText()
        val matchIsCached = logMatchRepository.findByMatchId(matchId) != null

        if (matchIsCached) { return }

        val platform = matchDataJsonNode["data"]["attributes"]["shardId"].asText()
        val mapName = matchDataJsonNode["data"]["attributes"]["mapName"].asText().let { translateMapName.getOrDefault(it, it) }
        val gameMode = matchDataJsonNode["data"]["attributes"]["gameMode"].asText()


        val telemetryURL = matchDataJsonNode["included"]
            .first { it["type"].asText() == "asset" }
            .let { it["attributes"]["URL"].asText() }
        val matchTelemetryJsonNode = apiCall(telemetryURL)

        val matchStartTime = matchTelemetryJsonNode
            .first { it["_T"].asText() == "LogMatchStart" }
            .let { it["_D"].asEpochSecond()  }

        logMatchRepository.save(LogMatch(matchId, matchStartTime, platform, mapName, gameMode))


        val logDamageList = mutableListOf<LogDamage>()
        val damageDealtToBotsMap = HashMap<String, Double>().withDefault { 0.0 }
        val damageTakenMap = HashMap<String, Double>().withDefault { 0.0 }
        val damageTakenByBluezoneMap = HashMap<String, Double>().withDefault { 0.0 }

        matchTelemetryJsonNode.forEach {
            if (it["_T"].asText() == "LogPlayerTakeDamage") {
                if (it["damage"].asDouble() < 0.00001) { return@forEach }

                val time = it["_D"].asEpochSecond() - matchStartTime
                val phase = it["common"]["isGame"].asDouble()
                val attacker = it.parseAttacker()
                val victim = it["victim"]["name"].asText()
                val reason = it["damageCauserName"].asText().let { translateReasonName.getOrDefault(it, it) }
                val damage = it["damage"].asDouble()
                val attackerHealth = it.parseAttackerHealth()
                val victimHealth = it["victim"]["health"].asDouble() - damage
                val distance = 0
                val isVictimBot = it["victim"]["accountId"].asText().startsWith("ai")

                if (isVictimBot) { damageDealtToBotsMap[attacker] = damageDealtToBotsMap.getValue(attacker) + damage }
                damageTakenMap[victim] = damageTakenMap.getValue(victim) + damage
                if (reason == "Bluezone") {
                    damageTakenByBluezoneMap[victim] = damageTakenByBluezoneMap.getValue(victim) + damage
                    return@forEach
                }

                logDamageList.add(LogDamage(matchId, time, phase, attacker, victim, reason, damage, attackerHealth,
                    victimHealth, distance, isVictimBot))
            }
        }
        logDamageRepository.saveAll(logDamageList)


        val logDownList = mutableListOf<LogDown>()
        val downsSelfMap = HashMap<String, Int>().withDefault { 0 }
        val downsEnemyMap = HashMap<String, Int>().withDefault { 0 }

        matchTelemetryJsonNode.forEach {
            if (it["_T"].asText() == "LogPlayerMakeGroggy") {
                val time = it["_D"].asEpochSecond() - matchStartTime
                val attacker =  it.parseAttacker()
                val victim = it["victim"]["name"].asText()
                val reason = it["damageCauserName"].asText().let { translateReasonName.getOrDefault(it, it) }
                val attackerHealth = it.parseAttackerHealth()
                val victimWeapon = it["victimWeapon"].asText()
                val distance = it["distance"].asInt()
                val isVictimBot = it["victim"]["accountId"].asText().startsWith("ai")

                downsSelfMap[victim] = downsSelfMap.getValue(victim) + 1
                downsEnemyMap[attacker] = downsSelfMap.getValue(attacker) + 1

                logDownList.add(LogDown(matchId, time, attacker, victim, reason, attackerHealth, victimWeapon, distance, isVictimBot))
            }
        }
        logDownRepository.saveAll(logDownList)

        val revivesMap = HashMap<String, Int>().withDefault { 0 }
        val healAmountMap = HashMap<String, Double>().withDefault { 0.0 }
        val useBandageMap = HashMap<String, Int>().withDefault { 0 }
        val useFirstAidMap = HashMap<String, Int>().withDefault { 0 }
        val useMedkitMap = HashMap<String, Int>().withDefault { 0 }
        val useEnergyDrinkMap = HashMap<String, Int>().withDefault { 0 }
        val usePainKillerMap = HashMap<String, Int>().withDefault { 0 }
        val useAdrenalineSyringeMap = HashMap<String, Int>().withDefault { 0 }

        matchTelemetryJsonNode.forEach {
            when (it["_T"].asText()) {
                "LogPlayerRevive" -> {
                    val userName = it["reviver"]["name"].asText()
                    revivesMap[userName] = revivesMap.getValue(userName) + 1
                }
                "LogHeal" -> {
                    val userName = it["character"]["name"].asText()
                    val healAmount = it["healAmount"].asDouble()
                    healAmountMap[userName] = healAmountMap.getValue(userName) + healAmount
                }
                "LogItemUse" -> {
                    val userName = it["character"]["name"].asText()
                    when (it["item"]["itemId"].asText()) {
                        "Item_Heal_Bandage_C" -> { useBandageMap[userName] = useBandageMap.getValue(userName) + 1 }
                        "Item_Heal_FirstAid_C" -> { useFirstAidMap[userName] = useFirstAidMap.getValue(userName) + 1 }
                        "Item_Heal_Medkit_C" -> { useMedkitMap[userName] = useMedkitMap.getValue(userName) + 1 }
                        "Item_Boost_EnergyDrink_C" -> { useEnergyDrinkMap[userName] = useEnergyDrinkMap.getValue(userName) + 1 }
                        "Item_Boost_PainKiller_C" -> { usePainKillerMap[userName] = usePainKillerMap.getValue(userName) + 1 }
                        "Item_Boost_AdrenalineSyringe_C" -> { useAdrenalineSyringeMap[userName] = useAdrenalineSyringeMap.getValue(userName) + 1 }
                    }
                }
            }
        }

        val logGameStatList = mutableListOf<LogGameStat>()
        val maxWinPlace = matchDataJsonNode["data"]["relationships"]["rosters"]["data"].size()

        val participantIdToRosterIdMap = HashMap<String, String>()
        val winPlaceMap = HashMap<String, Int>()
        matchDataJsonNode["included"].forEach {
            when (it["type"].asText()) {
                "roster" -> {
                    val rosterId = it["id"].asText()
                    it["relationships"]["participants"]["data"].forEach {
                        val participantId = it["id"].asText()
                        participantIdToRosterIdMap[participantId] = rosterId
                    }
                }
                "participant" -> {
                    val participantId = it["id"].asText()
                    val winPlace = it["attributes"]["stats"]["winPlace"].asInt()
                    winPlaceMap[participantId] = winPlace
                }
            }
        }

        matchDataJsonNode["included"].forEach {
            if (it["type"].asText() != "participant") { return@forEach }

            val participantId = it["id"].asText()
            val rosterId = participantIdToRosterIdMap.getValue(participantId)
            val stats = it["attributes"]["stats"]
            val userName = stats["name"].asText()
            val winPlace = participantIdToRosterIdMap
                .filter { it.value == rosterId }
                .map { winPlaceMap.getValue(it.key) }
                .minOf { it }
            val timeSurvived = stats["timeSurvived"].asInt()
            val damageDealt = stats["damageDealt"].asDouble()
            val damageDealtToBots = damageDealtToBotsMap.getValue(userName)
            val healAmount = healAmountMap.getValue(userName)
            val kills = stats["kills"].asInt()
            val assists = stats["assists"].asInt()
            val downsSelf = downsSelfMap.getValue(userName)
            val downsEnemy = downsEnemyMap.getValue(userName)
            val damageTaken = damageTakenMap.getValue(userName)
            val damageTakenByBluezone = damageTakenByBluezoneMap.getValue(userName)
            val revives = revivesMap.getValue(userName)
            val useBandage = useBandageMap.getValue(userName)
            val useFirstAid = useFirstAidMap.getValue(userName)
            val useMedkit = useMedkitMap.getValue(userName)
            val useEnergyDrink = useEnergyDrinkMap.getValue(userName)
            val usePainKiller = usePainKillerMap.getValue(userName)
            val useAdrenalineSyringe = useAdrenalineSyringeMap.getValue(userName)

            logGameStatList.add(LogGameStat(matchId, matchStartTime, userName, winPlace, maxWinPlace, rosterId, timeSurvived, damageDealt,
                damageDealtToBots, healAmount, kills, assists, downsSelf, downsEnemy, damageTaken, damageTakenByBluezone, revives, useBandage,
                useFirstAid, useMedkit, useEnergyDrink, usePainKiller, useAdrenalineSyringe))
        }

        logGameStatRepository.saveAll(logGameStatList)
    }

    fun updateLifeTimeStatByUserName(userName: String) {
        val jsonNode = apiCall("https://api.pubg.com/shards/kakao/players?filter[playerNames]=$userName")
        val accountId = "account.97d84ff6bf114cef960b7d9b5bcd204e"
        val url = "https://api.pubg.com/shards/kakao/players/${accountId}/seasons/lifetime"
        var kills = 0
        var wins = 0
        apiCall(url)
            .let { it["data"]["attributes"]["gameModeStats"] }
            .forEach {
                kills += it["kills"].asInt()
                wins += it["wins"].asInt()
            }


    }

    private fun JsonNode.parseAttacker() : String {
        return if (this["attacker"].asText() == "null") {
            ""
        } else {
            this["attacker"]["name"].asText()
        }
    }

    private fun JsonNode.parseAttackerHealth() : Double {
        return if (this["attacker"].asText() == "null") {
            0.0
        } else {
            this["attacker"]["health"].asDouble()
        }
    }

    private fun JsonNode.asEpochSecond() : Long {
        return this.asText().split('.')[0]
                .let { LocalDateTime.parse(it).toEpochSecond(ZoneOffset.UTC) }
    }

    private fun apiCall(url: String): JsonNode {
        val resultMap = restTemplate.exchange(url, HttpMethod.GET, httpEntity, String::class.java)
        return ObjectMapper().readTree(resultMap.body)
    }

    val translateMapName = hashMapOf(
        "Baltic_Main" to "에란겔",
        "Chimera_Main" to "파라모",
        "Desert_Main" to "미라마",
        "DihorOtok_Main" to "비켄디",
        "Erangel_Main" to "에란겔",
        "Heaven_Main" to "헤이븐",
        "Kiki_Main" to "데스턴",
        "Range_Main" to "Camp Jackal",
        "Savage_Main" to "사녹",
        "Summerland_Main" to "카라킨",
        "Tiger_Main" to "태이고",
    )

    val translateReasonName = hashMapOf(
            "AIPawn_Base_Female_C" to "AI Player",
            "AIPawn_Base_Male_C" to "AI Player",
            "AirBoat_V2_C" to "Airboat",
            "AquaRail_A_01_C" to "Aquarail",
            "AquaRail_A_02_C" to "Aquarail",
            "AquaRail_A_03_C" to "Aquarail",
            "BP_ATV_C" to "Quad",
            "BP_BRDM_C" to "BRDM-2",
            "BP_Bicycle_C" to "Mountain Bike",
            "BP_CoupeRB_C" to "Coupe RB",
            "BP_DO_Circle_Train_Merged_C" to "Train",
            "BP_DO_Line_Train_Dino_Merged_C" to "Train",
            "BP_DO_Line_Train_Merged_C" to "Train",
            "BP_Dirtbike_C" to "Dirt Bike",
            "BP_DronePackage_Projectile_C" to "Drone",
            "BP_Eragel_CargoShip01_C" to "Ferry Damage",
            "BP_FakeLootProj_AmmoBox_C" to "Loot Truck",
            "BP_FakeLootProj_MilitaryCrate_C" to "Loot Truck",
            "BP_FireEffectController_C" to "Molotov Fire",
            "BP_FireEffectController_JerryCan_C" to "Jerrycan Fire",
            "BP_Food_Truck_C" to "Food Truck",
            "BP_Helicopter_C" to "Pillar Scout Helicopter",
            "BP_IncendiaryDebuff_C" to "Burn",
            "BP_JerryCanFireDebuff_C" to "Burn",
            "BP_JerryCan_FuelPuddle_C" to "Burn",
            "BP_KillTruck_C" to "Kill Truck",
            "BP_LootTruck_C" to "Loot Truck",
            "BP_M_Rony_A_01_C" to "Rony",
            "BP_M_Rony_A_02_C" to "Rony",
            "BP_M_Rony_A_03_C" to "Rony",
            "BP_Mirado_A_02_C" to "Mirado",
            "BP_Mirado_A_03_Esports_C" to "Mirado",
            "BP_Mirado_Open_03_C" to "Mirado (open top)",
            "BP_Mirado_Open_04_C" to "Mirado (open top)",
            "BP_Mirado_Open_05_C" to "Mirado (open top)",
            "BP_MolotovFireDebuff_C" to "Molotov Fire Damage",
            "BP_Motorbike_04_C" to "Motorcycle",
            "BP_Motorbike_04_Desert_C" to "Motorcycle",
            "BP_Motorbike_04_SideCar_C" to "Motorcycle (w/ Sidecar)",
            "BP_Motorbike_04_SideCar_Desert_C" to "Motorcycle (w/ Sidecar)",
            "BP_Motorbike_Solitario_C" to "Motorcycle",
            "BP_Motorglider_C" to "Motor Glider",
            "BP_Motorglider_Green_C" to "Motor Glider",
            "BP_Niva_01_C" to "Zima",
            "BP_Niva_02_C" to "Zima",
            "BP_Niva_03_C" to "Zima",
            "BP_Niva_04_C" to "Zima",
            "BP_Niva_05_C" to "Zima",
            "BP_Niva_06_C" to "Zima",
            "BP_Niva_07_C" to "Zima",
            "BP_PickupTruck_A_01_C" to "Pickup Truck (closed top)",
            "BP_PickupTruck_A_02_C" to "Pickup Truck (closed top)",
            "BP_PickupTruck_A_03_C" to "Pickup Truck (closed top)",
            "BP_PickupTruck_A_04_C" to "Pickup Truck (closed top)",
            "BP_PickupTruck_A_05_C" to "Pickup Truck (closed top)",
            "BP_PickupTruck_A_esports_C" to "Pickup Truck (closed top)",
            "BP_PickupTruck_B_01_C" to "Pickup Truck (open top)",
            "BP_PickupTruck_B_02_C" to "Pickup Truck (open top)",
            "BP_PickupTruck_B_03_C" to "Pickup Truck (open top)",
            "BP_PickupTruck_B_04_C" to "Pickup Truck (open top)",
            "BP_PickupTruck_B_05_C" to "Pickup Truck (open top)",
            "BP_Pillar_Car_C" to "Pillar Security Car",
            "BP_PonyCoupe_C" to "Pony Coupe",
            "BP_Porter_C" to "Porter",
            "BP_Scooter_01_A_C" to "Scooter",
            "BP_Scooter_02_A_C" to "Scooter",
            "BP_Scooter_03_A_C" to "Scooter",
            "BP_Scooter_04_A_C" to "Scooter",
            "BP_Snowbike_01_C" to "Snowbike",
            "BP_Snowbike_02_C" to "Snowbike",
            "BP_Snowmobile_01_C" to "Snowmobile",
            "BP_Snowmobile_02_C" to "Snowmobile",
            "BP_Snowmobile_03_C" to "Snowmobile",
            "BP_Spiketrap_C" to "Spike Trap",
            "BP_TslGasPump_C" to "Gas Pump",
            "BP_TukTukTuk_A_01_C" to "Tukshai",
            "BP_TukTukTuk_A_02_C" to "Tukshai",
            "BP_TukTukTuk_A_03_C" to "Tukshai",
            "BP_Van_A_01_C" to "Van",
            "BP_Van_A_02_C" to "Van",
            "BP_Van_A_03_C" to "Van",
            "BattleRoyaleModeController_Chimera_C" to "Bluezone",
            "BattleRoyaleModeController_Def_C" to "Bluezone",
            "BattleRoyaleModeController_Desert_C" to "Bluezone",
            "BattleRoyaleModeController_DihorOtok_C" to "Bluezone",
            "BattleRoyaleModeController_Heaven_C" to "Bluezone",
            "BattleRoyaleModeController_Kiki_C" to "Bluezone",
            "BattleRoyaleModeController_Savage_C" to "Bluezone",
            "BattleRoyaleModeController_Summerland_C" to "Bluezone",
            "BattleRoyaleModeController_Tiger_C" to "Bluezone",
            "BlackZoneController_Def_C" to "Blackzone",
            "Bluezonebomb_EffectActor_C" to "Bluezone Grenade",
            "Boat_PG117_C" to "PG-117",
            "Buff_DecreaseBreathInApnea_C" to "Drowning",
            "Buggy_A_01_C" to "Buggy",
            "Buggy_A_02_C" to "Buggy",
            "Buggy_A_03_C" to "Buggy",
            "Buggy_A_04_C" to "Buggy",
            "Buggy_A_05_C" to "Buggy",
            "Buggy_A_06_C" to "Buggy",
            "Carepackage_Container_C" to "Care Package",
            "Dacia_A_01_v2_C" to "Dacia",
            "Dacia_A_01_v2_snow_C" to "Dacia",
            "Dacia_A_02_v2_C" to "Dacia",
            "Dacia_A_03_v2_C" to "Dacia",
            "Dacia_A_03_v2_Esports_C" to "Dacia",
            "Dacia_A_04_v2_C" to "Dacia",
            "DroppedItemGroup" to "Object Fragments",
            "EmergencyAircraft_Tiger_C" to "Emergency Aircraft",
            "Jerrycan" to "Jerrycan",
            "JerrycanFire" to "Jerrycan Fire",
            "Lava" to "Lava",
            "Mortar_Projectile_C" to "Mortar Projectile",
            "None" to "None",
            "PG117_A_01_C" to "PG-117",
            "PanzerFaust100M_Projectile_C" to "Panzerfaust Projectile",
            "PlayerFemale_A_C" to "Player",
            "PlayerMale_A_C" to "Player",
            "ProjC4_C" to "C4",
            "ProjGrenade_C" to "Frag Grenade",
            "ProjIncendiary_C" to "Incendiary Projectile",
            "ProjMolotov_C" to "Molotov Cocktail",
            "ProjMolotov_DamageField_Direct_C" to "Molotov Cocktail Fire Field",
            "ProjStickyGrenade_C" to "Sticky Bomb",
            "RacingDestructiblePropaneTankActor_C" to "Propane Tank",
            "RacingModeContorller_Desert_C" to "Bluezone",
            "RedZoneBomb_C" to "Redzone",
            "RedZoneBombingField" to "Redzone",
            "RedZoneBombingField_Def_C" to "Redzone",
            "TslDestructibleSurfaceManager" to "Destructible Surface",
            "TslPainCausingVolume" to "Lava",
            "Uaz_A_01_C" to "UAZ (open top)",
            "Uaz_Armored_C" to "UAZ (armored)",
            "Uaz_B_01_C" to "UAZ (soft top)",
            "Uaz_B_01_esports_C" to "UAZ (soft top)",
            "Uaz_C_01_C" to "UAZ (hard top)",
            "UltAIPawn_Base_Female_C" to "Player",
            "UltAIPawn_Base_Male_C" to "Player",
            "WeapACE32_C" to "ACE32",
            "WeapAK47_C" to "AKM",
            "WeapAUG_C" to "AUG A3",
            "WeapAWM_C" to "AWM",
            "WeapBerreta686_C" to "S686",
            "WeapBerylM762_C" to "Beryl",
            "WeapBizonPP19_C" to "Bizon",
            "WeapCowbarProjectile_C" to "Crowbar Projectile",
            "WeapCowbar_C" to "Crowbar",
            "WeapCrossbow_1_C" to "Crossbow",
            "WeapDP12_C" to "DBS",
            "WeapDP28_C" to "DP-28",
            "WeapDesertEagle_C" to "Deagle",
            "WeapDuncansHK416_C" to "M416",
            "WeapFNFal_C" to "SLR",
            "WeapG18_C" to "P18C",
            "WeapG36C_C" to "G36C",
            "WeapGroza_C" to "Groza",
            "WeapHK416_C" to "M416",
            "WeapJuliesKar98k_C" to "Kar98k",
            "WeapK2_C" to "K2",
            "WeapKar98k_C" to "Kar98k",
            "WeapL6_C" to "Lynx AMR",
            "WeapLunchmeatsAK47_C" to "AKM",
            "WeapM16A4_C" to "M16A4",
            "WeapM1911_C" to "P1911",
            "WeapM249_C" to "M249",
            "WeapM24_C" to "M24",
            "WeapM9_C" to "P92",
            "WeapMG3_C" to "MG3",
            "WeapMP5K_C" to "MP5K",
            "WeapMP9_C" to "MP9",
            "WeapMacheteProjectile_C" to "Machete Projectile",
            "WeapMachete_C" to "Machete",
            "WeapMadsQBU88_C" to "QBU88",
            "WeapMini14_C" to "Mini 14",
            "WeapMk12_C" to "Mk12",
            "WeapMk14_C" to "Mk14 EBR",
            "WeapMk47Mutant_C" to "Mk47 Mutant",
            "WeapMosinNagant_C" to "Mosin-Nagant",
            "WeapNagantM1895_C" to "R1895",
            "WeapOriginS12_C" to "O12",
            "WeapP90_C" to "P90",
            "WeapPanProjectile_C" to "Pan Projectile",
            "WeapPan_C" to "Pan",
            "WeapPanzerFaust100M1_C" to "Panzerfaust",
            "WeapQBU88_C" to "QBU88",
            "WeapQBZ95_C" to "QBZ95",
            "WeapRhino_C" to "R45",
            "WeapSCAR-L_C" to "SCAR-L",
            "WeapSKS_C" to "SKS",
            "WeapSaiga12_C" to "S12K",
            "WeapSawnoff_C" to "Sawed-off",
            "WeapSickleProjectile_C" to "Sickle Projectile",
            "WeapSickle_C" to "Sickle",
            "WeapThompson_C" to "Tommy Gun",
            "WeapTurret_KillTruck_Main_C" to "Kill Truck Turret",
            "WeapUMP_C" to "UMP9",
            "WeapUZI_C" to "Micro Uzi",
            "WeapVSS_C" to "VSS",
            "WeapVector_C" to "Vector",
            "WeapWin94_C" to "Win94",
            "WeapWinchester_C" to "S1897",
            "Weapvz61Skorpion_C" to "Skorpion"
    )
}