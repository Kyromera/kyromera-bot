package me.diamondforge.kyromera.bot.services

import io.github.freya022.botcommands.api.core.service.annotations.BService
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.*
import me.diamondforge.kyromera.bot.configuration.Config
import java.net.InetAddress
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

private val logger = KotlinLogging.logger {}


@BService
class ClusterCoordinator(
    private val config: Config,
    private val redisClientProvider: RedisClientProvider
) {
    companion object {
        private const val CLUSTER_REGISTRY_KEY = "kyromera:clusters"
        private const val CLUSTER_TTL_SECONDS = 60L
        private const val CLUSTER_HEARTBEAT_KEY_PREFIX = "kyromera:cluster:"
        private const val CLUSTER_LOCK_KEY_PREFIX = "kyromera:cluster:lock:"
        private const val HEARTBEAT_INTERVAL_SECONDS = 30L
        private const val LOCK_TIMEOUT_SECONDS = 10L
        private const val HEARTBEAT_STALE_MULTIPLIER = 4
    }

    private var clusterId: Int = 0
    private lateinit var clusterInfo: ClusterInfo
    private var shardIds: List<Int> = emptyList()
    private val isRunning = AtomicBoolean(true)
    private var heartbeatJob: Job = Job()
    private lateinit var cleanupJob: Job
    private val coroutineScope = CoroutineScope(Dispatchers.Default)
    private val isShardingEnabled: Boolean

    private val hostname: String by lazy {
        try {
            InetAddress.getLocalHost().hostName
        } catch (e: Exception) {
            logger.warn(e) { "Failed to get hostname, using 'unknown-host'" }
            "unknown-host"
        }
    }

    init {
        isShardingEnabled = config.shardingConfig != null

        if (isShardingEnabled) {
            runBlocking {
                try {
                    val cleanedUp = cleanupStaleRegistrations(forceCleanup = true)
                    if (cleanedUp > 0) {
                        logger.info { "Cleaned up $cleanedUp stale cluster registrations during initialization" }
                    }
                } catch (e: Exception) {
                    logger.warn(e) { "Error cleaning up stale registrations during initialization" }
                }
            }

            clusterId = determineClusterId()
            shardIds = calculateShardIds()

            clusterInfo = ClusterInfo(
                id = clusterId,
                hostname = hostname,
                shardIds = shardIds,
                startTime = System.currentTimeMillis()
            )

            registerCluster()
            heartbeatJob = startHeartbeat()

            Runtime.getRuntime().addShutdownHook(Thread {
                runBlocking {
                    shutdown().join()
                }
            })

            logger.info { "Initialized cluster $clusterId with shards $shardIds" }
        } else {
            logger.info { "Sharding is not enabled, ClusterCoordinator will use default values" }
            clusterId = 0
            shardIds = listOf(0)
        }
    }

    private fun determineClusterId(): Int {
        if (config.shardingConfig == null) {
            logger.info { "No sharding configuration provided, using default cluster ID 0" }
            return 0
        }

        config.shardingConfig.clusterId?.let { return it }

        return runBlocking {
            val cleanedUp = cleanupStaleRegistrations(forceCleanup = true)
            if (cleanedUp > 0) {
                logger.info { "Cleaned up $cleanedUp stale cluster registrations during startup" }
            }

            val instanceId = UUID.randomUUID().toString()
            val maxAttempts = 10

            for (attempt in 1..maxAttempts) {
                val shuffledIds = (0 until config.shardingConfig.totalClusters).shuffled()

                for (id in shuffledIds) {
                    try {
                        val existingClusters = getExistingClusters()
                        if (id in existingClusters) {
                            if (isClusterAlive(id)) {
                                logger.debug { "Cluster $id is already active, skipping" }
                                continue
                            } else {

                                logger.info { "Found stale registration for cluster $id, forcibly removing" }
                                if (forceRemoveCluster(id)) {
                                    logger.info { "Successfully removed stale cluster $id" }
                                } else {
                                    logger.warn { "Failed to forcibly remove stale cluster $id, skipping" }
                                    continue
                                }
                            }
                        }

                        val lockKey = "$CLUSTER_LOCK_KEY_PREFIX$id"
                        val lockValue = "$hostname:$instanceId:${System.currentTimeMillis()}"

                        if (redisClientProvider.setnxWithExpiry(
                                lockKey,
                                lockValue,
                                LOCK_TIMEOUT_SECONDS * 2,
                                maxRetries = 7
                            )
                        ) {
                            try {
                                if (id in getExistingClusters()) {
                                    logger.warn { "Cluster $id was registered by another instance after our check, skipping" }
                                    continue
                                }
                                var registered = false
                                for (registerAttempt in 1..3) {
                                    if (redisClientProvider.sadd(CLUSTER_REGISTRY_KEY, id.toString(), maxRetries = 7)) {
                                        registered = true
                                        break
                                    } else {
                                        logger.warn { "Failed to add cluster $id to registry set (attempt $registerAttempt/3)" }
                                        Thread.sleep(100L * registerAttempt)
                                    }
                                }

                                if (registered) {
                                    logger.info { "Successfully acquired cluster ID $id" }
                                    return@runBlocking id
                                } else {
                                    logger.warn { "Failed to add cluster $id to registry set after multiple attempts" }
                                }
                            } finally {

                                if (!redisClientProvider.delete(lockKey, maxRetries = 7)) {
                                    logger.warn { "Failed to release lock for cluster ID $id" }
                                }
                            }
                        } else {
                            logger.debug { "Failed to acquire lock for cluster ID $id, another instance may be trying to claim it" }
                        }
                    } catch (e: Exception) {
                        logger.warn(e) { "Error while trying to acquire cluster ID $id, skipping" }
                    }
                }

                if (attempt < maxAttempts) {
                    val baseDelayMs = 500L
                    val exponentialFactor = 1 shl minOf(attempt, 5)
                    val jitterMs = (Math.random() * baseDelayMs * 2).toLong()
                    val delayMs = baseDelayMs * exponentialFactor + jitterMs

                    logger.info { "All cluster IDs are currently busy, retrying in ${delayMs}ms (attempt $attempt/$maxAttempts)" }
                    delay(delayMs)


                    val moreCleaned = cleanupStaleRegistrations(forceCleanup = true)
                    if (moreCleaned > 0) {
                        logger.info { "Cleaned up $moreCleaned more stale cluster registrations" }
                    }
                }
            }



            logger.warn { "Could not acquire a cluster ID after $maxAttempts attempts, performing emergency cleanup" }

            val existingClusters = getExistingClusters()
            var emergencyCleaned = 0

            for (id in existingClusters) {
                if (forceRemoveCluster(id)) {
                    emergencyCleaned++
                }
            }

            if (emergencyCleaned > 0) {
                logger.warn { "Emergency cleanup removed $emergencyCleaned clusters, trying one more time" }


                val shuffledIds = (0 until config.shardingConfig.totalClusters).shuffled()
                for (id in shuffledIds) {
                    try {
                        val lockKey = "$CLUSTER_LOCK_KEY_PREFIX$id"
                        val lockValue = "$hostname:$instanceId:emergency:${System.currentTimeMillis()}"

                        if (redisClientProvider.setnxWithExpiry(
                                lockKey,
                                lockValue,
                                LOCK_TIMEOUT_SECONDS * 3,
                                maxRetries = 7
                            )
                        ) {
                            try {
                                if (redisClientProvider.sadd(CLUSTER_REGISTRY_KEY, id.toString(), maxRetries = 7)) {
                                    logger.info { "Successfully acquired cluster ID $id after emergency cleanup" }
                                    return@runBlocking id
                                }
                            } finally {
                                redisClientProvider.delete(lockKey, maxRetries = 7)
                            }
                        }
                    } catch (e: Exception) {
                        logger.error(e) { "Error during emergency cluster ID acquisition for $id" }
                    }
                }
            }


            throw IllegalStateException("No available cluster IDs. All ${config.shardingConfig.totalClusters} clusters are already running or Redis is not functioning correctly.")
        }
    }

    private fun calculateShardIds(): List<Int> {
        val totalShards = config.shardingConfig?.totalShards ?: 1
        val totalClusters = config.shardingConfig?.totalClusters ?: 1


        if (totalClusters == 1) {
            return (0 until totalShards).toList()
        }


        if (clusterId >= totalShards) {
            logger.warn { "Cluster $clusterId will handle no shards because there are more clusters than shards" }
            return emptyList()
        }


        val shardsPerCluster = totalShards / totalClusters
        val remainder = totalShards % totalClusters


        val startShardId = if (clusterId < remainder) {
            clusterId * (shardsPerCluster + 1)
        } else {
            remainder * (shardsPerCluster + 1) + (clusterId - remainder) * shardsPerCluster
        }

        val endShardId = if (clusterId < remainder) {
            startShardId + shardsPerCluster + 1
        } else {
            startShardId + shardsPerCluster
        }

        val shardIds = (startShardId until endShardId).toList()

        logger.info {
            "Cluster $clusterId will handle ${shardIds.size} shards out of $totalShards total " +
                    "(${shardIds.joinToString(", ")})"
        }

        return shardIds
    }

    private fun registerCluster(): Job {
        return coroutineScope.launch {
            try {

                var registered = false
                var attempts = 0
                val maxAttempts = 5
                var lastError: Exception? = null

                while (!registered && attempts < maxAttempts) {
                    attempts++

                    try {

                        val clusterKey = "$CLUSTER_HEARTBEAT_KEY_PREFIX$clusterId"
                        redisClientProvider.delete(clusterKey, maxRetries = 7)


                        val initialTtl = CLUSTER_TTL_SECONDS * 2
                        val success = redisClientProvider.setTypedWithExpiry(
                            clusterKey,
                            clusterInfo,
                            initialTtl,
                            ClusterInfo.serializer(),
                            maxRetries = 7
                        )

                        if (!success) {
                            logger.warn { "Failed to register cluster info in Redis (attempt $attempts/$maxAttempts)" }
                            throw RuntimeException("Failed to set cluster info in Redis")
                        }


                        val addedToSet =
                            redisClientProvider.sadd(CLUSTER_REGISTRY_KEY, clusterId.toString(), maxRetries = 5)
                        if (!addedToSet) {
                            logger.warn { "Failed to add cluster to registry set in Redis (attempt $attempts/$maxAttempts)" }


                            val existingClusters = getExistingClusters()
                            if (clusterId in existingClusters) {
                                logger.info { "Cluster $clusterId is already in registry set, continuing" }
                                registered = true
                            } else {

                                logger.info { "Trying one more time with longer timeout to add cluster to registry set" }
                                delay(500)
                                val finalAttempt =
                                    redisClientProvider.sadd(CLUSTER_REGISTRY_KEY, clusterId.toString(), maxRetries = 7)
                                if (finalAttempt) {
                                    logger.info { "Successfully added cluster to registry set on final attempt" }
                                    registered = true
                                } else {
                                    throw RuntimeException("Failed to add cluster to registry set after extended retries")
                                }
                            }
                        } else {
                            registered = true
                        }

                        if (registered) {
                            logger.info { "Registered cluster $clusterId in Redis (attempt $attempts/$maxAttempts)" }


                            val verifyInfo =
                                redisClientProvider.getTyped(clusterKey, ClusterInfo.serializer(), maxRetries = 7)
                            if (verifyInfo == null) {
                                logger.warn { "Could not verify cluster registration, will continue anyway" }
                            } else {
                                logger.debug { "Verified cluster registration: $verifyInfo" }
                            }
                        }
                    } catch (e: Exception) {
                        lastError = e
                        logger.warn(e) { "Error during cluster registration (attempt $attempts/$maxAttempts)" }

                        if (attempts < maxAttempts) {

                            val baseDelayMs = 200L
                            val exponentialFactor = 1 shl minOf(attempts, 4)
                            val jitterMs = (Math.random() * baseDelayMs).toLong()
                            val delayMs = baseDelayMs * exponentialFactor + jitterMs

                            logger.info { "Retrying cluster registration in ${delayMs}ms" }
                            delay(delayMs)
                        }
                    }
                }

                if (!registered) {
                    val errorMsg = "Failed to register cluster $clusterId in Redis after $maxAttempts attempts"
                    logger.error(lastError) { errorMsg }



                    logger.warn { "Continuing despite registration failure, will retry in heartbeat" }
                }


                cleanupJob = startPeriodicCleanup()

            } catch (e: Exception) {
                logger.error(e) { "Critical error registering cluster in Redis, will retry in heartbeat" }
            }
        }
    }

    private fun startPeriodicCleanup(): Job {
        return coroutineScope.launch {
            while (isRunning.get()) {
                try {

                    delay(60_000)


                    val existingClusters = getExistingClusters()
                    logger.debug { "Periodic cleanup starting, current clusters: $existingClusters" }


                    val cleanupCount = (System.currentTimeMillis() / 60_000) % 5
                    val forceCleanup = cleanupCount == 0L

                    if (forceCleanup) {
                        logger.info { "Running detailed periodic cleanup with force, current clusters: $existingClusters" }


                        for (id in existingClusters) {
                            try {
                                val clusterKey = "$CLUSTER_HEARTBEAT_KEY_PREFIX$id"
                                val clusterInfo =
                                    redisClientProvider.getTyped(clusterKey, ClusterInfo.serializer(), maxRetries = 7)
                                if (clusterInfo != null) {
                                    val heartbeatAge = System.currentTimeMillis() - clusterInfo.startTime
                                    val maxHeartbeatAge = CLUSTER_TTL_SECONDS * HEARTBEAT_STALE_MULTIPLIER * 1000
                                    logger.info { "Cluster $id health: age=${heartbeatAge / 1000}s, maxAge=${maxHeartbeatAge / 1000}s, host=${clusterInfo.hostname}" }


                                    if (heartbeatAge > maxHeartbeatAge * 0.75) {
                                        logger.warn { "Cluster $id heartbeat is getting old (${heartbeatAge / 1000}s > ${maxHeartbeatAge * 0.75 / 1000}s), monitoring closely" }
                                    }
                                } else {
                                    logger.warn { "Cluster $id has no valid info in Redis, will be cleaned up" }
                                }
                            } catch (e: Exception) {
                                logger.warn(e) { "Error checking health for cluster $id, treating as stale" }
                            }
                        }
                    }


                    val cleanedUp = cleanupStaleRegistrations(forceCleanup = forceCleanup)
                    if (cleanedUp > 0) {
                        logger.info { "Periodic cleanup: removed $cleanedUp stale cluster registrations" + (if (forceCleanup) " (force cleanup)" else "") }
                    } else {
                        logger.debug { "Periodic cleanup: no stale cluster registrations found" + (if (forceCleanup) " (force cleanup)" else "") }
                    }



                    if (cleanupCount == 0L && System.currentTimeMillis() / 60_000 % 15 == 0L) {
                        try {
                            val cleanedFromThisHost = cleanupRegistrationsFromThisHost()
                            if (cleanedFromThisHost > 0) {
                                logger.info { "Periodic cleanup: removed $cleanedFromThisHost cluster registrations from this host" }
                            }
                        } catch (e: Exception) {
                            logger.error(e) { "Error during cleanup of registrations from this host" }
                        }
                    }
                } catch (e: Exception) {
                    logger.error(e) { "Error during periodic cleanup of stale cluster registrations" }
                }
            }
        }
    }

    private suspend fun cleanupRegistrationsFromThisHost(): Int {
        var cleanedUp = 0
        val existingClusters = getExistingClusters()


        if (existingClusters.isEmpty()) {
            return 0
        }

        logger.debug { "Checking ${existingClusters.size} clusters for registrations from this host: $hostname" }

        for (id in existingClusters) {
            try {

                if (id == clusterId) {
                    continue
                }

                val clusterKey = "$CLUSTER_HEARTBEAT_KEY_PREFIX$id"
                val clusterInfo = redisClientProvider.getTyped(clusterKey, ClusterInfo.serializer(), maxRetries = 5)

                if (clusterInfo != null && clusterInfo.hostname == hostname) {
                    logger.info { "Found cluster $id registered from this host, cleaning up" }


                    if (forceRemoveCluster(id)) {
                        logger.info { "Successfully removed cluster $id from this host" }
                        cleanedUp++
                    } else {
                        logger.warn { "Failed to remove cluster $id from this host" }
                    }
                }
            } catch (e: Exception) {
                logger.warn(e) { "Error while checking cluster $id for this host" }
            }
        }

        return cleanedUp
    }

    private suspend fun getExistingClusters(): Set<Int> {
        return redisClientProvider.smembers(CLUSTER_REGISTRY_KEY)
            .mapNotNull { it.toIntOrNull() }
            .toSet()
    }

    private suspend fun isClusterAlive(clusterId: Int): Boolean {
        val clusterKey = "$CLUSTER_HEARTBEAT_KEY_PREFIX$clusterId"


        val clusterInfoStr = redisClientProvider.get(clusterKey)
        if (clusterInfoStr == null) {
            return false
        }



        try {
            val clusterInfo = redisClientProvider.getTyped(clusterKey, ClusterInfo.serializer(), maxRetries = 7)
            if (clusterInfo == null) {
                logger.warn { "Found invalid cluster info for cluster $clusterId, treating as stale" }
                return false
            }


            val maxHeartbeatAge = CLUSTER_TTL_SECONDS * HEARTBEAT_STALE_MULTIPLIER * 1000
            val heartbeatAge = System.currentTimeMillis() - clusterInfo.startTime
            if (heartbeatAge > maxHeartbeatAge) {
                logger.warn { "Cluster $clusterId heartbeat is too old (${heartbeatAge / 1000}s > ${maxHeartbeatAge / 1000}s), treating as stale" }
                return false
            }

            return true
        } catch (e: Exception) {
            logger.warn(e) { "Error parsing cluster info for cluster $clusterId, treating as stale" }
            return false
        }
    }

    private suspend fun cleanupStaleRegistrations(forceCleanup: Boolean = false): Int {
        var cleanedUp = 0
        val existingClusters = getExistingClusters()


        if (existingClusters.isEmpty()) {
            return 0
        }

        logger.debug { "Checking ${existingClusters.size} clusters for stale registrations" }

        for (id in existingClusters) {
            try {
                if (!isClusterAlive(id)) {

                    val clusterKey = "$CLUSTER_HEARTBEAT_KEY_PREFIX$id"
                    val lockKey = "$CLUSTER_LOCK_KEY_PREFIX$id"


                    val removedFromRegistry =
                        redisClientProvider.srem(CLUSTER_REGISTRY_KEY, id.toString(), maxRetries = 5)


                    var deletedHeartbeat = false
                    if (forceCleanup || removedFromRegistry) {
                        deletedHeartbeat = redisClientProvider.delete(clusterKey, maxRetries = 5)
                    }

                    if (removedFromRegistry) {
                        logger.info { "Cleaned up stale registration for cluster $id" }
                        cleanedUp++
                    } else if (forceCleanup) {

                        logger.warn { "Failed to remove cluster $id from registry set, trying again with force" }


                        delay(200)


                        val secondAttempt =
                            redisClientProvider.srem(CLUSTER_REGISTRY_KEY, id.toString(), maxRetries = 7)
                        if (secondAttempt) {
                            logger.info { "Successfully removed cluster $id from registry set on second attempt" }
                            cleanedUp++
                        } else {
                            logger.error { "Failed to remove cluster $id from registry set even with force cleanup" }
                        }
                    }

                    if (deletedHeartbeat) {
                        logger.info { "Deleted stale heartbeat key for cluster $id" }
                    } else if (forceCleanup) {

                        logger.warn { "Failed to delete heartbeat key for cluster $id, trying again" }


                        delay(200)


                        val secondAttempt = redisClientProvider.delete(clusterKey, maxRetries = 7)
                        if (secondAttempt) {
                            logger.info { "Successfully deleted heartbeat key for cluster $id on second attempt" }
                        } else {
                            logger.error { "Failed to delete heartbeat key for cluster $id even with force cleanup" }
                        }
                    }


                    val deletedLock = redisClientProvider.delete(lockKey, maxRetries = 5)
                    if (deletedLock) {
                        logger.info { "Cleaned up stale lock for cluster $id" }
                    } else if (forceCleanup) {

                        logger.warn { "Failed to delete lock key for cluster $id, trying again" }


                        delay(200)


                        val secondAttempt = redisClientProvider.delete(lockKey, maxRetries = 7)
                        if (secondAttempt) {
                            logger.info { "Successfully deleted lock key for cluster $id on second attempt" }
                        } else {
                            logger.error { "Failed to delete lock key for cluster $id even with force cleanup" }
                        }
                    }
                }
            } catch (e: Exception) {
                logger.warn(e) { "Error while checking if cluster $id is alive, treating as stale" }


                try {
                    if (redisClientProvider.srem(CLUSTER_REGISTRY_KEY, id.toString(), maxRetries = 5)) {
                        logger.info { "Cleaned up potentially stale registration for cluster $id after error" }
                        cleanedUp++
                    } else if (forceCleanup) {

                        logger.warn { "Failed to remove cluster $id from registry set after error, trying again with force" }


                        delay(300)


                        val secondAttempt =
                            redisClientProvider.srem(CLUSTER_REGISTRY_KEY, id.toString(), maxRetries = 7)
                        if (secondAttempt) {
                            logger.info { "Successfully removed cluster $id from registry set on second attempt after error" }
                            cleanedUp++
                        } else {
                            logger.error { "Failed to remove cluster $id from registry set even with force cleanup after error" }
                        }
                    }


                    if (forceCleanup) {
                        val clusterKey = "$CLUSTER_HEARTBEAT_KEY_PREFIX$id"
                        val lockKey = "$CLUSTER_LOCK_KEY_PREFIX$id"

                        redisClientProvider.delete(clusterKey, maxRetries = 5)
                        redisClientProvider.delete(lockKey, maxRetries = 5)
                    }
                } catch (e2: Exception) {
                    logger.error(e2) { "Failed to clean up potentially stale registration for cluster $id" }
                }
            }
        }

        return cleanedUp
    }

    private suspend fun forceRemoveCluster(clusterId: Int): Boolean {
        try {
            logger.warn { "Forcibly removing cluster $clusterId from registry" }

            var success = false
            var attempts = 0
            val maxAttempts = 3

            while (!success && attempts < maxAttempts) {
                attempts++
                try {

                    val removedFromRegistry = redisClientProvider.srem(
                        CLUSTER_REGISTRY_KEY,
                        clusterId.toString(),
                        maxRetries = 5
                    )


                    val clusterKey = "$CLUSTER_HEARTBEAT_KEY_PREFIX$clusterId"
                    val deletedHeartbeat = redisClientProvider.delete(clusterKey, maxRetries = 5)


                    val lockKey = "$CLUSTER_LOCK_KEY_PREFIX$clusterId"
                    val deletedLock = redisClientProvider.delete(lockKey, maxRetries = 5)


                    success = removedFromRegistry || deletedHeartbeat || deletedLock

                    if (success) {
                        if (attempts > 1) {
                            logger.info { "Successfully removed cluster $clusterId after $attempts attempts" }
                        }


                        if (removedFromRegistry) {
                            logger.info { "Successfully removed cluster $clusterId from registry set" }
                        }
                        if (deletedHeartbeat) {
                            logger.info { "Successfully deleted heartbeat key for cluster $clusterId" }
                        }
                        if (deletedLock) {
                            logger.info { "Successfully deleted lock key for cluster $clusterId" }
                        }
                    } else {
                        logger.warn { "Failed to remove cluster $clusterId (attempt $attempts/$maxAttempts)" }

                        if (attempts < maxAttempts) {

                            val baseDelayMs = 200L
                            val exponentialFactor = 1 shl minOf(attempts, 3)
                            val jitterMs = (Math.random() * baseDelayMs).toLong()
                            val delayMs = baseDelayMs * exponentialFactor + jitterMs

                            logger.info { "Retrying force removal of cluster $clusterId in ${delayMs}ms" }
                            delay(delayMs)
                        }
                    }
                } catch (e: Exception) {
                    logger.error(e) { "Error during force removal of cluster $clusterId (attempt $attempts/$maxAttempts)" }

                    if (attempts < maxAttempts) {

                        delay(300L * attempts)
                    }
                }
            }


            if (!success) {
                logger.warn { "All standard attempts to remove cluster $clusterId failed, trying emergency approach" }

                try {

                    val clusterKey = "$CLUSTER_HEARTBEAT_KEY_PREFIX$clusterId"
                    val lockKey = "$CLUSTER_LOCK_KEY_PREFIX$clusterId"


                    val finalRemoveFromRegistry = redisClientProvider.srem(
                        CLUSTER_REGISTRY_KEY,
                        clusterId.toString(),
                        maxRetries = 7
                    )

                    val finalDeleteHeartbeat = redisClientProvider.delete(clusterKey, maxRetries = 7)
                    val finalDeleteLock = redisClientProvider.delete(lockKey, maxRetries = 7)

                    success = finalRemoveFromRegistry || finalDeleteHeartbeat || finalDeleteLock

                    if (success) {
                        logger.info { "Emergency approach successfully removed cluster $clusterId" }
                    } else {
                        logger.error { "Emergency approach failed to remove cluster $clusterId" }
                    }
                } catch (e: Exception) {
                    logger.error(e) { "Error during emergency removal of cluster $clusterId" }
                }
            }

            return success
        } catch (e: Exception) {
            logger.error(e) { "Critical failure during force removal of cluster $clusterId" }
            return false
        }
    }

    fun getShardIds(): List<Int> = shardIds

    fun getClusterId(): Int = clusterId

    private fun startHeartbeat(): Job {
        return coroutineScope.launch {
            var consecutiveFailures = 0
            var emergencyMode = false

            while (isRunning.get()) {
                try {

                    val forceRegistration = consecutiveFailures >= 2 || emergencyMode

                    val success = sendHeartbeatWithRetry(
                        maxRetries = if (emergencyMode) 10 else 5,
                        forceRegistration = forceRegistration
                    )

                    if (success) {

                        if (consecutiveFailures > 0 || emergencyMode) {
                            logger.info {
                                "Heartbeat recovered after $consecutiveFailures consecutive failures" +
                                        if (emergencyMode) " (emergency mode)" else ""
                            }
                            consecutiveFailures = 0
                            emergencyMode = false
                        }


                        delay(HEARTBEAT_INTERVAL_SECONDS * 1000)
                    } else {

                        consecutiveFailures++


                        if (consecutiveFailures >= 5 && !emergencyMode) {
                            logger.warn { "Entering emergency heartbeat mode after $consecutiveFailures consecutive failures" }
                            emergencyMode = true


                            try {
                                val cleaned = cleanupStaleRegistrations(forceCleanup = true)
                                if (cleaned > 0) {
                                    logger.info { "Emergency cleanup removed $cleaned stale cluster registrations" }
                                }
                            } catch (e: Exception) {
                                logger.error(e) { "Failed to perform emergency cleanup" }
                            }
                        }


                        val backoffSeconds = minOf(30, 1 shl minOf(consecutiveFailures, 4))
                        val jitterMs = (Math.random() * 1000).toLong()
                        val delayMs = backoffSeconds * 1000L + jitterMs

                        val modeStr = if (emergencyMode) " (emergency mode)" else ""
                        logger.warn { "Heartbeat failed $consecutiveFailures times$modeStr, next attempt in ${delayMs / 1000.0} seconds" }
                        delay(delayMs)
                    }
                } catch (e: Exception) {

                    logger.error(e) { "Unexpected error in heartbeat loop" }


                    emergencyMode = true
                    consecutiveFailures++


                    delay(5000)
                }
            }
        }
    }

    private suspend fun sendHeartbeatWithRetry(maxRetries: Int = 5, forceRegistration: Boolean = false): Boolean {
        val clusterKey = "$CLUSTER_HEARTBEAT_KEY_PREFIX$clusterId"
        var lastException: Exception? = null

        for (attempt in 1..maxRetries) {
            try {

                val existingClusters = getExistingClusters()
                val inRegistry = clusterId in existingClusters

                if (!inRegistry && forceRegistration) {

                    logger.warn { "Cluster $clusterId not found in registry during heartbeat, attempting to re-register" }
                    val addedToSet =
                        redisClientProvider.sadd(CLUSTER_REGISTRY_KEY, clusterId.toString(), maxRetries = 7)
                    if (addedToSet) {
                        logger.info { "Re-added cluster $clusterId to registry set during heartbeat" }
                    } else {
                        logger.warn { "Failed to re-add cluster $clusterId to registry set during heartbeat" }
                    }
                }


                val updatedInfo = clusterInfo.copy(startTime = System.currentTimeMillis())


                val success = redisClientProvider.setTypedWithExpiry(
                    clusterKey,
                    updatedInfo,
                    CLUSTER_TTL_SECONDS,
                    ClusterInfo.serializer(),
                    maxRetries = 7
                )

                if (success) {
                    if (attempt > 1) {
                        logger.info { "Heartbeat sent successfully after $attempt attempts" }
                    } else {

                        val heartbeatCount = (System.currentTimeMillis() / (HEARTBEAT_INTERVAL_SECONDS * 1000)) % 10
                        if (heartbeatCount == 0L) {
                            logger.info { "Sent heartbeat for cluster $clusterId (startTime=${updatedInfo.startTime})" }
                        } else {
                            logger.debug { "Sent heartbeat for cluster $clusterId (startTime=${updatedInfo.startTime})" }
                        }
                    }
                    return true
                } else {
                    logger.warn { "Failed to send heartbeat (attempt $attempt/$maxRetries): Redis operation returned false" }


                    if (attempt == maxRetries && forceRegistration) {
                        logger.warn { "Last heartbeat attempt failed, trying emergency re-registration" }
                        try {

                            redisClientProvider.delete(clusterKey, maxRetries = 7)


                            val emergencySuccess = redisClientProvider.setTypedWithExpiry(
                                clusterKey,
                                updatedInfo,
                                CLUSTER_TTL_SECONDS * 2,
                                ClusterInfo.serializer(),
                                maxRetries = 7
                            )

                            if (emergencySuccess) {
                                logger.info { "Emergency re-registration successful" }
                                return true
                            }
                        } catch (e: Exception) {
                            logger.error(e) { "Emergency re-registration failed" }
                        }
                    }
                }
            } catch (e: Exception) {
                lastException = e
                if (attempt < maxRetries) {
                    logger.warn(e) { "Failed to send heartbeat (attempt $attempt/$maxRetries), retrying..." }


                    val baseDelayMs = 100L
                    val exponentialFactor = 1 shl minOf(attempt, 3)
                    val jitterMs = (Math.random() * baseDelayMs).toLong()
                    val retryDelayMs = baseDelayMs * exponentialFactor + jitterMs

                    delay(retryDelayMs)
                } else {
                    logger.error(e) { "Failed to send heartbeat after $maxRetries attempts" }
                }
            }
        }


        if (forceRegistration) {
            logger.error(lastException) { "All heartbeat attempts failed including emergency re-registration" }
        } else {

            logger.warn { "All heartbeat attempts failed, next attempt will try force registration" }
        }

        return false
    }

    fun shutdown(): Job {
        if (isRunning.compareAndSet(true, false)) {
            logger.info { "Starting shutdown of cluster coordinator for cluster $clusterId" }


            heartbeatJob.cancel()
            if (::cleanupJob.isInitialized) {
                cleanupJob.cancel()
            }


            return coroutineScope.launch {
                try {

                    var success = false
                    var attempts = 0
                    val maxAttempts = 5

                    while (!success && attempts < maxAttempts) {
                        attempts++
                        try {

                            val clusterKey = "$CLUSTER_HEARTBEAT_KEY_PREFIX$clusterId"
                            val lockKey = "$CLUSTER_LOCK_KEY_PREFIX$clusterId"


                            val removedFromRegistry = redisClientProvider.srem(
                                CLUSTER_REGISTRY_KEY,
                                clusterId.toString(),
                                maxRetries = 5
                            )


                            val deletedClusterInfo = redisClientProvider.delete(clusterKey, maxRetries = 5)


                            val deletedLock = redisClientProvider.delete(lockKey, maxRetries = 5)


                            success = removedFromRegistry && deletedClusterInfo

                            if (success) {
                                logger.info { "Successfully unregistered cluster $clusterId from Redis (attempt $attempts/$maxAttempts)" }
                            } else {

                                if (!removedFromRegistry) {
                                    logger.warn { "Failed to remove cluster $clusterId from registry set (attempt $attempts/$maxAttempts)" }
                                }
                                if (!deletedClusterInfo) {
                                    logger.warn { "Failed to delete cluster info for cluster $clusterId (attempt $attempts/$maxAttempts)" }
                                }

                                if (attempts < maxAttempts) {

                                    val baseDelayMs = 200L
                                    val exponentialFactor = 1 shl minOf(attempts, 3)
                                    val jitterMs = (Math.random() * baseDelayMs).toLong()
                                    val delayMs = baseDelayMs * exponentialFactor + jitterMs

                                    logger.info { "Retrying cluster unregistration in ${delayMs}ms" }
                                    delay(delayMs)
                                }
                            }

                            if (deletedLock) {
                                logger.debug { "Deleted lock for cluster $clusterId" }
                            } else {
                                logger.warn { "Failed to delete lock for cluster $clusterId (attempt $attempts/$maxAttempts)" }
                            }
                        } catch (e: Exception) {
                            logger.error(e) { "Error during cluster unregistration (attempt $attempts/$maxAttempts)" }

                            if (attempts < maxAttempts) {

                                val delayMs = 300L * (1 shl minOf(attempts, 3))
                                delay(delayMs)
                            }
                        }
                    }

                    if (!success) {
                        logger.error { "Failed to properly unregister cluster $clusterId after $maxAttempts attempts, trying emergency cleanup" }


                        try {

                            val clusterKey = "$CLUSTER_HEARTBEAT_KEY_PREFIX$clusterId"
                            val lockKey = "$CLUSTER_LOCK_KEY_PREFIX$clusterId"


                            val emergencyRemoveFromRegistry = redisClientProvider.srem(
                                CLUSTER_REGISTRY_KEY,
                                clusterId.toString(),
                                maxRetries = 7
                            )

                            val emergencyDeleteClusterInfo = redisClientProvider.delete(clusterKey, maxRetries = 7)
                            val emergencyDeleteLock = redisClientProvider.delete(lockKey, maxRetries = 7)

                            val emergencySuccess =
                                emergencyRemoveFromRegistry || emergencyDeleteClusterInfo || emergencyDeleteLock

                            if (emergencySuccess) {
                                logger.info { "Emergency cleanup partially succeeded for cluster $clusterId" }


                                if (emergencyRemoveFromRegistry) {
                                    logger.info { "Successfully removed cluster $clusterId from registry set during emergency cleanup" }
                                }
                                if (emergencyDeleteClusterInfo) {
                                    logger.info { "Successfully deleted cluster info for cluster $clusterId during emergency cleanup" }
                                }
                                if (emergencyDeleteLock) {
                                    logger.info { "Successfully deleted lock for cluster $clusterId during emergency cleanup" }
                                }
                            } else {
                                logger.error { "Emergency cleanup failed for cluster $clusterId" }
                            }
                        } catch (e: Exception) {
                            logger.error(e) { "Error during emergency cleanup" }
                        }


                        try {
                            logger.warn { "Attempting forced removal of cluster $clusterId as last resort" }
                            if (forceRemoveCluster(clusterId)) {
                                logger.info { "Forcibly removed cluster $clusterId during shutdown" }
                            } else {
                                logger.error { "Failed to forcibly remove cluster $clusterId during shutdown" }
                            }
                        } catch (e: Exception) {
                            logger.error(e) { "Error during forced cluster removal" }
                        }
                    }
                } catch (e: Exception) {
                    logger.error(e) { "Critical error during cluster shutdown" }


                    try {
                        forceRemoveCluster(clusterId)
                    } catch (e2: Exception) {
                        logger.error(e2) { "Final cleanup attempt failed during critical error handling" }
                    }
                } finally {
                    logger.info { "Completed shutdown of cluster coordinator for cluster $clusterId" }
                }
            }
        } else {

            logger.debug { "Shutdown already in progress for cluster $clusterId" }
            return coroutineScope.launch { }
        }
    }
}

@kotlinx.serialization.Serializable
data class ClusterInfo(
    val id: Int,
    val hostname: String,
    val shardIds: List<Int>,
    val startTime: Long
)
