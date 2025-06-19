package me.diamondforge.kyromera.bot.services

import io.github.freya022.botcommands.api.core.service.annotations.BService
import io.github.oshai.kotlinlogging.KotlinLogging
import io.lettuce.core.RedisClient
import io.lettuce.core.RedisURI
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.async.RedisAsyncCommands
import io.lettuce.core.codec.StringCodec
import io.lettuce.core.pubsub.RedisPubSubListener
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection
import io.lettuce.core.support.AsyncConnectionPoolSupport
import io.lettuce.core.support.BoundedAsyncPool
import io.lettuce.core.support.BoundedPoolConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import me.diamondforge.kyromera.bot.configuration.Config
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

@BService
class RedisClientProvider(
    config: Config,
) {
    private val uri =
        config.redisConfig.let {
            RedisURI.Builder
                .redis(it.host, it.port)
                .apply {
                    if (it.password.isNotBlank()) {
                        withPassword(it.password.toCharArray())
                    }
                }.build()
        }

    private val client: RedisClient = RedisClient.create()
    private val json = Json { ignoreUnknownKeys = true }
    private val coroutineScope = CoroutineScope(Dispatchers.Default)
    private val pubSubConnections = ConcurrentHashMap<String, StatefulRedisPubSubConnection<String, String>>()

    private val pool: BoundedAsyncPool<StatefulRedisConnection<String, String>> =
        AsyncConnectionPoolSupport
            .createBoundedObjectPoolAsync(
                { client.connectAsync(StringCodec.UTF8, uri) },
                BoundedPoolConfig.create(),
            ).toCompletableFuture()
            .join()

    init {

        Runtime.getRuntime().addShutdownHook(
            Thread {
                runCatching {
                    pubSubConnections.forEach { (channel, connection) ->
                        try {
                            connection.closeAsync().toCompletableFuture().join()
                            logger.info { "Closed Redis Pub/Sub connection for channel: $channel" }
                        } catch (e: Exception) {
                            logger.warn(e) { "Error closing Redis Pub/Sub connection for channel: $channel" }
                        }
                    }

                    pool.closeAsync().toCompletableFuture().join()
                    client.shutdownAsync().toCompletableFuture().join()
                }.onSuccess {
                    logger.info { "Redis pool closed and client shutdown" }
                }.onFailure {
                    logger.warn(it) { "Error during Redis shutdown" }
                }
            },
        )

        logger.info { "Initialized Redis connection pool at ${uri.host}:${uri.port}" }

        runCatching {
            val connection = pool.acquire().toCompletableFuture().get()
            pool.release(connection)
            logger.info { "Successfully initialized Redis connection pool at ${uri.host}:${uri.port}" }
        }.onFailure {
            logger.error(it) { "Failed to initialize Redis pool at ${uri.host}:${uri.port}" }
        }
    }

    private suspend fun <T> withConnection(action: suspend (RedisAsyncCommands<String, String>) -> T): T {
        val connection = pool.acquire().await()
        try {
            return action(connection.async())
        } finally {
            pool.release(connection)
        }
    }

    suspend fun get(key: String): String? =
        runCatching { withConnection { it.get(key).await() } }
            .onFailure { logger.error(it) { "Failed to get key '$key'" } }
            .getOrNull()

    suspend fun set(
        key: String,
        value: String,
    ): Boolean =
        runCatching { withConnection { it.set(key, value).await() == "OK" } }
            .onFailure { logger.error(it) { "Failed to set key '$key'" } }
            .getOrDefault(false)

    suspend fun increaseXp(
        key: String,
        increment: Long,
    ): Boolean =
        runCatching { withConnection { it.incrby(key, increment).await() >= 0 } }
            .onFailure { logger.error(it) { "Failed to increase XP for key '$key' by $increment" } }
            .getOrDefault(false)

    suspend fun decreaseXp(
        key: String,
        decrement: Long,
    ): Boolean =
        runCatching { withConnection { it.decrby(key, decrement).await() >= 0 } }
            .onFailure { logger.error(it) { "Failed to decrease XP for key '$key' by $decrement" } }
            .getOrDefault(false)

    suspend fun setWithExpiry(
        key: String,
        value: String,
        ttlSeconds: Long,
    ): Boolean =
        runCatching { withConnection { it.setex(key, ttlSeconds, value).await() == "OK" } }
            .onFailure { logger.error(it) { "Failed to set key '$key' with expiry ($ttlSeconds s)" } }
            .getOrDefault(false)

    suspend fun delete(key: String): Boolean =
        runCatching { withConnection { it.del(key).await() == 1L } }
            .onFailure { logger.error(it) { "Failed to delete key '$key'" } }
            .getOrDefault(false)

    suspend fun <T> getTyped(
        key: String,
        serializer: KSerializer<T>,
    ): T? =
        runCatching {
            withConnection {
                it.get(key).await()?.let { json.decodeFromString(serializer, it) }
            }
        }.onFailure {
            logger.error(it) { "Failed to deserialize key '$key'" }
        }.getOrNull()

    suspend fun <T> setTyped(
        key: String,
        value: T,
        serializer: KSerializer<T>,
    ): Boolean =
        runCatching {
            val encoded = json.encodeToString(serializer, value)
            withConnection {
                it.set(key, encoded).await() == "OK"
            }
        }.onFailure {
            logger.error(it) { "Failed to serialize and set key '$key'" }
        }.getOrDefault(false)

    suspend fun <T> setTypedWithExpiry(
        key: String,
        value: T,
        ttlSeconds: Long,
        serializer: KSerializer<T>,
    ): Boolean =
        runCatching {
            val encoded = json.encodeToString(serializer, value)
            withConnection {
                it.setex(key, ttlSeconds, encoded).await() == "OK"
            }
        }.onFailure {
            logger.error(it) { "Failed to set key '$key' with expiry ($ttlSeconds s)" }
        }.getOrDefault(false)

    suspend fun getKeysByPattern(pattern: String): List<String> =
        runCatching {
            withConnection { it.keys(pattern).await() }
        }.onFailure {
            logger.error(it) { "Failed to get keys by pattern '$pattern'" }
        }.getOrDefault(emptyList())

    suspend fun getExpiry(key: String): Long? =
        runCatching {
            withConnection { it.ttl(key).await() }
        }.onFailure {
            logger.error(it) { "Failed to get expiry for key '$key'" }
        }.getOrNull()

    /**
     * Subscribes to a Redis channel and executes the provided callback function when a message is received.
     *
     * @param channel The channel to subscribe to
     * @param callback The function to execute when a message is received
     * @return True if the subscription was successful, false otherwise
     */
    fun subscribe(
        channel: String,
        callback: (String) -> Unit,
    ): Boolean {
        return runCatching {
            if (pubSubConnections.containsKey(channel)) {
                logger.warn { "Already subscribed to channel: $channel" }
                return true
            }

            val pubSubConnection = client.connectPubSub(StringCodec.UTF8, uri)
            pubSubConnections[channel] = pubSubConnection

            pubSubConnection.addListener(
                object : RedisPubSubListener<String, String> {
                    override fun message(
                        channel: String,
                        message: String,
                    ) {
                        logger.trace { "Received message on channel '$channel': $message" }
                        coroutineScope.launch {
                            try {
                                callback(message)
                            } catch (e: Exception) {
                                logger.error(e) { "Error in callback for message on channel '$channel'" }
                            }
                        }
                    }

                    override fun message(
                        pattern: String,
                        channel: String,
                        message: String,
                    ) {
                    }

                    override fun subscribed(
                        channel: String,
                        count: Long,
                    ) {
                        logger.info { "Subscribed to channel: $channel" }
                    }

                    override fun psubscribed(
                        pattern: String,
                        count: Long,
                    ) {
                    }

                    override fun unsubscribed(
                        channel: String,
                        count: Long,
                    ) {
                        logger.info { "Unsubscribed from channel: $channel" }
                    }

                    override fun punsubscribed(
                        pattern: String,
                        count: Long,
                    ) {
                    }
                },
            )

            val async = pubSubConnection.async()
            async.subscribe(channel).toCompletableFuture().join()

            true
        }.onFailure {
            logger.error(it) { "Failed to subscribe to channel: $channel" }
        }.getOrDefault(false)
    }

    /**
     * Unsubscribes from a Redis channel.
     *
     * @param channel The channel to unsubscribe from
     * @return True if the unsubscription was successful, false otherwise
     */
    fun unsubscribe(channel: String): Boolean {
        return runCatching {
            val connection =
                pubSubConnections[channel] ?: run {
                    logger.warn { "Not subscribed to channel: $channel" }
                    return true
                }

            connection
                .async()
                .unsubscribe(channel)
                .toCompletableFuture()
                .join()

            connection.closeAsync().toCompletableFuture().join()
            pubSubConnections.remove(channel)

            logger.info { "Unsubscribed from channel: $channel" }
            true
        }.onFailure {
            logger.error(it) { "Failed to unsubscribe from channel: $channel" }
        }.getOrDefault(false)
    }

    /**
     * Publishes a message to a Redis channel.
     *
     * @param channel The channel to publish to
     * @param message The message to publish
     * @return True if the message was published successfully, false otherwise
     */
    suspend fun publish(
        channel: String,
        message: String,
    ): Boolean =
        runCatching {
            withConnection {
                it.publish(channel, message).await() >= 0
            }
        }.onFailure {
            logger.error(it) { "Failed to publish message to channel: $channel" }
        }.getOrDefault(false)
}
