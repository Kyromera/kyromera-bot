package me.diamondforge.kyromera.bot.runtimeTests.database

import io.github.oshai.kotlinlogging.KotlinLogging
import me.diamondforge.kyromera.bot.models.database.LevelingTimestamps
import me.diamondforge.kyromera.bot.models.database.LevelingUsers
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import kotlin.system.measureTimeMillis

private val logger = KotlinLogging.logger { }

fun testDatabaseConnectionPool() {
    logger.info { "=== DATABASE CONNECTION POOL TEST ===" }
    logger.info { "Starting database connection pool test..." }

    // Test 1: Sequential database operations
    logger.info { "TEST 1: Sequential database operations" }
    val iterations = 10
    val totalTime =
        measureTimeMillis {
            repeat(iterations) { iteration ->
                val operationTime =
                    measureTimeMillis {
                        transaction {
                            // Perform a simple database operation
                            val count = LevelingTimestamps.selectAll().count()
                            logger.info { "Test query #$iteration: Found $count timestamp records" }
                        }
                    }
                logger.info { "Database operation #$iteration completed in ${operationTime}ms" }
            }
        }

    val avgSequentialTime = totalTime / iterations
    logger.info { "Sequential test completed: $iterations operations in ${totalTime}ms (avg: ${avgSequentialTime}ms per operation)" }

    // Test 2: Parallel database connections
    logger.info { "TEST 2: Parallel database connections" }
    val numThreads = 5
    val parallelTime =
        measureTimeMillis {
            val threads =
                List(numThreads) { threadNum ->
                    Thread {
                        val threadTime =
                            measureTimeMillis {
                                transaction {
                                    val count = LevelingUsers.selectAll().count()
                                    logger.info { "Thread #$threadNum: Found $count user records" }
                                }
                            }
                        logger.info { "Thread #$threadNum completed in ${threadTime}ms" }
                    }.apply { start() }
                }

            threads.forEach { it.join() }
        }

    val avgParallelTime = parallelTime / numThreads
    logger.info { "Parallel test completed: $numThreads operations in ${parallelTime}ms (avg: ${avgParallelTime}ms per operation)" }

    // Test 3: Connection reuse
    logger.info { "TEST 3: Connection reuse efficiency" }
    val reuseTime =
        measureTimeMillis {
            // First batch of operations to warm up the pool
            repeat(5) {
                transaction {
                    LevelingTimestamps.selectAll().count()
                }
            }

            // Second batch to measure reuse efficiency
            repeat(5) { iteration ->
                val operationTime =
                    measureTimeMillis {
                        transaction {
                            val count = LevelingTimestamps.selectAll().count()
                            logger.info { "Reuse query #$iteration: Found $count timestamp records" }
                        }
                    }
                logger.info { "Reuse operation #$iteration completed in ${operationTime}ms" }
            }
        }

    logger.info { "Connection reuse test completed in ${reuseTime}ms" }

    // Test 4: Pool saturation (create more threads than max pool size)
    logger.info { "TEST 4: Pool saturation (exceeding pool size)" }
    val poolSize = 10 // This should match the maximumPoolSize in DatabaseSource
    val saturationThreads = poolSize + 5 // Exceed the pool size

    val saturationTime =
        measureTimeMillis {
            val threads =
                List(saturationThreads) { threadNum ->
                    Thread {
                        val threadTime =
                            measureTimeMillis {
                                transaction {
                                    // Simple query
                                    LevelingUsers.selectAll().count()
                                    logger.info { "Saturation thread #$threadNum executed query" }
                                }
                            }
                        logger.info { "Saturation thread #$threadNum completed in ${threadTime}ms" }
                    }.apply { start() }
                }

            threads.forEach { it.join() }
        }

    logger.info { "Pool saturation test completed: $saturationThreads threads in ${saturationTime}ms" }

    // Summary
    logger.info { "=== DATABASE CONNECTION POOL TEST SUMMARY ===" }
    logger.info { "Sequential operations: $iterations in ${totalTime}ms (avg: ${avgSequentialTime}ms)" }
    logger.info { "Parallel operations: $numThreads in ${parallelTime}ms (avg: ${avgParallelTime}ms)" }
    logger.info { "Connection reuse: completed in ${reuseTime}ms" }
    logger.info { "Pool saturation: $saturationThreads threads in ${saturationTime}ms" }

    val parallelEfficiency =
        if (avgSequentialTime > 0) (avgSequentialTime.toFloat() / avgParallelTime.toFloat()) else 0f
    logger.info { "Parallel efficiency factor: ${parallelEfficiency.toInt()}x (higher is better)" }
    logger.info { "=== TEST COMPLETED ===" }
}
