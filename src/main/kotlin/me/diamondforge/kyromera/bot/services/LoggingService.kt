package me.diamondforge.kyromera.bot.services

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import io.github.freya022.botcommands.api.core.service.annotations.BService
import io.github.oshai.kotlinlogging.KotlinLogging
import org.slf4j.LoggerFactory

/**
 * Service for managing and toggling log levels at runtime.
 * 
 * This service provides functionality to:
 * - Get the current log level for a specific logger
 * - Set a new log level for a specific logger
 * - Toggle between different log levels
 * - List all available loggers
 */
@BService
class LoggingService {
    private val logger = KotlinLogging.logger {}
    private val loggerContext = LoggerFactory.getILoggerFactory() as LoggerContext

    /**
     * Gets the current log level for a specific logger.
     *
     * @param loggerName The name of the logger to get the level for. If null, returns the root logger's level.
     * @return The current log level as a string, or "null" if the logger has no level set.
     */
    fun getLogLevel(loggerName: String? = null): String {
        val targetLogger = if (loggerName.isNullOrBlank()) {
            loggerContext.getLogger(Logger.ROOT_LOGGER_NAME)
        } else {
            loggerContext.getLogger(loggerName)
        }
        
        return targetLogger.level?.toString() ?: "null (inherits from parent)"
    }

    /**
     * Sets the log level for a specific logger.
     *
     * @param level The log level to set (TRACE, DEBUG, INFO, WARN, ERROR, or OFF)
     * @param loggerName The name of the logger to set the level for. If null, sets the root logger's level.
     * @return True if the level was set successfully, false otherwise.
     */
    fun setLogLevel(level: String, loggerName: String? = null): Boolean {
        val logLevel = try {
            Level.valueOf(level.uppercase())
        } catch (e: IllegalArgumentException) {
            logger.error { "Invalid log level: $level. Valid levels are: TRACE, DEBUG, INFO, WARN, ERROR, OFF" }
            return false
        }

        val targetLogger = if (loggerName.isNullOrBlank()) {
            loggerContext.getLogger(Logger.ROOT_LOGGER_NAME)
        } else {
            loggerContext.getLogger(loggerName)
        }

        targetLogger.level = logLevel
        logger.info { "Set log level for ${loggerName ?: "ROOT"} to $logLevel" }
        return true
    }

    /**
     * Toggles the log level for a specific logger between INFO and DEBUG.
     * If the current level is DEBUG, it will be set to INFO.
     * If the current level is anything else, it will be set to DEBUG.
     *
     * @param loggerName The name of the logger to toggle. If null, toggles the root logger.
     * @return The new log level after toggling.
     */
    fun toggleLogLevel(loggerName: String? = null): String {
        val targetLogger = if (loggerName.isNullOrBlank()) {
            loggerContext.getLogger(Logger.ROOT_LOGGER_NAME)
        } else {
            loggerContext.getLogger(loggerName)
        }

        val currentLevel = targetLogger.level
        val newLevel = when (currentLevel) {
            Level.DEBUG -> Level.INFO
            else -> Level.DEBUG
        }

        targetLogger.level = newLevel
        logger.info { "Toggled log level for ${loggerName ?: "ROOT"} from ${currentLevel ?: "null"} to $newLevel" }
        return newLevel.toString()
    }

    /**
     * Lists all available loggers and their current levels.
     *
     * @return A map of logger names to their current levels.
     */
    fun listLoggers(): Map<String, String> {
        return loggerContext.loggerList
            .filter { it.level != null || it.name == Logger.ROOT_LOGGER_NAME }
            .associate { it.name to (it.level?.toString() ?: "null (inherits from parent)") }
    }
}