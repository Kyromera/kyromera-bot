package me.diamondforge.kyromera.bot

import io.github.freya022.botcommands.api.core.JDAService
import io.github.freya022.botcommands.api.core.events.BReadyEvent
import io.github.freya022.botcommands.api.core.service.annotations.BService
import io.github.oshai.kotlinlogging.KotlinLogging
import me.diamondforge.kyromera.bot.configuration.Config
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
lateinit var jda: ShardManager


@BService
class Bot(private val config: Config) : JDAService() {
    override val intents: Set<GatewayIntent> =
        defaultIntents + GatewayIntent.GUILD_MEMBERS


    override val cacheFlags: Set<CacheFlag> = emptySet()


    override fun createJDA(event: BReadyEvent, eventManager: IEventManager) {
        val shardManager = DefaultShardManagerBuilder.createDefault(config.token, intents).apply {
            enableCache(cacheFlags)
            //setMemberCachePolicy(MemberCachePolicy.lru(5000).and(MemberCachePolicy.DEFAULT))
            setChunkingFilter(ChunkingFilter.NONE)
            setStatus(OnlineStatus.DO_NOT_DISTURB)
            setActivityProvider { Activity.playing("Booting up...") }
            setEventManagerProvider { eventManager }
        }.build()
        jda = shardManager
        logger.info { "Booting up ${shardManager.shards.size} shards" }
    }

}