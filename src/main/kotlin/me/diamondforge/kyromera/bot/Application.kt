package me.diamondforge.kyromera.bot

import dev.reformator.stacktracedecoroutinator.jvm.DecoroutinatorJvmApi
import io.github.freya022.botcommands.api.core.BotCommands
import io.github.freya022.botcommands.api.core.config.DevConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import me.diamondforge.kyromera.bot.configuration.Config
import me.diamondforge.kyromera.bot.configuration.Environment
import me.diamondforge.kyromera.bot.services.ClusterCoordinator
import net.dv8tion.jda.api.interactions.DiscordLocale
import java.lang.management.ManagementFactory
import kotlin.io.path.absolutePathString
import kotlin.io.path.exists
import kotlin.system.exitProcess
import ch.qos.logback.classic.ClassicConstants as LogbackConstants

private val logger by lazy { KotlinLogging.logger {} }

private const val mainPackageName = "me.diamondforge.kyromera.bot"

object Main {
    @JvmStatic
    fun main(args: Array<out String>) {

        try {
            
            
            val customLogbackConfig = Environment.logbackConfigPath
            if (customLogbackConfig.exists()) {
                System.setProperty(
                    LogbackConstants.CONFIG_FILE_PROPERTY,
                    customLogbackConfig.absolutePathString()
                )
                logger.info { "Loading custom logback configuration at ${customLogbackConfig.absolutePathString()}" }
            } else {
                logger.info { "Using default logback configuration from resources" }
            }
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

            
            
            
            
            val shutdownHook = Thread {
                logger.info { "Application shutdown hook triggered, performing cleanup..." }

                try {
                    
                    
                    val botCommandsClass = Class.forName("io.github.freya022.botcommands.api.core.BotCommands")
                    val instanceField = botCommandsClass.getDeclaredField("INSTANCE")
                    instanceField.isAccessible = true
                    val botCommandsInstance = instanceField.get(null)

                    if (botCommandsInstance != null) {
                        
                        val getServiceProviderMethod = botCommandsClass.getDeclaredMethod("getServiceProvider")
                        getServiceProviderMethod.isAccessible = true
                        val serviceProvider = getServiceProviderMethod.invoke(botCommandsInstance)

                        if (serviceProvider != null) {
                            
                            val getServiceMethod = serviceProvider.javaClass.getDeclaredMethod("getService", Class::class.java)
                            getServiceMethod.isAccessible = true
                            val clusterCoordinator = getServiceMethod.invoke(serviceProvider, ClusterCoordinator::class.java) as? ClusterCoordinator

                            if (clusterCoordinator != null) {
                                logger.info { "Found ClusterCoordinator instance, shutting down..." }
                                
                                runBlocking {
                                    clusterCoordinator.shutdown().join()
                                }
                                logger.info { "ClusterCoordinator shutdown completed" }
                            } else {
                                logger.warn { "ClusterCoordinator instance not found, skipping cleanup" }
                            }
                        }
                    }
                } catch (e: Exception) {
                    logger.error(e) { "Error during application shutdown cleanup" }
                }

                logger.info { "Application shutdown hook completed" }
            }

            
            Runtime.getRuntime().addShutdownHook(shutdownHook)
            logger.info { "Added application-level shutdown hook" }

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
