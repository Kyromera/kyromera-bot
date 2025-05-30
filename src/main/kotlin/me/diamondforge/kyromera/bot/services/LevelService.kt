package me.diamondforge.kyromera.bot.services

import io.github.freya022.botcommands.api.core.BContext
import io.github.freya022.botcommands.api.core.annotations.BEventListener
import io.github.freya022.botcommands.api.core.service.annotations.BService
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import me.diamondforge.kyromera.bot.KyromeraScope
import me.diamondforge.kyromera.bot.enums.XpRewardType
import me.diamondforge.kyromera.bot.models.database.LevelingUsers
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import okhttp3.Dispatcher
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import kotlin.math.pow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

typealias XpPoints = Int
typealias Level = Int


private val logger = KotlinLogging.logger {}

@Serializable
data class CachedXp(
    val guildId: Long,
    val userId: Long,
    val xp: Int,
    val lastUpdated: Long = System.currentTimeMillis()
)

@BService
class LevelService(private val redisClient: RedisClientProvider, private val databaseClient: DatabaseSource, private val context: BContext) {
    private val cacheFlushInterval = 1.minutes

    fun getLevelUpMessage(guildId: Long, userId: Long, xp: Int): String {
        val level = levelAtXp(xp)

        return "Congratulations <@!$userId>! You have reached level $level."
    }

    init {
        KyromeraScope.launch(Dispatchers.IO) {
            startCacheFlushWorker()
        }
    }

    private suspend fun startCacheFlushWorker() {
        logger.info { "Starting XP cache flush worker with interval: $cacheFlushInterval" }
        while (true) {
            try {
                flushCacheToDatabase()
                delay(cacheFlushInterval)
            } catch (e: Exception) {
                logger.error(e) { "Error in XP cache flush worker" }
                delay(10.seconds)
            }
        }
    }

    private suspend fun flushCacheToDatabase() {
        val cacheKeys = redisClient.getKeysByPattern("xp:cache:*")

        if (cacheKeys.isEmpty()) {
            return
        }

        logger.info { "Flushing ${cacheKeys.size} XP cache entries to database" }

        cacheKeys.forEach { key ->
            try {
                redisClient.get(key) ?: return@forEach
                val cachedXp = redisClient.getTyped(key, CachedXp.serializer()) ?: return@forEach

                transaction {
                    val existingUser = LevelingUsers.selectAll().where(
                        LevelingUsers.guildId eq cachedXp.guildId and
                        (LevelingUsers.userId eq cachedXp.userId)
                    ).singleOrNull()

                    if (existingUser != null) {
                        val oldLevel = existingUser[LevelingUsers.level]
                        val existingXp = existingUser[LevelingUsers.xp]
                        val updatedXp = existingXp + cachedXp.xp
                        val updatedLevel = levelAtXp(updatedXp)

                        if (updatedLevel > oldLevel) {
                            val levelUpMessage = getLevelUpMessage(cachedXp.guildId, cachedXp.userId, updatedXp)
                            logger.info { "User ${cachedXp.userId} in guild ${cachedXp.guildId} leveled up from $oldLevel to $updatedLevel. Message: $levelUpMessage" }


                            KyromeraScope.launch {
                                val lastChannelId = getLastMessageChannelInGuild(cachedXp.guildId)
                                if (lastChannelId != null) {
                                    try {
                                        val jda = context.jda
                                        val channel = jda.getChannelById(MessageChannel::class.java, lastChannelId)
                                        channel?.sendMessage(levelUpMessage)?.queue()
                                        logger.debug { "Sent level-up message to channel $lastChannelId in guild ${cachedXp.guildId}" }
                                    } catch (e: Exception) {
                                        logger.error(e) { "Failed to send level-up message to channel $lastChannelId in guild ${cachedXp.guildId}" }
                                    }
                                } else {
                                    logger.warn { "No last message channel found for guild ${cachedXp.guildId}, could not send level-up message" }
                                }
                            }
                        }

                        LevelingUsers.update({ 
                            LevelingUsers.guildId eq cachedXp.guildId and
                            (LevelingUsers.userId eq cachedXp.userId)
                        }) {
                            it[xp] = updatedXp
                            it[level] = updatedLevel
                        }
                    } else {
                        val newLevel = levelAtXp(cachedXp.xp)
                        LevelingUsers.insert {
                            it[guildId] = cachedXp.guildId
                            it[userId] = cachedXp.userId
                            it[xp] = cachedXp.xp
                            it[level] = newLevel
                        }
                    }
                }

                redisClient.delete(key)
            } catch (e: Exception) {
                logger.error(e) { "Error flushing XP cache entry: $key" }
            }
        }
    }

    suspend fun addXp(guildId: Long, userId: Long, type: XpRewardType): Int? {
        val cooldownKey = "xp:cooldown:$guildId:$userId:${type.name}"
        val onCooldown = redisClient.get(cooldownKey) != null

        if (onCooldown) {
            val remainingCooldown = redisClient.getExpiry(cooldownKey)
            logger.debug { "User $userId in guild $guildId is on cooldown for ${type.name} XP for ${getXpCooldown(type).inWholeSeconds} seconds. Remaining cooldown: $remainingCooldown seconds" }
            return null
        }

        val cooldown = getXpCooldown(type)
        redisClient.setWithExpiry(cooldownKey, "1", cooldown.inWholeSeconds)

        val baseXp = getBaseXp(type)

        val cacheKey = "xp:cache:$guildId:$userId"
        val currentCachedXp = redisClient.getTyped(cacheKey, CachedXp.serializer())

        val dbXp = transaction {
            try {
                val result = LevelingUsers.selectAll().where(
                    LevelingUsers.guildId eq guildId and
                    (LevelingUsers.userId eq userId)
                ).singleOrNull()

                if (result != null) {
                    try {
                        result[LevelingUsers.xp]
                    } catch (e: IllegalStateException) {
                        logger.warn(e) { "XP field not found in record for user $userId in guild $guildId, defaulting to 0" }
                        0
                    }
                } else {
                    0
                }
            } catch (e: Exception) {
                logger.error(e) { "Error retrieving XP for user $userId in guild $guildId, defaulting to 0" }
                0
            }
        }

        val newCachedXp = if (currentCachedXp != null) {
            currentCachedXp.copy(xp = currentCachedXp.xp + baseXp, lastUpdated = System.currentTimeMillis())
        } else {
            CachedXp(guildId, userId, baseXp, System.currentTimeMillis())
        }

        redisClient.setTyped(cacheKey, newCachedXp, CachedXp.serializer())

        return dbXp + newCachedXp.xp
    }


    suspend fun getXp(guildId: Long, userId: Long): XpPoints {
        val cacheKey = "xp:cache:$guildId:$userId"
        val cachedXp = redisClient.getTyped(cacheKey, CachedXp.serializer())

        val dbXp = transaction {
            try {
                val result = LevelingUsers.selectAll().where(
                    LevelingUsers.guildId eq guildId and
                    (LevelingUsers.userId eq userId)
                ).singleOrNull()

                if (result != null) {
                    try {
                        result[LevelingUsers.xp]
                    } catch (e: IllegalStateException) {
                        logger.warn(e) { "XP field not found in record for user $userId in guild $guildId, defaulting to 0" }
                        0
                    }
                } else {
                    0
                }
            } catch (e: Exception) {
                logger.error(e) { "Error retrieving XP for user $userId in guild $guildId, defaulting to 0" }
                0
            }
        }

        return if (cachedXp != null) {
            dbXp + cachedXp.xp
        } else {
            dbXp
        }
    }

    suspend fun getLevel(guildId: Long, userId: Long): Level {
        val totalXp = getXp(guildId, userId)
        return levelAtXp(totalXp)
    }
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

    suspend fun setLastMessageChannelInGuild(guildId: Long, channelId: Long) {
        val key = "guild:$guildId:lastMessageChannel"
        redisClient.set(key, channelId.toString())
        logger.debug { "Set last message channel for guild $guildId to $channelId" }
    }

    suspend fun getLastMessageChannelInGuild(guildId: Long): Long? {
        val key = "guild:$guildId:lastMessageChannel"
        return redisClient.get(key)?.toLongOrNull()
    }


    suspend fun handleMessageCreated(createEvent: MessageReceivedEvent) {
        if (createEvent.author.isBot) return
        if (createEvent.guild == null) return


        logger.debug { "Received message create event for guild ${createEvent.guild.name} and channel ${createEvent.channel.name}" }
        val guildId = createEvent.guild.idLong
        val userId = createEvent.author.idLong
        val channelId = createEvent.channel.idLong

        setLastMessageChannelInGuild(guildId, channelId)

        val newXp = addXp(guildId, userId, XpRewardType.Message)

        if (newXp == null) {
            logger.debug { "User $userId in guild $guildId is on cooldown for message XP" }
            return
        }
        val level = levelAtXp(newXp)
        logger.debug { "User $userId in guild $guildId has $newXp XP and is at level $level" }
    }

}
