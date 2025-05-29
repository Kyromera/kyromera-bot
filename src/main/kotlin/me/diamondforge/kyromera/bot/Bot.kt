package me.diamondforge.kyromera.bot

import io.github.freya022.botcommands.api.core.JDAService
import io.github.freya022.botcommands.api.core.events.BReadyEvent
import io.github.freya022.botcommands.api.core.lightSharded
import io.github.freya022.botcommands.api.core.service.annotations.BService
import io.github.oshai.kotlinlogging.KotlinLogging
import me.diamondforge.kyromera.bot.configuration.Config
import me.diamondforge.kyromera.bot.configuration.Environment
import me.diamondforge.kyromera.bot.models.database.LevelingTimestamps
import me.diamondforge.kyromera.bot.models.database.LevelingUsers
import me.diamondforge.kyromera.bot.runtimeTests.database.testDatabaseConnectionPool
import me.diamondforge.kyromera.bot.services.DatabaseSource
import net.dv8tion.jda.api.OnlineStatus
import net.dv8tion.jda.api.entities.Activity
import net.dv8tion.jda.api.hooks.IEventManager
import net.dv8tion.jda.api.requests.GatewayIntent
import net.dv8tion.jda.api.sharding.DefaultShardManagerBuilder
import net.dv8tion.jda.api.sharding.ShardManager
import net.dv8tion.jda.api.utils.ChunkingFilter
import net.dv8tion.jda.api.utils.MemberCachePolicy
import net.dv8tion.jda.api.utils.cache.CacheFlag


private val logger by lazy { KotlinLogging.logger {} }


@BService
class Bot(private val config: Config, private val databaseSource: DatabaseSource) : JDAService() {
    override val intents: Set<GatewayIntent> =
        defaultIntents + GatewayIntent.GUILD_MEMBERS


    override val cacheFlags: Set<CacheFlag> = emptySet()


    override fun createJDA(event: BReadyEvent, eventManager: IEventManager) {
        lightSharded(
            token = config.token,
            memberCachePolicy = MemberCachePolicy.NONE,
            chunkingFilter = ChunkingFilter.NONE,
            activityProvider = { Activity.customStatus("Booting up...") },
        ) {
            setStatus(OnlineStatus.DO_NOT_DISTURB)
            logger.info { "Booting up ${jda.shards.size} shards" }
        }
        if (Environment.isDbTest) {
            logger.info { "Running in database development mode. Testing database connection pool..." }
            testDatabaseConnectionPool()
        }
    }
}
