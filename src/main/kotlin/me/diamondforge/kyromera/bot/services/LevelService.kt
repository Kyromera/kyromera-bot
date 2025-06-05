package me.diamondforge.kyromera.bot.services

import dev.minn.jda.ktx.messages.MessageCreate
import io.github.freya022.botcommands.api.core.BContext
import io.github.freya022.botcommands.api.core.annotations.BEventListener
import io.github.freya022.botcommands.api.core.events.InjectedJDAEvent
import io.github.freya022.botcommands.api.core.service.annotations.BService
import io.github.freya022.botcommands.api.core.utils.enumSetOf
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import me.diamondforge.kyromera.bot.KyromeraScope
import me.diamondforge.kyromera.bot.enums.LevelUpAnnounceMode
import me.diamondforge.kyromera.bot.enums.XpRewardType
import me.diamondforge.kyromera.bot.models.CachedXp
import me.diamondforge.kyromera.bot.models.Rank
import me.diamondforge.kyromera.bot.models.RewardRole
import me.diamondforge.kyromera.bot.models.database.LevelingSettings
import me.diamondforge.kyromera.bot.models.database.LevelingUsers
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.Message.MentionType
import net.dv8tion.jda.api.entities.Role
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.update
import java.util.*
import kotlin.math.pow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

typealias XpPoints = Int
typealias Level = Int


private val logger = KotlinLogging.logger {}


@BService
class LevelService(
    private val redisClient: RedisClientProvider,
    private val databaseClient: DatabaseSource,
    private val context: BContext
) {
    private val cacheFlushInterval = 1.minutes
    private val voiceChannelCheckInterval = 1.minutes

    /**
     * Retrieves the level-up message template for a specific guild.
     * 
     * This method first checks the Redis cache for the message. If not found,
     * it queries the database for the guild's custom level-up message.
     * If no custom message is found, it returns a default message.
     * The retrieved message is then cached for future use.
     *
     * @param guildId The ID of the guild to get the level-up message for
     * @return The level-up message template string for the specified guild
     */
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
                ?: "Congratulations {mention}! You just advanced to level {level}!"
        }

        redisClient.setWithExpiry(cacheKey, dbMessage, 4.hours.inWholeSeconds)
        logger.debug { "Cached level-up message for guild $guildId" }

        return dbMessage
    }

    /**
     * Retrieves the level-up message template for when a user receives a role reward.
     * 
     * This method first checks the Redis cache for the message. If not found,
     * it queries the database for the guild's custom level-up reward message.
     * If no custom message is found, it returns a default message that includes
     * information about the role reward.
     * The retrieved message is then cached for future use.
     *
     * @param guildId The ID of the guild to get the level-up reward message for
     * @return The level-up reward message template string for the specified guild
     */
    suspend fun getLevelUpMessageReward(guildId: Long): String {
        val cacheKey = "levelup:message:reward:$guildId"
        val cachedMessage = redisClient.get(cacheKey)

        if (cachedMessage != null) {
            logger.debug { "Found cached level-up reward message for guild $guildId" }
            return cachedMessage
        }

        logger.debug { "No cached level-up reward message found for guild $guildId, querying database" }
        val dbMessage = newSuspendedTransaction {
            LevelingSettings
                .selectAll().where { LevelingSettings.guildId eq guildId }
                .limit(1)
                .map { it[LevelingSettings.levelupMessageReward] }
                .firstOrNull()
                ?: "Congratulations {mention}! You just advanced to level {level} and earned the {reward_names} role!"
        }

        redisClient.setWithExpiry(cacheKey, dbMessage, 4.hours.inWholeSeconds)
        logger.debug { "Cached level-up reward message for guild $guildId" }

        return dbMessage
    }

    /**
     * Retrieves the level-up announcement mode for a specific guild.
     * 
     * This method first checks the Redis cache for the announcement mode. If not found,
     * it queries the database for the guild's custom level-up announcement mode.
     * If no custom mode is found, it returns the default mode (CURRENT).
     * The retrieved mode is then cached for future use.
     *
     * @param guildId The ID of the guild to get the level-up announcement mode for
     * @return The LevelUpAnnounceMode enum value for the specified guild
     */
    suspend fun getLevelUpAnnounceMode(guildId: Long): LevelUpAnnounceMode {
        val cacheKey = "levelup:announce:mode:$guildId"
        val cachedMode = redisClient.get(cacheKey)

        if (cachedMode != null) {
            logger.debug { "Found cached level-up announce mode for guild $guildId: $cachedMode" }
            return LevelUpAnnounceMode.fromString(cachedMode)
        }

        logger.debug { "No cached level-up announce mode found for guild $guildId, querying database" }
        val dbMode = newSuspendedTransaction {
            LevelingSettings
                .selectAll().where { LevelingSettings.guildId eq guildId }
                .limit(1)
                .map { it[LevelingSettings.levelupAnnounceMode] }
                .firstOrNull()
                ?: LevelUpAnnounceMode.CURRENT.value
        }

        redisClient.setWithExpiry(cacheKey, dbMode, 4.hours.inWholeSeconds)
        logger.debug { "Cached level-up announce mode for guild $guildId: $dbMode" }

        return LevelUpAnnounceMode.fromString(dbMode)
    }

    /**
     * Sets the level-up announcement mode for a specific guild.
     * 
     * This method updates the database with the new announcement mode and
     * updates the cache to reflect the change.
     *
     * @param guildId The ID of the guild to set the level-up announcement mode for
     * @param mode The LevelUpAnnounceMode enum value to set
     */
    suspend fun setLevelUpAnnounceMode(guildId: Long, mode: LevelUpAnnounceMode) {
        val cacheKey = "levelup:announce:mode:$guildId"

        newSuspendedTransaction {
            val exists = LevelingSettings
                .selectAll().where { LevelingSettings.guildId eq guildId }
                .count() > 0

            if (exists) {
                LevelingSettings.update({ LevelingSettings.guildId eq guildId }) {
                    it[levelupAnnounceMode] = mode.value
                }
            } else {
                LevelingSettings.insert {
                    it[LevelingSettings.guildId] = guildId
                    it[levelupAnnounceMode] = mode.value
                }
            }
        }

        redisClient.setWithExpiry(cacheKey, mode.value, 4.hours.inWholeSeconds)
        logger.debug { "Updated level-up announce mode for guild $guildId to ${mode.value}" }
    }


    @BEventListener
    suspend fun onInjectedJDA(event: InjectedJDAEvent) {
        logger.info { "JDA instance is ready, starting workers" }
        KyromeraScope.launch(Dispatchers.IO) {
            startCacheFlushWorker()
        }

        KyromeraScope.launch(Dispatchers.IO) {
            startVoiceChannelMonitoringWorker()
        }
    }

    /**
     * Starts a background worker that periodically flushes cached XP data to the database.
     * 
     * This worker runs continuously in the background, flushing XP cache to the database
     * at regular intervals defined by [cacheFlushInterval]. It includes error handling
     * and automatic recovery mechanisms to ensure the worker continues running even
     * if errors occur during the flush process.
     */
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

    /**
     * Starts a background worker that monitors voice channels and awards XP to users.
     * 
     * This worker runs continuously in the background, checking voice channels at regular
     * intervals defined by [voiceChannelCheckInterval]. It awards XP to users who are
     * actively participating in voice channels. The worker includes error handling and
     * automatic recovery mechanisms to ensure it continues running even if errors occur.
     * 
     * The worker waits for the JDA instance to be fully ready before starting the monitoring.
     */
    private suspend fun startVoiceChannelMonitoringWorker() {
        logger.info { "Starting voice channel monitoring worker with interval: $voiceChannelCheckInterval" }
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

    /**
     * Checks all voice channels across all guilds and awards XP to eligible users.
     * 
     * This method iterates through all guilds and their voice channels, awarding XP to users who are:
     * - Not bots
     * - Not muted or deafened
     * - In a voice channel with at least one other unmuted user
     * - Not in the guild's AFK channel
     * 
     * The method includes comprehensive error handling to ensure that failures in processing
     * one guild or channel don't prevent others from being processed.
     */
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

    /**
     * Flushes cached XP data from Redis to the database.
     * 
     * This method retrieves all pending XP entries from Redis cache and persists them to the database.
     * For each entry, it:
     * 1. Retrieves the cached XP data
     * 2. Checks if the user already exists in the database
     * 3. Updates existing users or creates new user records as needed
     * 4. Calculates new levels based on updated XP totals
     * 5. Triggers level-up messages and rewards if a user has leveled up
     * 6. Deletes the cache entry after successful processing
     * 
     * The method includes error handling for individual cache entries to ensure that
     * failures in processing one entry don't prevent others from being processed.
     */
    private suspend fun flushCacheToDatabase() {
        val cacheKeys = redisClient.getKeysByPattern("xp:pending:*")

        if (cacheKeys.isEmpty()) {
            return
        }

        logger.info { "Flushing ${cacheKeys.size} XP cache entries to database" }

        cacheKeys.forEach { key ->
            try {
                redisClient.get(key) ?: return@forEach
                val cachedXp = redisClient.getTyped(key, CachedXp.serializer()) ?: return@forEach

                newSuspendedTransaction {
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
                            logger.info { "User ${cachedXp.userId} in guild ${cachedXp.guildId} leveled up from $oldLevel to $updatedLevel." }

                            KyromeraScope.launch(Dispatchers.IO) {
                                val lastChannelId = getLastMessageChannelInGuild(cachedXp.guildId)
                                if (lastChannelId != null) {
                                    try {
                                        val jda = context.jda
                                        val channel = jda.getChannelById(MessageChannel::class.java, lastChannelId)
                                        if (channel != null) {
                                            sendLevelUpMessageAndReward(
                                                userId = cachedXp.userId,
                                                guildId = cachedXp.guildId,
                                                level = updatedLevel,
                                                oldLevel = oldLevel,
                                                xp = updatedXp,
                                                channel = channel,
                                                oldXp = existingXp
                                            )
                                            logger.debug { "Sent level-up message to channel $lastChannelId in guild ${cachedXp.guildId}" }
                                        }
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

    /**
     * Adds XP to a user in a specific guild based on the reward type.
     * 
     * This method handles the core XP awarding logic:
     * 1. Checks if the user is on cooldown for the specified reward type
     * 2. If not on cooldown, sets a cooldown and awards XP
     * 3. Retrieves existing XP from both cache and database
     * 4. Updates the cache with the new XP value
     * 
     * The XP is stored in Redis cache and later persisted to the database by the cache flush worker.
     *
     * @param guildId The ID of the guild where the user earned XP
     * @param userId The ID of the user to award XP to
     * @param type The type of activity that earned the XP (message, voice, etc.)
     * @return The user's new total XP after adding the reward, or null if the user is on cooldown
     */
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

        val cacheKey = "xp:pending:$guildId:$userId"
        val currentCachedXp = redisClient.getTyped(cacheKey, CachedXp.serializer())

        val dbXp = newSuspendedTransaction {
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


    /**
     * Retrieves the total XP for a user in a specific guild.
     * 
     * This method implements a multi-level caching strategy:
     * 1. First checks a short-lived runtime cache for the most recent XP value
     * 2. If not found, checks the pending XP cache for any XP not yet persisted to the database
     * 3. Retrieves the user's XP from the database
     * 4. Combines database XP with any pending cached XP to get the total
     * 5. Stores the result in the runtime cache for a short period
     *
     * @param guildId The ID of the guild where the user's XP is stored
     * @param userId The ID of the user whose XP to retrieve
     * @return The total XP points for the user in the specified guild
     */
    suspend fun getXp(guildId: Long, userId: Long): XpPoints {
        val preCacheKey = "xp:runtimecache:$guildId:$userId"
        if (redisClient.get(preCacheKey) != null) {
            logger.debug { "Using runtime cache for XP of user $userId in guild $guildId" }
            return redisClient.get(preCacheKey)?.toIntOrNull() ?: 0
        }
        val cacheKey = "xp:pending:$guildId:$userId"
        val cachedXp = redisClient.getTyped(cacheKey, CachedXp.serializer())

        val dbXp = newSuspendedTransaction {
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

    /**
     * Retrieves the current level for a user in a specific guild.
     * 
     * This method first gets the user's total XP using [getXp], then calculates
     * the corresponding level using [levelAtXp].
     *
     * @param guildId The ID of the guild where the user's level is being checked
     * @param userId The ID of the user whose level to retrieve
     * @return The current level of the user in the specified guild
     */
    suspend fun getLevel(guildId: Long, userId: Long): Level {
        val totalXp = getXp(guildId, userId)
        return levelAtXp(totalXp)
    }

    /**
     * Determines the base amount of XP to award for a specific activity type.
     * 
     * This method returns a random amount of XP within a predefined range based on the activity type:
     * - Message: 15-25 XP for sending a message
     * - Voice: 2-6 XP for active participation in voice channels
     *
     * @param type The type of activity that earned the XP
     * @return A random amount of XP within the range for the specified activity type
     */
    fun getBaseXp(type: XpRewardType): Int = when (type) {
        XpRewardType.Message -> (15..25).random()
        XpRewardType.Voice -> (2..6).random()
    }


    /**
     * Calculates the total XP required to reach a specific level.
     * 
     * This method uses a mathematical formula to determine the cumulative XP
     * required to reach a given level. The formula is a polynomial function
     * that creates a non-linear progression curve, making higher levels
     * require increasingly more XP to achieve.
     *
     * @param level The level to calculate the XP requirement for
     * @return The total XP required to reach the specified level
     */
    fun xpForLevel(level: Int): Int {
        if (level <= 0) return 0
        val alvl: Int = level - 1
        return (5 / 6.0 * (151 * alvl + 33 * alvl.toDouble().pow(2.0) + 2 * alvl.toDouble().pow(3.0)) + 100).toInt()
    }


    /**
     * Calculates the percentage of progress towards the next level.
     * 
     * This method determines how far a user has progressed within their current level
     * by calculating what percentage of the XP required for the next level has been earned.
     * The result is a value between 0.0 (just reached current level) and 1.0 (ready to level up).
     *
     * @param level The user's current level
     * @param xp The user's total XP
     * @return A value between 0.0 and 1.0 representing the percentage progress towards the next level
     */
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


    /**
     * Determines the level corresponding to a given amount of total XP.
     * 
     * This method calculates what level a user should be at based on their total accumulated XP.
     * It iteratively checks each level's XP requirement against the total XP until it finds
     * the highest level the user has enough XP to achieve.
     *
     * @param totalXp The total XP to calculate the level for
     * @return The level corresponding to the given XP amount
     */
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


    /**
     * Calculates how much more XP is needed to reach the next level.
     * 
     * This method determines the amount of additional XP a user needs to earn
     * to advance from their current level to the next level.
     *
     * @param level The user's current level
     * @param totalXp The user's current total XP
     * @return The amount of XP needed to reach the next level
     */
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

    /**
     * Calculates the minimum and maximum XP values for a specific level.
     * 
     * This method determines the XP range that corresponds to a given level.
     * The minimum is the XP required to reach the level, and the maximum
     * is one less than the XP required to reach the next level.
     *
     * @param level The level to calculate the XP range for
     * @return A Pair containing the minimum and maximum XP values for the level
     */
    fun MinAndMaxXpForLevel(level: Int): Pair<Int, Int> {
        if (level <= 0) return Pair(0, 0)

        val currentXp = xpForLevel(level)
        val nextXp = xpForLevel(level + 1)

        return Pair(currentXp, nextXp - 1)
    }

    /**
     * Determines the cooldown period for XP rewards based on the activity type.
     * 
     * This method returns the duration that must elapse before a user can
     * earn XP again for the same type of activity. This prevents users from
     * earning XP too quickly by spamming messages or other actions.
     *
     * @param type The type of activity to get the cooldown for
     * @return The cooldown duration for the specified activity type
     */
    private fun getXpCooldown(type: XpRewardType): Duration {
        return when (type) {
            XpRewardType.Message -> 60.seconds
            XpRewardType.Voice -> 60.seconds
        }
    }

    /**
     * Stores the ID of the last active message channel in a guild.
     * 
     * This method caches the channel ID in Redis with an expiry time,
     * allowing the bot to remember where to send level-up messages.
     *
     * @param guildId The ID of the guild
     * @param channelId The ID of the channel to record as the last active channel
     */
    private suspend fun setLastMessageChannelInGuild(guildId: Long, channelId: Long) {
        val key = "guild:$guildId:lastMessageChannel"
        redisClient.setWithExpiry(key, channelId.toString(), 12.hours.inWholeSeconds)
        logger.debug { "Set last message channel for guild $guildId to $channelId" }
    }

    /**
     * Retrieves the ID of the last active message channel in a guild.
     * 
     * This method fetches the cached channel ID from Redis, which was previously
     * stored by [setLastMessageChannelInGuild]. This is used to determine where
     * to send level-up messages.
     *
     * @param guildId The ID of the guild to get the last active channel for
     * @return The ID of the last active channel, or null if no recent activity
     */
    private suspend fun getLastMessageChannelInGuild(guildId: Long): Long? {
        val key = "guild:$guildId:lastMessageChannel"
        return redisClient.get(key)?.toLongOrNull()
    }


    /**
     * Handles message creation events for XP rewards.
     * 
     * This method is called when a new message is sent in a guild. It:
     * 1. Filters out messages from bots and DMs
     * 2. Records the channel as the last active channel in the guild
     * 3. Awards XP to the message author if they're not on cooldown
     * 
     * The XP award is subject to cooldown periods to prevent spam.
     *
     * @param createEvent The message received event containing information about the message
     */
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

    /**
     * Retrieves the list of role rewards configured for a guild.
     * 
     * This method fetches role rewards that are granted to users when they reach specific levels.
     * It first checks the Redis cache, and if not found, queries the database.
     * The results are cached for future use to improve performance.
     *
     * @param guildId The ID of the guild to get reward roles for
     * @return A list of RewardRole objects containing role IDs and their required levels
     */
    suspend fun getRewardRoles(guildId: Long): List<RewardRole> {
        val serializer = ListSerializer(RewardRole.serializer())
        val cacheKey = "rewardroles:$guildId"
        val cachedRoles = redisClient.getTyped(cacheKey, serializer)

        if (cachedRoles != null) {
            logger.debug { "Found cached reward roles for guild $guildId" }
            return cachedRoles
        }

        logger.debug { "No cached reward roles found for guild $guildId, querying database" }
        val dbRoles = newSuspendedTransaction {
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


    /**
     * Checks if a user has enabled level-up ping notifications.
     * 
     * This method determines whether a user should be mentioned (pinged) in level-up messages.
     * It first checks the Redis cache, and if not found, queries the database.
     * The result is cached for future use to improve performance.
     *
     * @param guildId The ID of the guild where the user is
     * @param userId The ID of the user to check ping settings for
     * @return True if the user has enabled level-up pings, false otherwise
     */
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

    /**
     * Sends a level-up message and assigns role rewards if applicable.
     * 
     * This method is called when a user levels up. It:
     * 1. Checks if the user has enabled ping notifications
     * 2. Retrieves the appropriate level-up message template
     * 3. Determines if any role rewards should be granted
     * 4. Formats the message with user and level information
     * 5. Sends the message to the specified channel
     * 6. Assigns any earned role rewards to the user
     *
     * @param userId The ID of the user who leveled up
     * @param guildId The ID of the guild where the level-up occurred
     * @param level The user's new level
     * @param oldLevel The user's previous level
     * @param xp The user's current total XP
     * @param channel The channel to send the level-up message to
     * @param oldXp The user's previous XP total (defaults to 0)
     */
    private suspend fun sendLevelUpMessageAndReward(userId: Long, guildId: Long, level: Int, oldLevel: Int, xp: Int, channel: MessageChannel, oldXp: Int = 0) {
        try {
            val announceMode = getLevelUpAnnounceMode(guildId)

            if (!announceMode.isEnabled()) {
                logger.debug { "Level-up announcements are disabled for guild $guildId" }
                assignRewardRoles(userId, guildId, level, oldLevel)
                return
            }

            val pingEnabled = isPingEnabled(guildId, userId)

            val jda = context.jda
            val guild = try {
                jda.getGuildById(guildId) ?: run {
                    logger.error { "Failed to get guild $guildId, guild not found" }
                    return
                }
            } catch (e: Exception) {
                logger.error(e) { "Failed to get guild $guildId due to exception" }
                return
            }

            val user = try {
                jda.getUserById(userId) ?: run {
                    logger.error { "Failed to get user $userId, user not found" }
                    return
                }
            } catch (e: Exception) {
                logger.error(e) { "Failed to get user $userId due to exception" }
                return
            }

            val member = try {
                guild.getMember(user) ?: run {
                    logger.error { "Failed to get member for user $userId in guild $guildId, member not found" }
                    return
                }
            } catch (e: Exception) {
                logger.error(e) { "Failed to get member for user $userId in guild $guildId due to exception" }
                return
            }

            val rewardRoles = try {
                if (level > oldLevel) {
                    getRewardRoles(guildId).filter { it.level == level }
                } else {
                    emptyList()
                }
            } catch (e: Exception) {
                logger.error(e) { "Failed to get reward roles for guild $guildId, defaulting to empty list" }
                emptyList()
            }

            val rewardRoleObjects = try {
                rewardRoles.mapNotNull { 
                    try {
                        guild.getRoleById(it.roleId)
                    } catch (e: Exception) {
                        logger.error(e) { "Failed to get role ${it.roleId} in guild $guildId" }
                        null
                    }
                }
            } catch (e: Exception) {
                logger.error(e) { "Failed to map reward roles to role objects for guild $guildId" }
                emptyList()
            }

            val baseMessage = try {
                if (level > oldLevel && rewardRoleObjects.isNotEmpty()) {
                    getLevelUpMessageReward(guildId)
                } else {
                    getLevelUpMessage(guildId)
                }
            } catch (e: Exception) {
                logger.error(e) { "Failed to get level-up message for guild $guildId, using default message" }
                "Congratulations {mention}! You just advanced to level {level}!"
            }

            val templatedMessage = try {
                templateLevelUpMessage(
                    baseMessage,
                    user,
                    member,
                    level,
                    oldLevel,
                    xp,
                    rewardRoleObjects,
                    oldXp
                )
            } catch (e: Exception) {
                logger.error(e) { "Failed to template level-up message for user $userId in guild $guildId, using simple message" }
                "Congratulations ${user.asMention}! You just advanced to level $level!"
            }

            val message = MessageCreate {
                content = templatedMessage
                allowedMentionTypes = if (pingEnabled) {
                    EnumSet.of(MentionType.USER, MentionType.ROLE)
                } else {
                    enumSetOf(MentionType.ROLE)
                }
            }

            when {
                announceMode.isDM() -> {
                    try {
                        user.openPrivateChannel().queue({ privateChannel ->
                            privateChannel.sendMessage(message).queue(
                                { logger.debug { "Sent level-up DM to user $userId in guild $guildId" } },
                                { e -> logger.error(e) { "Failed to send level-up DM to user $userId in guild $guildId" } }
                            )
                        }, { e ->
                            logger.error(e) { "Failed to open private channel for user $userId in guild $guildId" }
                        })
                    } catch (e: Exception) {
                        logger.error(e) { "Failed to open private channel for user $userId in guild $guildId" }
                    }
                }
                announceMode.isCustomChannel() -> {
                    val customChannelId = try {
                        newSuspendedTransaction {
                            LevelingSettings
                                .selectAll().where { LevelingSettings.guildId eq guildId }
                                .limit(1)
                                .map { it[LevelingSettings.levelupChannel] }
                                .firstOrNull()
                        }
                    } catch (e: Exception) {
                        logger.error(e) { "Failed to get custom channel ID for guild $guildId, no level-up message will be sent" }
                        null
                    }

                    if (customChannelId != null) {
                        try {
                            val customChannel = jda.getChannelById(MessageChannel::class.java, customChannelId)
                            if (customChannel != null) {
                                customChannel.sendMessage(message).queue(
                                    { logger.debug { "Sent level-up message to custom channel $customChannelId in guild $guildId" } },
                                    { e -> logger.error(e) { "Failed to send level-up message to custom channel $customChannelId in guild $guildId, no message will be sent" } }
                                )
                            } else {
                                logger.warn { "Custom channel $customChannelId not found in guild $guildId, no level-up message will be sent" }
                            }
                        } catch (e: Exception) {
                            logger.error(e) { "Error sending to custom channel $customChannelId in guild $guildId, no level-up message will be sent" }
                        }
                    } else {
                        logger.warn { "No custom channel configured for guild $guildId, no level-up message will be sent" }
                    }
                }
                announceMode.isCurrentChannel() -> {
                    try {
                        channel.sendMessage(message).queue(
                            { logger.debug { "Sent level-up message to current channel in guild $guildId" } },
                            { e -> logger.error(e) { "Failed to send level-up message to current channel in guild $guildId" } }
                        )
                    } catch (e: Exception) {
                        logger.error(e) { "Failed to send level-up message to current channel in guild $guildId" }
                    }
                }
                else -> {
                    logger.warn { "Unknown announce mode for guild $guildId, not sending level-up message" }
                }
            }

            if (level > oldLevel && rewardRoleObjects.isNotEmpty()) {
                rewardRoleObjects.forEach { role ->
                    try {
                        guild.addRoleToMember(member, role).queue(
                            { logger.debug { "Assigned role ${role.name} to user $userId in guild $guildId" } },
                            { e -> logger.error(e) { "Failed to assign role ${role.name} to user $userId in guild $guildId" } }
                        )
                    } catch (e: Exception) {
                        logger.error(e) { "Failed to assign role ${role.name} to user $userId in guild $guildId" }
                    }
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Critical error in sendLevelUpMessageAndReward for user $userId in guild $guildId" }
        }
    }

    /**
     * Checks whether the ping feature is enabled for a specific user in a specific guild.
     *
     * @param guildId The ID of the guild to query.
     * @param userId The ID of the user to check the ping status for.
     * @return Returns true if the ping feature is enabled for the user in the specified guild, false otherwise.
     */
    private suspend fun isPingEnabled(guildId: Long, userId: Long): Boolean {
        val pingEnabled = try {
            newSuspendedTransaction {
                LevelingUsers.selectAll()
                    .where(LevelingUsers.guildId eq guildId and (LevelingUsers.userId eq userId))
                    .map { it[LevelingUsers.pingActive] }
                    .firstOrNull() ?: false
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to get ping setting for user $userId in guild $guildId, defaulting to false" }
            false
        }
        return pingEnabled
    }
    
    /**
     * Toggles the ping notification setting for a user in a specific guild.
     *
     * @param guildId The ID of the guild where the setting is being updated.
     * @param userId The ID of the user whose ping setting is being toggled.
     * @param enabled A boolean indicating whether ping notifications should be enabled or disabled.
     */
    private suspend fun togglePingEnabled(guildId: Long, userId: Long, enabled: Boolean) {
        val cacheKey = "leveling:ping:$guildId:$userId"
        redisClient.setWithExpiry(cacheKey, enabled.toString(), 4.hours.inWholeSeconds)

        newSuspendedTransaction {
            LevelingUsers.update({
                LevelingUsers.guildId eq guildId and (LevelingUsers.userId eq userId)
            }) {
                it[pingActive] = enabled
            }
        }
        logger.debug { "Set ping enabled for user $userId in guild $guildId to $enabled" }
    }

    /**
     * Drops all caches for a specific guild.
     *
     * This method clears all Redis caches related to the specified guild, including:
     * - Level-up message templates
     * - Level-up announcement mode
     * - Reward roles
     * - Last message channel
     *
     * This should be called whenever guild settings are changed to ensure the bot
     * uses the updated settings.
     *
     * @param guildId The ID of the guild to drop caches for
     */
    suspend fun dropGuildCaches(guildId: Long) {
        logger.info { "Dropping all caches for guild $guildId" }

        // Drop level-up message caches
        dropLevelUpMessageCache(guildId)
        dropLevelUpRewardMessageCache(guildId)

        // Drop announcement mode cache
        dropLevelUpAnnounceModeCache(guildId)

        // Drop reward roles cache
        dropRewardRolesCache(guildId)

        // Drop last message channel cache
        dropLastMessageChannelCache(guildId)

        logger.info { "Successfully dropped all caches for guild $guildId" }
    }

    /**
     * Drops the level-up message template cache for a specific guild.
     *
     * @param guildId The ID of the guild to drop the cache for
     */
    suspend fun dropLevelUpMessageCache(guildId: Long) {
        val cacheKey = "levelup:message:$guildId"
        redisClient.delete(cacheKey)
        logger.debug { "Dropped level-up message cache for guild $guildId" }
    }

    /**
     * Drops the level-up reward message template cache for a specific guild.
     *
     * @param guildId The ID of the guild to drop the cache for
     */
    suspend fun dropLevelUpRewardMessageCache(guildId: Long) {
        val cacheKey = "levelup:message:reward:$guildId"
        redisClient.delete(cacheKey)
        logger.debug { "Dropped level-up reward message cache for guild $guildId" }
    }

    /**
     * Drops the level-up announcement mode cache for a specific guild.
     *
     * @param guildId The ID of the guild to drop the cache for
     */
    suspend fun dropLevelUpAnnounceModeCache(guildId: Long) {
        val cacheKey = "levelup:announce:mode:$guildId"
        redisClient.delete(cacheKey)
        logger.debug { "Dropped level-up announce mode cache for guild $guildId" }
    }

    /**
     * Drops the reward roles cache for a specific guild.
     *
     * @param guildId The ID of the guild to drop the cache for
     */
    suspend fun dropRewardRolesCache(guildId: Long) {
        val cacheKey = "rewardroles:$guildId"
        redisClient.delete(cacheKey)
        logger.debug { "Dropped reward roles cache for guild $guildId" }
    }

    /**
     * Drops the last message channel cache for a specific guild.
     *
     * @param guildId The ID of the guild to drop the cache for
     */
    suspend fun dropLastMessageChannelCache(guildId: Long) {
        val cacheKey = "guild:$guildId:lastMessageChannel"
        redisClient.delete(cacheKey)
        logger.debug { "Dropped last message channel cache for guild $guildId" }
    }

    /**
     * Drops the ping enabled cache for a specific user in a guild.
     *
     * @param guildId The ID of the guild
     * @param userId The ID of the user
     */
    suspend fun dropPingEnabledCache(guildId: Long, userId: Long) {
        val cacheKey = "leveling:ping:$guildId:$userId"
        redisClient.delete(cacheKey)
        logger.debug { "Dropped ping enabled cache for user $userId in guild $guildId" }
    }

    /**
     * Drops the XP runtime cache for a specific user in a guild.
     *
     * @param guildId The ID of the guild
     * @param userId The ID of the user
     */
    suspend fun dropXpRuntimeCache(guildId: Long, userId: Long) {
        val cacheKey = "xp:runtimecache:$guildId:$userId"
        redisClient.delete(cacheKey)
        logger.debug { "Dropped XP runtime cache for user $userId in guild $guildId" }
    }

    /**
     * Drops all user-specific caches for a user in a guild.
     *
     * @param guildId The ID of the guild
     * @param userId The ID of the user
     */
    suspend fun dropUserCaches(guildId: Long, userId: Long) {
        logger.info { "Dropping all caches for user $userId in guild $guildId" }

        // Drop ping enabled cache
        dropPingEnabledCache(guildId, userId)

        // Drop XP runtime cache
        dropXpRuntimeCache(guildId, userId)

        // Drop XP cooldown caches
        val cooldownKeyPattern = "xp:cooldown:$guildId:$userId:*"
        val cooldownKeys = redisClient.getKeysByPattern(cooldownKeyPattern)
        cooldownKeys.forEach { redisClient.delete(it) }
        logger.debug { "Dropped ${cooldownKeys.size} XP cooldown caches for user $userId in guild $guildId" }

        logger.info { "Successfully dropped all caches for user $userId in guild $guildId" }
    }

    /**
     * Assigns reward roles to a user who has leveled up.
     * 
     * This helper method handles the role assignment logic separately from message sending,
     * allowing roles to be assigned even when level-up announcements are disabled.
     *
     * @param userId The ID of the user who leveled up
     * @param guildId The ID of the guild where the level-up occurred
     * @param level The user's new level
     * @param oldLevel The user's previous level
     */
    private suspend fun assignRewardRoles(userId: Long, guildId: Long, level: Int, oldLevel: Int) {
        try {
            if (level <= oldLevel) {
                logger.debug { "Not assigning roles for user $userId in guild $guildId as level ($level) is not greater than old level ($oldLevel)" }
                return
            }

            val jda = context.jda
            val guild = try {
                jda.getGuildById(guildId) ?: run {
                    logger.error { "Failed to get guild $guildId for role assignment, guild not found" }
                    return
                }
            } catch (e: Exception) {
                logger.error(e) { "Failed to get guild $guildId for role assignment due to exception" }
                return
            }

            val user = try {
                jda.getUserById(userId) ?: run {
                    logger.error { "Failed to get user $userId for role assignment, user not found" }
                    return
                }
            } catch (e: Exception) {
                logger.error(e) { "Failed to get user $userId for role assignment due to exception" }
                return
            }

            val member = try {
                guild.getMember(user) ?: run {
                    logger.error { "Failed to get member for user $userId in guild $guildId for role assignment, member not found" }
                    return
                }
            } catch (e: Exception) {
                logger.error(e) { "Failed to get member for user $userId in guild $guildId for role assignment due to exception" }
                return
            }

            val rewardRoles = try {
                getRewardRoles(guildId).filter { it.level == level }
            } catch (e: Exception) {
                logger.error(e) { "Failed to get reward roles for guild $guildId, defaulting to empty list" }
                emptyList()
            }

            if (rewardRoles.isEmpty()) {
                logger.debug { "No reward roles found for level $level in guild $guildId" }
                return
            }

            val rewardRoleObjects = try {
                rewardRoles.mapNotNull { 
                    try {
                        guild.getRoleById(it.roleId)
                    } catch (e: Exception) {
                        logger.error(e) { "Failed to get role ${it.roleId} in guild $guildId for role assignment" }
                        null
                    }
                }
            } catch (e: Exception) {
                logger.error(e) { "Failed to map reward roles to role objects for guild $guildId" }
                emptyList()
            }

            if (rewardRoleObjects.isEmpty()) {
                logger.debug { "No valid reward role objects found for level $level in guild $guildId" }
                return
            }

            logger.info { "Assigning ${rewardRoleObjects.size} roles to user $userId in guild $guildId for reaching level $level" }
            rewardRoleObjects.forEach { role ->
                try {
                    guild.addRoleToMember(member, role).queue(
                        { logger.debug { "Assigned role ${role.name} to user $userId in guild $guildId" } },
                        { e -> logger.error(e) { "Failed to assign role ${role.name} to user $userId in guild $guildId" } }
                    )
                } catch (e: Exception) {
                    logger.error(e) { "Failed to assign role ${role.name} to user $userId in guild $guildId due to exception" }
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Critical error in assignRewardRoles for user $userId in guild $guildId" }
        }
    }

    /**
     * Generates a level-up message by replacing tokens in the provided template with actual values.
     *
     * This method takes a message template containing placeholder tokens and replaces them
     * with actual user, level, and reward information to create a personalized level-up message.
     *
     * Supported tokens:
     * {name} - The user's full tag, including their discriminator (e.g., user#1234)
     * {nick} - The nickname of the user in the guild, or their username if no nickname exists
     * {mention} - A mention of the user that pings them in the guild
     * {level} - The new level that the user has reached
     * {old_level} - The user's previous level before leveling up
     * {xp} - The user's updated XP total after leveling up
     * {old_xp} - The user's XP total before leveling up
     *
     * Additional tokens for role rewards:
     * {reward_names} - A comma-separated list of the names of the roles awarded
     * {reward_mentions} - A mention of the awarded roles (directly pings these roles in the guild)
     *
     * @param template The message template containing placeholder tokens
     * @param user The Discord user who leveled up
     * @param member The guild member representation of the user
     * @param level The user's new level
     * @param oldLevel The user's previous level
     * @param xp The user's current total XP
     * @param rewardRoles A list of roles awarded to the user (if any)
     * @param oldXp The user's previous XP total (defaults to 0)
     * @return The formatted level-up message with all tokens replaced
     */
    fun templateLevelUpMessage(
        template: String,
        user: User,
        member: Member,
        level: Int,
        oldLevel: Int,
        xp: Int,
        rewardRoles: List<Role> = emptyList(),
        oldXp: Int = 0
    ): String {
        var result = template

        result = result.replace("{name}", user.asTag)
        result = result.replace("{nick}", member.effectiveName)
        result = result.replace("{mention}", user.asMention)

        result = result.replace("{level}", level.toString())
        result = result.replace("{old_level}", oldLevel.toString())
        result = result.replace("{xp}", xp.toString())
        result = result.replace("{old_xp}", oldXp.toString())

        if (rewardRoles.isNotEmpty()) {
            val rewardNames = rewardRoles.joinToString(", ") { it.name }
            val rewardMentions = rewardRoles.joinToString(" ") { it.asMention }

            result = result.replace("{reward_names}", rewardNames)
            result = result.replace("{reward_mentions}", rewardMentions)
        } else {
            result = result.replace("{reward_names}", "")
            result = result.replace("{reward_mentions}", "")
        }

        return result
    }

    /**
     * Retrieves a list of reward roles corresponding to a specific level for a given guild.
     *
     * @param guildId The unique identifier of the guild.
     * @param level The level for which to fetch the reward roles.
     * @return A list of reward roles that match the specified level in the guild.
     */
    suspend fun getRewardRoleForLevel(guildId: Long, level: Int): List<RewardRole> {
        return getRewardRoles(guildId).filter { it.level == level }
    }


    /**
     * Retrieves the rank of a user within a specified guild based on their XP.
     *
     * This function queries the `LevelingUsers` table to get the user's XP in the specified guild.
     * If the user does not exist in the table, they are inserted with default values and then re-fetched.
     * It then calculates the user's rank by counting how many users in the same guild have higher XP.
     *
     * @param guildId The ID of the guild in which the user's rank should be determined.
     * @param userId The ID of the user whose rank is being retrieved.
     * @return A [Rank] object containing the user's rank, XP, level, and associated IDs.
     */
    suspend fun getRank(guildId: Long, userId: Long): Rank = newSuspendedTransaction {
        val userRow = LevelingUsers
            .selectAll()
            .where { (LevelingUsers.guildId eq guildId) and (LevelingUsers.userId eq userId) }
            .singleOrNull()
            ?: run {
                LevelingUsers.insert {
                    it[LevelingUsers.guildId] = guildId
                    it[LevelingUsers.userId] = userId
                }

                LevelingUsers
                    .selectAll()
                    .where { (LevelingUsers.guildId eq guildId) and (LevelingUsers.userId eq userId) }
                    .single()
            }

        val userXp = userRow[LevelingUsers.xp]

        val higherXpCount = LevelingUsers
            .selectAll()
            .where { (LevelingUsers.guildId eq guildId) and (LevelingUsers.xp greater userXp) }
            .count()

        Rank(
            userId = userId,
            guildId = guildId,
            level = levelAtXp(userXp),
            xp = userXp,
            rank = higherXpCount + 1
        )
    }


}
