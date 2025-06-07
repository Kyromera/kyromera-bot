package me.diamondforge.kyromera.bot.listeners

import io.github.freya022.botcommands.api.core.annotations.BEventListener
import io.github.freya022.botcommands.api.core.service.annotations.BService
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.diamondforge.kyromera.bot.enums.LevelUpAnnounceMode
import me.diamondforge.kyromera.bot.models.database.LevelingSettings
import net.dv8tion.jda.api.events.guild.GuildJoinEvent
import net.dv8tion.jda.api.events.guild.GuildReadyEvent
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction

/**
 * Listener for guild-related events to initialize leveling settings.
 *
 * This listener handles events when the bot joins a new guild or when a guild becomes ready
 * after the bot starts up. It ensures that each guild has an entry in the leveling_settings table
 * with default values.
 */
@BService
class GuildJoinListener() {
    private val logger = KotlinLogging.logger {}

    /**
     * Handles the event when the bot joins a new guild.
     *
     * @param event The GuildJoinEvent containing information about the guild
     */
    @BEventListener
    suspend fun onGuildJoin(event: GuildJoinEvent) {
        val guildId = event.guild.idLong
        val guildName = event.guild.name
        logger.info { "Bot joined guild: $guildName (ID: $guildId)" }
        logger.trace { "Initializing leveling settings for guild $guildName (ID: $guildId)" }
        initializeGuildSettings(guildId)
    }

    /**
     * Handles the event when a guild becomes ready after the bot starts up.
     *
     * @param event The GuildReadyEvent containing information about the guild
     */
    @BEventListener
    suspend fun onGuildReady(event: GuildReadyEvent) {
        val guildId = event.guild.idLong
        val guildName = event.guild.name
        logger.trace { "Guild ready: $guildName (ID: $guildId). Initializing leveling settings." }
        initializeGuildSettings(guildId)
    }

    /**
     * Initializes the leveling settings for a guild if they don't already exist.
     *
     * This method checks the database and creates a new entry with default values if needed.
     *
     * @param guildId The ID of the guild to initialize settings for
     */
    private suspend fun initializeGuildSettings(guildId: Long) {
        withContext(Dispatchers.IO) {
            try {
                newSuspendedTransaction {
                    val exists = LevelingSettings
                        .selectAll().where { LevelingSettings.guildId eq guildId }
                        .limit(1).count() > 0

                    if (!exists) {
                        logger.info { "Creating default leveling settings for guild $guildId" }
                        try {
                            LevelingSettings.insert {
                                it[LevelingSettings.guildId] = guildId
                                it[textEnabled] = true
                                it[vcEnabled] = true
                                it[textMulti] = 1.0
                                it[vcMulti] = 1.0
                                it[retainRoles] = false
                                it[lastRecalc] = 0
                                it[whitelistMode] = false
                                it[levelupAnnounceMode] = LevelUpAnnounceMode.CURRENT.value
                            }
                            logger.trace { "Successfully created default leveling settings for guild $guildId" }
                        } catch (e: Exception) {
                            logger.error(e) { "Database error while creating leveling settings for guild $guildId" }
                            throw e
                        }
                    } else {
                        logger.trace { "Leveling settings already exist for guild $guildId" }
                    }
                }

                logger.trace { "Cached guild $guildId initialization status (initialized=true)" }

            } catch (e: Exception) {
                logger.error(e) { "Error initializing leveling settings for guild $guildId" }
            }
        }
    }
}
