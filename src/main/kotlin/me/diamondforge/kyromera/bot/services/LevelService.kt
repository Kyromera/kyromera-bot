package me.diamondforge.kyromera.bot.services

import io.github.freya022.botcommands.api.core.BContext
import io.github.freya022.botcommands.api.core.service.annotations.BService
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import me.diamondforge.kyromera.bot.KyromeraScope
import me.diamondforge.kyromera.bot.enums.XpRewardType
import me.diamondforge.kyromera.bot.models.database.LevelingSettings
import me.diamondforge.kyromera.bot.models.database.LevelingUsers
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import kotlin.math.pow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
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

@Serializable
data class RewardRole(
    val guildId: Long,
    val roleId: Long,
    val level: Int
)

@BService
class LevelService(
    private val redisClient: RedisClientProvider,
    private val databaseClient: DatabaseSource,
    private val context: BContext
) {
    private val cacheFlushInterval = 1.minutes
    private val voiceChannelCheckInterval = 1.minutes

    suspend fun getLevelUpMessage(guildId: Long): String {
        val cacheKey = "levelup:message:$guildId"
        val cachedMessage = redisClient.get(cacheKey)

        if (cachedMessage != null) {
            logger.debug { "Found cached level-up message for guild $guildId" }
            return cachedMessage
        }

        logger.debug { "No cached level-up message found for guild $guildId, querying database" }
        val dbMessage = newSuspendedTransaction {
            LevelingSettings
                .selectAll().where { LevelingSettings.guildId eq guildId }
                .limit(1)
                .map { it[LevelingSettings.levelupMessage] }
                .firstOrNull()
                ?: "Congratulations {user}! You just advanced to level {level}!"
        }

        redisClient.setWithExpiry(cacheKey, dbMessage, 4.hours.inWholeSeconds)
        logger.debug { "Cached level-up message for guild $guildId" }

        return dbMessage
    }


    init {
        KyromeraScope.launch(Dispatchers.IO) {
            startCacheFlushWorker()
        }

        KyromeraScope.launch(Dispatchers.IO) {
            startVoiceChannelMonitoringWorker()
        }
    }

    private suspend fun startCacheFlushWorker() {
        logger.info { "Starting XP cache flush worker with interval: $cacheFlushInterval" }

        // Outer loop to ensure the worker restarts if it fails
        while (true) {
            try {
                // Inner loop for the actual work
                while (true) {
                    try {
                        flushCacheToDatabase()
                        delay(cacheFlushInterval)
                    } catch (e: Exception) {
                        logger.error(e) { "Error in XP cache flush worker, will retry after delay" }
                        delay(10.seconds)
                    }
                }
            } catch (e: Exception) {
                logger.error(e) { "Critical error in XP cache flush worker, restarting worker" }
                delay(30.seconds) // Longer delay before restarting the worker
            }
        }
    }

    private suspend fun startVoiceChannelMonitoringWorker() {
        logger.info { "Starting voice channel monitoring worker with interval: $voiceChannelCheckInterval" }
        val jda = context.jda
        jda.awaitReady()
        logger.info { "JDA instance is ready, starting voice channel monitoring worker" }
        while (true) {
            try {
                while (true) {
                    try {
                        checkVoiceChannelsAndAwardXp()
                        delay(voiceChannelCheckInterval)
                    } catch (e: Exception) {
                        logger.error(e) { "Error in voice channel monitoring worker, will retry after delay" }
                        delay(10.seconds)
                    }
                }
            } catch (e: Exception) {
                logger.error(e) { "Critical error in voice channel monitoring worker, restarting worker" }
                delay(30.seconds)
            }
        }
    }

    private suspend fun checkVoiceChannelsAndAwardXp() {
        logger.debug { "Checking voice channels for XP awards" }
        var totalAvardedMemberCount = 0

        try {
            val jda = context.jda
            val guilds = jda.guilds
            var awardedMembers = 0

            for (guild in guilds) {
                try {
                    val voiceChannels = guild.voiceChannels

                    for (voiceChannel in voiceChannels) {
                        logger.debug { "Processing voice channel ${voiceChannel.name} (${voiceChannel.id}) in guild ${guild.name}. Member count: ${voiceChannel.members.size}" }
                        try {
                            val members = voiceChannel.members

                            if (members.isEmpty()) {
                                continue
                            }

                            val unmutedMembers = members.filter { it.voiceState?.isMuted == false && !it.user.isBot }
                            if (unmutedMembers.size < 2) {
                                logger.debug { "Skipping voice channel ${voiceChannel.name} (${voiceChannel.id}) in guild ${guild.name} due to insufficient unmuted members" }
                                continue
                            }

                            logger.trace { "Found ${members.size} members in voice channel ${voiceChannel.name} (${voiceChannel.id}) in guild ${guild.name}" }

                            for (member in members) {
                                try {
                                    if (member.user.isBot) {
                                        continue
                                    }

                                    val voiceState = member.voiceState
                                    if (voiceState?.isDeafened == true || voiceState?.isSelfDeafened == true) {
                                        logger.debug { "Skipping deafened user ${member.id} in guild ${guild.id}" }
                                        continue
                                    }

                                    if (guild.afkChannel != null && voiceChannel.id == guild.afkChannel?.id) {
                                        logger.debug { "Skipping user ${member.id} in AFK channel in guild ${guild.id}" }
                                        continue
                                    }

                                    val guildId = guild.idLong
                                    val userId = member.idLong

                                    val newXp = addXp(guildId, userId, XpRewardType.Voice)

                                    if (newXp == null) {
                                        logger.debug { "User $userId in guild $guildId is on cooldown for voice XP" }
                                    } else {
                                        val level = levelAtXp(newXp)
                                        awardedMembers++
                                        totalAvardedMemberCount++
                                        logger.debug { "Awarded voice XP to user $userId in guild $guildId. Total XP: $newXp, Level: $level" }
                                    }
                                } catch (e: Exception) {
                                    logger.error(e) { "Error processing member ${member.id} in voice channel ${voiceChannel.id}" }
                                }
                            }
                        } catch (e: Exception) {
                            logger.error(e) { "Error processing voice channel ${voiceChannel.id} in guild ${guild.id}" }
                        }
                    }
                } catch (e: Exception) {
                    logger.error(e) { "Error processing guild ${guild.id}" }
                }
                logger.info { "Processed guild ${guild.name} (${guild.id}), awarded XP to $awardedMembers members" }
            }
            logger.info { "Total awarded members across all guilds: $totalAvardedMemberCount" }
        } catch (e: Exception) {
            logger.error(e) { "Error checking voice channels" }
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
                            // Get the message template first
                            val messageTemplate = LevelingSettings.selectAll()
                                .where { LevelingSettings.guildId eq cachedXp.guildId }
                                .limit(1)
                                .map { it[LevelingSettings.levelupMessage] }
                                .firstOrNull()
                                ?: "Congratulations {user}! You just advanced to level {level}!"

                            // Format the message
                            val levelUpMessage = messageTemplate
                                .replace("{user}", "<@${cachedXp.userId}>")
                                .replace("{level}", updatedLevel.toString())

                            logger.info { "User ${cachedXp.userId} in guild ${cachedXp.guildId} leveled up from $oldLevel to $updatedLevel. Message: $levelUpMessage" }


                            KyromeraScope.launch(Dispatchers.IO) {
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
        logger.trace { "Adding $baseXp XP for user $userId in guild $guildId for type ${type.name}" }

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

        val newCachedXp =
            currentCachedXp?.copy(xp = currentCachedXp.xp + baseXp, lastUpdated = System.currentTimeMillis())
                ?: CachedXp(guildId, userId, baseXp, System.currentTimeMillis())

        redisClient.setTyped(cacheKey, newCachedXp, CachedXp.serializer())

        return dbXp + newCachedXp.xp
    }


    suspend fun getXp(guildId: Long, userId: Long): XpPoints {
        val preCacheKey = "xp:runtimecache:$guildId:$userId"
        if (redisClient.get(preCacheKey) != null) {
            logger.debug { "Using runtime cache for XP of user $userId in guild $guildId" }
            return redisClient.get(preCacheKey)?.toIntOrNull() ?: 0
        }
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

        val xp = if (cachedXp != null) {
            dbXp + cachedXp.xp
        } else {
            dbXp
        }
        redisClient.setWithExpiry(preCacheKey, xp.toString(), 60.seconds.inWholeSeconds)

        return xp
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

    private suspend fun setLastMessageChannelInGuild(guildId: Long, channelId: Long) {
        val key = "guild:$guildId:lastMessageChannel"
        redisClient.setWithExpiry(key, channelId.toString(), 12.hours.inWholeSeconds)
        logger.debug { "Set last message channel for guild $guildId to $channelId" }
    }

    private suspend fun getLastMessageChannelInGuild(guildId: Long): Long? {
        val key = "guild:$guildId:lastMessageChannel"
        return redisClient.get(key)?.toLongOrNull()
    }


    suspend fun handleMessageCreated(createEvent: MessageReceivedEvent) {
        if (createEvent.author.isBot) return
        @Suppress("SENSELESS_COMPARISON")
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

    suspend fun getRewardRoles(guildId: Long): List<RewardRole> {
        val serializer = ListSerializer(RewardRole.serializer())
        val cacheKey = "rewardroles:$guildId"
        val cachedRoles = redisClient.getTyped(cacheKey, serializer)

        if (cachedRoles != null) {
            logger.debug { "Found cached reward roles for guild $guildId" }
            return cachedRoles
        }

        logger.debug { "No cached reward roles found for guild $guildId, querying database" }
        val dbRoles = transaction {
            LevelingUsers.selectAll()
                .where(LevelingUsers.guildId eq guildId)
                .map {
                    RewardRole(
                        guildId = it[LevelingUsers.guildId],
                        roleId = it[LevelingUsers.userId],
                        level = it[LevelingUsers.level]
                    )
                }
        }

        if (dbRoles.isEmpty()) {
            logger.debug { "No reward roles found in database for guild $guildId" }
            return emptyList()
        }

        redisClient.setTypedWithExpiry(cacheKey, dbRoles, 60.minutes.inWholeSeconds, serializer)
        logger.debug { "Cached ${dbRoles.size} reward roles for guild $guildId" }
        return dbRoles
    }
    
    // cached
    suspend fun getPingEnabled(guildId: Long, userId: Long): Boolean {
        val cacheKey = "leveling:ping:$guildId:$userId"
        val cachedPing = redisClient.get(cacheKey)?.toBoolean()
        if (cachedPing != null) {
            logger.debug { "Found cached ping setting for user $userId in guild $guildId: $cachedPing" }
            return cachedPing
        }
        logger.debug { "No cached ping setting found for user $userId in guild $guildId, querying database" }
        val dbPing = newSuspendedTransaction {
            LevelingUsers.selectAll()
                .where(LevelingUsers.guildId eq guildId and (LevelingUsers.userId eq userId))
                .map { it[LevelingUsers.pingActive] }
                .firstOrNull() ?: false
        }
        redisClient.setWithExpiry(cacheKey, dbPing.toString(), 4.hours.inWholeSeconds)
        logger.debug { "Cached ping setting for user $userId in guild $guildId: $dbPing" }
        return dbPing
    }

    suspend fun sendLevelUpMessageAndReward(userId: Long, guildId: Long, level: Int, channel: MessageChannel) {
        val baseMessage = getLevelUpMessage(guildId)
        val pingEnabled = newSuspendedTransaction {
            LevelingUsers.selectAll()
                .where(LevelingUsers.guildId eq guildId and (LevelingUsers.userId eq userId))
                .map { it[LevelingUsers.pingActive] }
                .firstOrNull() ?: false
        }

    }


}
