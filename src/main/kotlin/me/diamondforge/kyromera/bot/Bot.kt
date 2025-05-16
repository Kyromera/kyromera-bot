package me.diamondforge.kyromera.bot

import io.github.freya022.botcommands.api.core.JDAService
import io.github.freya022.botcommands.api.core.events.BReadyEvent
import io.github.freya022.botcommands.api.core.service.annotations.BService
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import me.diamondforge.kyromera.bot.configuration.Config
import me.diamondforge.kyromera.bot.services.ClusterCoordinator
import net.dv8tion.jda.api.OnlineStatus
import net.dv8tion.jda.api.entities.Activity
import net.dv8tion.jda.api.hooks.IEventManager
import net.dv8tion.jda.api.requests.GatewayIntent
import net.dv8tion.jda.api.sharding.DefaultShardManagerBuilder
import net.dv8tion.jda.api.sharding.ShardManager
import net.dv8tion.jda.api.utils.ChunkingFilter
import net.dv8tion.jda.api.utils.cache.CacheFlag
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException


private val logger by lazy { KotlinLogging.logger {} }
lateinit var jda: ShardManager


@BService
class Bot(
    private val config: Config,
    private val clusterCoordinator: ClusterCoordinator
) : JDAService() {
    override val intents: Set<GatewayIntent> =
        defaultIntents + GatewayIntent.GUILD_MEMBERS


    override val cacheFlags: Set<CacheFlag> = emptySet()

    
    init {
        
        Runtime.getRuntime().addShutdownHook(Thread {
            logger.info { "Bot shutdown hook triggered, shutting down JDA and ClusterCoordinator..." }

            try {
                
                if (::jda.isInitialized) {
                    logger.info { "Shutting down JDA..." }
                    jda.shutdown()

                    
                    try {
                        
                        
                        logger.info { "Waiting for JDA to shutdown (max 10 seconds)..." }
                        Thread.sleep(10000)
                        logger.info { "JDA shutdown wait completed" }
                    } catch (e: Exception) {
                        logger.error(e) { "Error waiting for JDA to shutdown" }
                    }
                }

                
                logger.info { "Shutting down ClusterCoordinator..." }
                runBlocking {
                    try {
                        val job = clusterCoordinator.shutdown()
                        job.join()
                        logger.info { "ClusterCoordinator shutdown completed successfully" }
                    } catch (e: Exception) {
                        logger.error(e) { "Error shutting down ClusterCoordinator" }
                    }
                }
            } catch (e: Exception) {
                logger.error(e) { "Error during Bot shutdown" }
            }

            logger.info { "Bot shutdown hook completed" }
        })

        logger.info { "Added Bot shutdown hook" }
    }


    override fun createJDA(event: BReadyEvent, eventManager: IEventManager) {
        val shardIds = clusterCoordinator.getShardIds()
        val clusterId = clusterCoordinator.getClusterId()

        
        
        val delayMs = clusterId * 10_000L
        if (delayMs > 0) {
            logger.info { "Cluster $clusterId waiting for ${delayMs/1000} seconds before initializing to avoid rate limits" }
            runBlocking { delay(delayMs) }
        }

        logger.info { "Initializing cluster $clusterId with shards $shardIds" }

        jda = DefaultShardManagerBuilder.createLight(config.token, intents).apply {
            enableCache(cacheFlags)
            
            setChunkingFilter(ChunkingFilter.NONE)
            setStatus(OnlineStatus.DO_NOT_DISTURB)
            setActivity(Activity.playing("Cluster $clusterId"))
            setEventManagerProvider { eventManager }

            
            setShardsTotal(config.shardingConfig.totalShards)
            setShards(shardIds)

            
            
        }.build()

        logger.info { "Cluster $clusterId is running with ${jda.shards.size} shards: $shardIds" }

        
        jda.setStatus(OnlineStatus.ONLINE)
    }

        fun shutdown(): Job {
        logger.info { "Shutting down Bot..." }

        
        if (::jda.isInitialized) {
            logger.info { "Shutting down JDA..." }
            jda.shutdown()

            
            try {
                
                
                logger.info { "Waiting for JDA to shutdown (max 10 seconds)..." }
                Thread.sleep(10000)
                logger.info { "JDA shutdown wait completed" }
            } catch (e: Exception) {
                logger.error(e) { "Error waiting for JDA to shutdown" }
            }
        }

        
        logger.info { "Shutting down ClusterCoordinator..." }
        return clusterCoordinator.shutdown()
    }
}
