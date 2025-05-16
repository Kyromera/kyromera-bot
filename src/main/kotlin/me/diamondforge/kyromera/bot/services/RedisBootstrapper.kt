package me.diamondforge.kyromera.bot.services

import io.github.freya022.botcommands.api.core.service.annotations.BService
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import me.diamondforge.kyromera.bot.configuration.Config
import java.net.InetAddress
import java.util.*

private val logger = KotlinLogging.logger {}

@BService
class RedisBootstrapper(
    private val config: Config,
    private val redisClientProvider: RedisClientProvider
) {
    companion object {
        private const val CLUSTER_REGISTRY_KEY = "kyromera:clusters"
        private const val CLUSTER_HEARTBEAT_KEY_PREFIX = "kyromera:cluster:"
        private const val CLUSTER_LOCK_KEY_PREFIX = "kyromera:cluster:lock:"
        private const val HEARTBEAT_STALE_MULTIPLIER = 4
        private const val CLUSTER_TTL_SECONDS = 60L


        private val INSTANCE_ID = UUID.randomUUID().toString()
    }


    private val hostname: String by lazy {
        try {
            InetAddress.getLocalHost().hostName
        } catch (e: Exception) {
            logger.warn(e) { "Failed to get hostname, using 'unknown-host'" }
            "unknown-host"
        }
    }

    init {
        logger.info { "Initializing Redis bootstrapper for host: $hostname, instance: $INSTANCE_ID" }

        runBlocking {
            try {

                val cleanedUp = cleanupStaleRegistrations(forceCleanup = true)
                if (cleanedUp > 0) {
                    logger.info { "Bootstrapper cleaned up $cleanedUp stale cluster registrations" }
                } else {
                    logger.info { "No stale cluster registrations found during bootstrap" }
                }


                val cleanedFromThisHost = cleanupRegistrationsFromThisHost()
                if (cleanedFromThisHost > 0) {
                    logger.info { "Bootstrapper cleaned up $cleanedFromThisHost cluster registrations from this host" }
                }
            } catch (e: Exception) {
                logger.error(e) { "Error during Redis bootstrap cleanup" }
            }
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
                        logger.info { "Bootstrapper cleaned up stale registration for cluster $id" }
                        cleanedUp++
                    } else if (forceCleanup) {

                        logger.warn { "Failed to remove cluster $id from registry set, trying again with force" }


                        kotlinx.coroutines.delay(200)


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


                        kotlinx.coroutines.delay(200)


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


                        kotlinx.coroutines.delay(200)


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


                        kotlinx.coroutines.delay(300)


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

    private suspend fun cleanupRegistrationsFromThisHost(): Int {
        var cleanedUp = 0
        val existingClusters = getExistingClusters()


        if (existingClusters.isEmpty()) {
            return 0
        }

        logger.debug { "Checking ${existingClusters.size} clusters for registrations from this host: $hostname" }

        for (id in existingClusters) {
            try {
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
            val clusterInfo = redisClientProvider.getTyped(clusterKey, ClusterInfo.serializer(), maxRetries = 5)
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
                            kotlinx.coroutines.delay(delayMs)
                        }
                    }
                } catch (e: Exception) {
                    logger.error(e) { "Error during force removal of cluster $clusterId (attempt $attempts/$maxAttempts)" }

                    if (attempts < maxAttempts) {

                        kotlinx.coroutines.delay(300L * attempts)
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
}