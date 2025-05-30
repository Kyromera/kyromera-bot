package me.diamondforge.kyromera.bot.services

import io.github.freya022.botcommands.api.core.service.annotations.BService
import kotlinx.serialization.builtins.serializer
import me.diamondforge.kyromera.bot.enums.XpRewardType
import kotlin.math.pow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@BService
class LevelService(private val redisClient: RedisClientProvider, private val databaseClient: DatabaseSource) {
    fun getBaseXp(type: XpRewardType): Int = when (type) {
        XpRewardType.Message -> (15..25).random()
        XpRewardType.Voice -> (2..6).random()
    }


    fun xpForLevel(level: Int): Int {
        if (level <= 0) return 0
        val alvl: Int = level - 1
        return (5 / 6.0 * (151 * alvl + 33 * alvl.toDouble().pow(2.0) + 2 * alvl.toDouble().pow(3.0)) + 100).toInt()
    }


    fun getLevelPercent(level: Int, xp: Int): Double {
        if (level <= 0) return 0.0

        val currentXp = xpForLevel(level)
        val nextXp = xpForLevel(level + 1)

        return if (nextXp == currentXp) {
            1.0
        } else {
            (xp - currentXp).toDouble() / (nextXp - currentXp)
        }
    }


    fun levelAtXp(totalXp: Int): Int {
        if (totalXp <= 0) return 0

        var level = 1
        while (true) {
            val requiredXp = xpForLevel(level)
            if (requiredXp > totalXp) {
                return level - 1
            }
            level++
        }
    }


    fun XpUntilNextLevel(level: Int, totalXp: Int): Int {
        if (level <= 0) return 0

        val currentXp = xpForLevel(level)
        val nextXp = xpForLevel(level + 1)

        return if (nextXp == currentXp) {
            0
        } else {
            nextXp - totalXp
        }
    }

    fun MinAndMaxXpForLevel(level: Int): Pair<Int, Int> {
        if (level <= 0) return Pair(0, 0)

        val currentXp = xpForLevel(level)
        val nextXp = xpForLevel(level + 1)

        return Pair(currentXp, nextXp - 1)
    }

    fun getXpCooldown(type: XpRewardType): Duration {
        return when (type) {
            XpRewardType.Message -> 60.seconds
            XpRewardType.Voice -> 60.seconds
        }
    }

    
}