package me.diamondforge.kyromera.bot

import dev.reformator.stacktracedecoroutinator.jvm.DecoroutinatorJvmApi
import io.github.freya022.botcommands.api.core.BotCommands
import io.github.freya022.botcommands.api.core.config.DevConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import me.diamondforge.kyromera.bot.configuration.Config
import me.diamondforge.kyromera.bot.configuration.Environment
import net.dv8tion.jda.api.interactions.DiscordLocale
import java.lang.management.ManagementFactory
import kotlin.io.path.absolutePathString
import kotlin.system.exitProcess
import ch.qos.logback.classic.ClassicConstants as LogbackConstants

private val logger by lazy { KotlinLogging.logger {} }

private const val mainPackageName = "me.diamondforge.kyromera.bot"

object Main {
    @JvmStatic
    fun main(args: Array<out String>) {
        try {
            System.setProperty(
                LogbackConstants.CONFIG_FILE_PROPERTY,
                Environment.logbackConfigPath.absolutePathString(),
            )
            logger.info { "Loading logback configuration at ${Environment.logbackConfigPath.absolutePathString()}" }
            if ("-XX:+AllowEnhancedClassRedefinition" in ManagementFactory.getRuntimeMXBean().inputArguments) {
                logger.info { "Skipping stacktrace-decoroutinator as enhanced hotswap is active" }
            } else if ("--no-decoroutinator" in args) {
                logger.info { "Skipping stacktrace-decoroutinator as --no-decoroutinator is specified" }
            } else {
                DecoroutinatorJvmApi.install()
                logger.info { "Enabled stacktrace-decoroutinator" }
            }
            logger.info { "Running on Java ${System.getProperty("java.version")}" }

            val config = Config.instance
            if (Environment.isDev) {
                logger.warn { "Running in development mode" }
            }

            BotCommands.create {
                if (Environment.isDev) {
                    disableExceptionsInDMs = true
                }

                addPredefinedOwners(config.ownerIds)

                addSearchPath(mainPackageName)

                applicationCommands {
                    testGuildIds += config.testGuildIds
                }

                applicationCommands {
                    @OptIn(DevConfig::class)
                    disableAutocompleteCache = Environment.isDev
                    fileCache {
                        @OptIn(DevConfig::class)
                        checkOnline = Environment.isDev
                    }
                    testGuildIds += config.testGuildIds
                    addLocalizations("Commands", DiscordLocale.GERMAN)
                }

                components {
                    enable = true
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Unable to start the bot" }
            exitProcess(1)
        }
    }
}
