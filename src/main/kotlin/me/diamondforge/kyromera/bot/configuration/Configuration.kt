package me.diamondforge.kyromera.bot.configuration

import com.fasterxml.jackson.module.kotlin.readValue
import io.github.freya022.botcommands.api.core.service.annotations.BService
import io.github.freya022.botcommands.api.core.utils.DefaultObjectMapper
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.exists
import kotlin.io.path.readText


data class DatabaseConfig(val serverName: String, val port: Int, val name: String, val user: String, val password: String) {
    val url: String
        get() = "jdbc:postgresql://$serverName:$port/$name"
}

data class Config(val token: String,
                  val ownerIds: List<Long>,
                  val testGuildIds: List<Long>,
                  val databaseConfig: DatabaseConfig
) {

    companion object {
        private val logger = KotlinLogging.logger { }

        private val configFilePath: Path = Environment.configFolder.resolve("config.json")
            .also {
                if (!it.exists()) {
                    logger.error { "Configuration file not found at ${it.absolutePathString()}" }
                    throw IllegalStateException("Configuration file not found")
                }
            }

        @get:BService
        val instance: Config by lazy {
            logger.info { "Loading configuration at ${configFilePath.absolutePathString()}" }

            return@lazy DefaultObjectMapper.mapper.readValue(configFilePath.readText())
        }
    }
}