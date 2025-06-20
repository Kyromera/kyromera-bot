package me.diamondforge.kyromera.bot.configuration

import com.fasterxml.jackson.module.kotlin.readValue
import io.github.freya022.botcommands.api.core.service.annotations.BService
import io.github.freya022.botcommands.api.core.utils.DefaultObjectMapper
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.util.collections.StringMap
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.exists
import kotlin.io.path.readText


data class DatabaseConfig(
    val serverName: String,
    val port: Int,
    val name: String,
    val user: String,
    val password: String
) {
    val url: String
        get() = "jdbc:postgresql://$serverName:$port/$name"
}

data class RedisConfig(val host: String, val port: Int, val password: String)

data class RabbitMqConfig(val host: String, val port: Int, val user: String, val password: String)

data class Config(
    val token: String,
    val ownerIds: List<Long>,
    val testGuildIds: List<Long>,
    val databaseConfig: DatabaseConfig,
    val redisConfig: RedisConfig,
    val rabbitMqConfig: RabbitMqConfig,
) {

    companion object {
        private val logger = KotlinLogging.logger { }

        private val configFilePath: Path = Environment.configFolder.resolve("config.json")

        @get:BService
        val instance: Config by lazy {
            val source = System.getenv("CONFIG_SOURCE")?.lowercase()

            if (source == "env") {
                logger.info { "Loading configuration from environment variables" }
                return@lazy loadFromEnv()
            }

            if (!configFilePath.exists()) {
                logger.error { "Configuration file not found at ${configFilePath.absolutePathString()}" }
                throw IllegalStateException("Configuration file not found")
            }

            logger.info { "Loading configuration from file at ${configFilePath.absolutePathString()}" }
            try {
                val configText = configFilePath.readText().trim()
                if (configText.isEmpty()) {
                    logger.error { "Configuration file is empty at ${configFilePath.absolutePathString()}" }
                    throw IllegalStateException("Configuration file is empty")
                }
                return@lazy DefaultObjectMapper.mapper.readValue<Config>(configText)
            } catch (e: Exception) {
                logger.error(e) { "Failed to load configuration from file: ${e.message}" }
                throw IllegalStateException("Could not parse configuration file", e)
            }
        }

        private fun loadFromEnv(): Config {
            val token = System.getenv("BOT_TOKEN") ?: throw IllegalStateException("Missing BOT_TOKEN")
            val ownerIds = System.getenv("OWNER_IDS")?.replace(" ", "")?.split(",")?.map { it.toLong() }
                ?: throw IllegalStateException("Missing OWNER_IDS")
            val testGuildIds = System.getenv("TEST_GUILD_IDS")?.replace(" ", "")?.split(",")?.map { it.toLong() }
                ?: throw IllegalStateException("Missing TEST_GUILD_IDS")

            val dbServer = System.getenv("POSTGRES_HOST") ?: throw IllegalStateException("Missing POSTGRES_HOST")
            val dbPort = System.getenv("POSTGRES_PORT")?.toIntOrNull() ?: 5432
            val dbName = System.getenv("POSTGRES_DB") ?: throw IllegalStateException("Missing POSTGRES_DB")
            val dbUser = System.getenv("POSTGRES_USER") ?: throw IllegalStateException("Missing POSTGRES_USER")
            val dbPassword =
                System.getenv("POSTGRES_PASSWORD") ?: throw IllegalStateException("Missing POSTGRES_PASSWORD")

            val redisHost = System.getenv("REDIS_HOST") ?: throw IllegalStateException("Missing REDIS_HOST")
            val redisPort = System.getenv("REDIS_PORT")?.toIntOrNull() ?: 6379
            val redisPassword = System.getenv("REDIS_PASSWORD") ?: throw IllegalStateException("Missing REDIS_PASSWORD")

            val rabbitMqHost = System.getenv("RABBITMQ_HOST") ?: throw IllegalStateException("Missing RABBITMQ_HOST")
            val rabbitMqPort = System.getenv("RABBITMQ_PORT")?.toIntOrNull() ?: 5672
            val rabbitMqUser = System.getenv("RABBITMQ_USER") ?: throw IllegalStateException("Missing RABBITMQ_USER")
            val rabbitMqPassword = System.getenv("RABBITMQ_PASSWORD") ?: throw IllegalStateException("Missing RABBITMQ_PASSWORD")

            val redisConfig = RedisConfig(redisHost, redisPort, redisPassword)

            val databaseConfig = DatabaseConfig(dbServer, dbPort, dbName, dbUser, dbPassword)

            val rabbitMqConfig = RabbitMqConfig(rabbitMqHost, rabbitMqPort, rabbitMqUser, rabbitMqPassword)

            return Config(token, ownerIds, testGuildIds, databaseConfig, redisConfig, rabbitMqConfig)
        }
    }

}