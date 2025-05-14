package me.diamondforge.kyromera.bot.services

import io.github.freya022.botcommands.api.core.service.annotations.BService
import io.github.oshai.kotlinlogging.KotlinLogging
import io.lettuce.core.RedisClient
import io.lettuce.core.RedisURI
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.reactive.RedisReactiveCommands
import kotlinx.coroutines.reactive.awaitFirstOrNull
import me.diamondforge.kyromera.bot.configuration.Config
import kotlinx.serialization.json.Json

val logger = KotlinLogging.logger {}

@BService
class RedisClientProvider(config: Config) {
    private val redisConfig = config.redisConfig
    private val uri = RedisURI.Builder.redis(redisConfig.host, redisConfig.port).apply {
        if (redisConfig.password.isNotBlank()) {
            withPassword(redisConfig.password.toCharArray())
        }
    }.build()

    private val client: RedisClient = RedisClient.create(uri)
    private var connection: StatefulRedisConnection<String, String> = client.connect()
    var reactive: RedisReactiveCommands<String, String> = connection.reactive()

    init {
        logger.info { "Connected to Redis at ${redisConfig.host}:${redisConfig.port}" }

        Runtime.getRuntime().addShutdownHook(Thread {
            runCatching {
                connection.close()
                client.shutdown()
            }.onSuccess {
                logger.info { "Redis connection closed" }
            }.onFailure {
                logger.warn(it) { "Error during Redis shutdown" }
            }
        })
    }

    suspend fun get(key: String): String? =
        runCatching { reactive.get(key).awaitFirstOrNull() }
            .onFailure { logger.error(it) { "Failed to get key '$key'" } }
            .getOrNull()

    suspend fun set(key: String, value: String): Boolean =
        runCatching { reactive.set(key, value).awaitFirstOrNull() == "OK" }
            .onFailure { logger.error(it) { "Failed to set key '$key'" } }
            .getOrDefault(false)

    suspend fun setWithExpiry(key: String, value: String, ttlSeconds: Long): Boolean =
        runCatching { reactive.setex(key, ttlSeconds, value).awaitFirstOrNull() == "OK" }
            .onFailure { logger.error(it) { "Failed to set key '$key' with expiry ($ttlSeconds s)" } }
            .getOrDefault(false)

    suspend fun isAlive(): Boolean =
        runCatching { reactive.ping().awaitFirstOrNull() == "PONG" }
            .getOrDefault(false)

    fun reconnect() {
        runCatching {
            connection.close()
            connection = client.connect()
            reactive = connection.reactive()
        }.onSuccess {
            logger.info { "Reconnected to Redis" }
        }.onFailure {
            logger.error(it) { "Failed to reconnect to Redis" }
        }
    }

    val json = Json { ignoreUnknownKeys = true }

    suspend inline fun <reified T> getTyped(key: String): T? =
        runCatching {
            reactive.get(key).awaitFirstOrNull()?.let { json.decodeFromString<T>(it) }
        }.onFailure {
            logger.error(it) { "Failed to deserialize key '$key' to ${T::class.simpleName}" }
        }.getOrNull()

    suspend inline fun <reified T> setTyped(key: String, value: T): Boolean =
        runCatching {
            val encoded = json.encodeToString(value)
            reactive.set(key, encoded).awaitFirstOrNull() == "OK"
        }.onFailure {
            logger.error(it) { "Failed to serialize and set key '$key'" }
        }.getOrDefault(false)


    suspend inline fun <reified T> setTypedWithExpiry(
        key: String,
        value: T,
        ttlSeconds: Long
    ): Boolean =
        runCatching {
            val encoded = json.encodeToString(value)
            reactive.setex(key, ttlSeconds, encoded).awaitFirstOrNull() == "OK"
        }.onFailure {
            logger.error(it) { "Failed to set key '$key' with expiry ($ttlSeconds s)" }
        }.getOrDefault(false)


}
