package me.diamondforge.kyromera.bot.services

import io.github.freya022.botcommands.api.core.service.annotations.BService
import io.github.oshai.kotlinlogging.KotlinLogging
import io.lettuce.core.RedisClient
import io.lettuce.core.RedisURI
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.async.RedisAsyncCommands
import io.lettuce.core.codec.StringCodec
import io.lettuce.core.support.AsyncConnectionPoolSupport
import io.lettuce.core.support.BoundedAsyncPool
import io.lettuce.core.support.BoundedPoolConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import me.diamondforge.kyromera.bot.configuration.Config

private val logger = KotlinLogging.logger {}

@BService
class RedisClientProvider(config: Config) {
    private val uri = config.redisConfig.let {
        RedisURI.Builder.redis(it.host, it.port).apply {
            if (it.password.isNotBlank()) {
                withPassword(it.password.toCharArray())
            }
        }.build()
    }

    private val client: RedisClient = RedisClient.create().apply {
        
        options = io.lettuce.core.ClientOptions.builder()
            .disconnectedBehavior(io.lettuce.core.ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
            .autoReconnect(true)
            .socketOptions(io.lettuce.core.SocketOptions.builder()
                .connectTimeout(java.time.Duration.ofSeconds(5))
                .keepAlive(true)
                .build())
            .build()
    }
    private val json = Json { ignoreUnknownKeys = true }


    private val pool: BoundedAsyncPool<StatefulRedisConnection<String, String>> =
        AsyncConnectionPoolSupport
            .createBoundedObjectPoolAsync(
                { client.connectAsync(StringCodec.UTF8, uri) },
                BoundedPoolConfig.builder()
                    .maxTotal(16)  
                    .maxIdle(8)    
                    .minIdle(2)    
                    .build()
            ).toCompletableFuture().join()

    init {
        
        Runtime.getRuntime().addShutdownHook(Thread {
            runCatching {
                logger.info { "Shutting down Redis connection pool and client..." }
                pool.closeAsync().toCompletableFuture().join()
                client.shutdownAsync().toCompletableFuture().join()
            }.onSuccess {
                logger.info { "Redis pool closed and client shutdown successfully" }
            }.onFailure {
                logger.warn(it) { "Error during Redis shutdown" }
            }
        })

        logger.info { "Initialized Redis connection pool at ${uri.host}:${uri.port} with maxTotal=16, maxIdle=8, minIdle=2" }


        
        var success = false
        var attempts = 0
        val maxAttempts = 5

        while (!success && attempts < maxAttempts) {
            attempts++
            runCatching {
                logger.info { "Testing Redis connection (attempt $attempts/$maxAttempts)..." }
                val connection = pool.acquire().toCompletableFuture().get()
                pool.release(connection)
                success = true
                logger.info { "Successfully initialized and tested Redis connection pool at ${uri.host}:${uri.port}" }
            }.onFailure { e ->
                if (attempts < maxAttempts) {
                    val delayMs = 500L * attempts
                    logger.warn(e) { "Failed to test Redis connection (attempt $attempts/$maxAttempts), retrying in ${delayMs}ms..." }
                    Thread.sleep(delayMs) 
                } else {
                    logger.error(e) { "Failed to initialize Redis pool at ${uri.host}:${uri.port} after $maxAttempts attempts" }
                    logger.warn { "The application will continue, but Redis operations may fail until the connection is established" }
                }
            }
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

    suspend fun set(key: String, value: String): Boolean =
        runCatching { withConnection { it.set(key, value).await() == "OK" } }
            .onFailure { logger.error(it) { "Failed to set key '$key'" } }
            .getOrDefault(false)

    suspend fun setWithExpiry(key: String, value: String, ttlSeconds: Long): Boolean =
        runCatching { withConnection { it.setex(key, ttlSeconds, value).await() == "OK" } }
            .onFailure { logger.error(it) { "Failed to set key '$key' with expiry ($ttlSeconds s)" } }
            .getOrDefault(false)

    suspend fun delete(key: String, maxRetries: Int = 5): Boolean {
        var attempts = 0
        var lastException: Exception? = null

        while (attempts < maxRetries) {
            attempts++
            try {
                val result = withConnection { it.del(key).await() == 1L }
                if (result) {
                    if (attempts > 1) {
                        logger.info { "Successfully deleted key '$key' after $attempts attempts" }
                    }
                    return true
                } else {
                    
                    if (attempts > 1) {
                        logger.debug { "Key '$key' not found or already deleted (attempt $attempts/$maxRetries)" }
                    }
                    return false
                }
            } catch (e: Exception) {
                lastException = e
                logger.warn(e) { "Failed to delete key '$key' (attempt $attempts/$maxRetries)" }

                if (attempts < maxRetries) {
                    
                    val baseDelayMs = 100L
                    val exponentialFactor = 1 shl minOf(attempts, 3) 
                    val jitterMs = (Math.random() * baseDelayMs).toLong()
                    val delayMs = baseDelayMs * exponentialFactor + jitterMs

                    delay(delayMs)
                }
            }
        }

        
        logger.error(lastException) { "Failed to delete key '$key' after $maxRetries attempts" }
        return false
    }

    suspend fun <T> getTyped(key: String, serializer: KSerializer<T>, maxRetries: Int = 5): T? {
        var attempts = 0
        var lastException: Exception? = null

        while (attempts < maxRetries) {
            attempts++
            try {
                val result = withConnection {
                    it.get(key).await()?.let { json.decodeFromString(serializer, it) }
                }

                if (attempts > 1) {
                    logger.info { "Successfully retrieved and deserialized key '$key' after $attempts attempts" }
                }

                return result
            } catch (e: Exception) {
                lastException = e
                logger.warn(e) { "Failed to retrieve or deserialize key '$key' (attempt $attempts/$maxRetries)" }

                if (attempts < maxRetries) {
                    
                    val baseDelayMs = 100L
                    val exponentialFactor = 1 shl minOf(attempts, 3) 
                    val jitterMs = (Math.random() * baseDelayMs).toLong()
                    val delayMs = baseDelayMs * exponentialFactor + jitterMs

                    delay(delayMs)
                }
            }
        }

        
        logger.error(lastException) { "Failed to retrieve or deserialize key '$key' after $maxRetries attempts" }
        return null
    }

    suspend fun <T> setTyped(key: String, value: T, serializer: KSerializer<T>): Boolean =
        runCatching {
            val encoded = json.encodeToString(serializer, value)
            withConnection {
                it.set(key, encoded).await() == "OK"
            }
        }.onFailure {
            logger.error(it) { "Failed to serialize and set key '$key'" }
        }.getOrDefault(false)

    suspend fun <T> setTypedWithExpiry(key: String, value: T, ttlSeconds: Long, serializer: KSerializer<T>, maxRetries: Int = 5): Boolean {
        var attempts = 0
        var lastException: Exception? = null
        val encoded = json.encodeToString(serializer, value)

        while (attempts < maxRetries) {
            attempts++
            try {
                val result = withConnection {
                    it.setex(key, ttlSeconds, encoded).await() == "OK"
                }

                if (result) {
                    if (attempts > 1) {
                        logger.info { "Successfully set key '$key' with expiry after $attempts attempts" }
                    }
                    return true
                } else {
                    logger.warn { "Failed to set key '$key' with expiry (attempt $attempts/$maxRetries): operation returned false" }
                }
            } catch (e: Exception) {
                lastException = e
                logger.warn(e) { "Failed to set key '$key' with expiry (attempt $attempts/$maxRetries)" }

                if (attempts < maxRetries) {
                    
                    val baseDelayMs = 100L
                    val exponentialFactor = 1 shl minOf(attempts, 3) 
                    val jitterMs = (Math.random() * baseDelayMs).toLong()
                    val delayMs = baseDelayMs * exponentialFactor + jitterMs

                    delay(delayMs)
                }
            }
        }

        
        logger.error(lastException) { "Failed to set key '$key' with expiry after $maxRetries attempts" }
        return false
    }

    
    suspend fun sadd(key: String, value: String, maxRetries: Int = 5): Boolean {
        var attempts = 0
        var lastException: Exception? = null

        while (attempts < maxRetries) {
            attempts++
            try {
                val result = withConnection { it.sadd(key, value).await() > 0 }
                if (result) {
                    if (attempts > 1) {
                        logger.info { "Successfully added value to set '$key' after $attempts attempts" }
                    }
                    return true
                } else {
                    logger.warn { "Failed to add value to set '$key' (attempt $attempts/$maxRetries): operation returned false" }
                }
            } catch (e: Exception) {
                lastException = e
                logger.warn(e) { "Failed to add value to set '$key' (attempt $attempts/$maxRetries)" }

                if (attempts < maxRetries) {
                    
                    val baseDelayMs = 100L
                    val exponentialFactor = 1 shl minOf(attempts, 3) 
                    val jitterMs = (Math.random() * baseDelayMs).toLong()
                    val delayMs = baseDelayMs * exponentialFactor + jitterMs

                    delay(delayMs)
                }
            }
        }

        
        logger.error(lastException) { "Failed to add value to set '$key' after $maxRetries attempts" }
        return false
    }

    suspend fun smembers(key: String, maxRetries: Int = 5): Set<String> {
        var attempts = 0
        var lastException: Exception? = null

        while (attempts < maxRetries) {
            attempts++
            try {
                val result = withConnection { it.smembers(key).await().toSet() }
                if (attempts > 1) {
                    logger.info { "Successfully retrieved members from set '$key' after $attempts attempts" }
                }
                return result
            } catch (e: Exception) {
                lastException = e
                logger.warn(e) { "Failed to get members of set '$key' (attempt $attempts/$maxRetries)" }

                if (attempts < maxRetries) {
                    
                    val baseDelayMs = 100L
                    val exponentialFactor = 1 shl minOf(attempts, 3) 
                    val jitterMs = (Math.random() * baseDelayMs).toLong()
                    val delayMs = baseDelayMs * exponentialFactor + jitterMs

                    delay(delayMs)
                }
            }
        }

        
        logger.error(lastException) { "Failed to get members of set '$key' after $maxRetries attempts" }
        return emptySet()
    }

    suspend fun srem(key: String, value: String, maxRetries: Int = 5): Boolean {
        var attempts = 0
        var lastException: Exception? = null

        while (attempts < maxRetries) {
            attempts++
            try {
                val result = withConnection { it.srem(key, value).await() > 0 }
                if (result) {
                    if (attempts > 1) {
                        logger.info { "Successfully removed value from set '$key' after $attempts attempts" }
                    }
                    return true
                } else {
                    logger.warn { "Failed to remove value from set '$key' (attempt $attempts/$maxRetries): operation returned false" }
                }
            } catch (e: Exception) {
                lastException = e
                logger.warn(e) { "Failed to remove value from set '$key' (attempt $attempts/$maxRetries)" }

                if (attempts < maxRetries) {
                    
                    val baseDelayMs = 100L
                    val exponentialFactor = 1 shl minOf(attempts, 3) 
                    val jitterMs = (Math.random() * baseDelayMs).toLong()
                    val delayMs = baseDelayMs * exponentialFactor + jitterMs

                    delay(delayMs)
                }
            }
        }

        
        logger.error(lastException) { "Failed to remove value from set '$key' after $maxRetries attempts" }
        return false
    }

    
    suspend fun setnx(key: String, value: String, maxRetries: Int = 5): Boolean {
        var attempts = 0
        var lastException: Exception? = null

        while (attempts < maxRetries) {
            attempts++
            try {
                val result = withConnection { it.setnx(key, value).await() }

                
                if (result) {
                    if (attempts > 1) {
                        logger.info { "Successfully set key '$key' with NX after $attempts attempts" }
                    }
                } else {
                    
                    logger.debug { "Key '$key' already exists, could not set with NX" }
                }

                return result
            } catch (e: Exception) {
                lastException = e
                logger.warn(e) { "Failed to set key '$key' with NX (attempt $attempts/$maxRetries)" }

                if (attempts < maxRetries) {
                    
                    val baseDelayMs = 100L
                    val exponentialFactor = 1 shl minOf(attempts, 3) 
                    val jitterMs = (Math.random() * baseDelayMs).toLong()
                    val delayMs = baseDelayMs * exponentialFactor + jitterMs

                    delay(delayMs)
                }
            }
        }

        
        logger.error(lastException) { "Failed to set key '$key' with NX after $maxRetries attempts" }
        return false
    }

    suspend fun setnxWithExpiry(key: String, value: String, ttlSeconds: Long, maxRetries: Int = 5): Boolean {
        var attempts = 0
        var lastException: Exception? = null

        while (attempts < maxRetries) {
            attempts++
            try {
                val result = withConnection { 
                    
                    val setArgs = io.lettuce.core.SetArgs.Builder.nx().ex(ttlSeconds)
                    it.set(key, value, setArgs).await() == "OK"
                }

                
                if (result) {
                    if (attempts > 1) {
                        logger.info { "Successfully set key '$key' with NX and expiry after $attempts attempts" }
                    }
                } else {
                    
                    logger.debug { "Key '$key' already exists, could not set with NX" }
                }

                return result
            } catch (e: Exception) {
                lastException = e
                logger.warn(e) { "Failed to set key '$key' with NX and expiry (attempt $attempts/$maxRetries)" }

                if (attempts < maxRetries) {
                    
                    val baseDelayMs = 100L
                    val exponentialFactor = 1 shl minOf(attempts, 3) 
                    val jitterMs = (Math.random() * baseDelayMs).toLong()
                    val delayMs = baseDelayMs * exponentialFactor + jitterMs

                    delay(delayMs)
                }
            }
        }

        
        logger.error(lastException) { "Failed to set key '$key' with NX and expiry after $maxRetries attempts" }
        return false
    }
}
