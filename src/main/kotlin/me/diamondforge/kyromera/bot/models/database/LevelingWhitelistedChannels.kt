package me.diamondforge.kyromera.bot.models.database

import org.jetbrains.exposed.sql.Table

/**
 * Legacy model for backward compatibility.
 * @deprecated Use LevelingFilteredChannels instead
 */
@Deprecated("Use LevelingFilteredChannels instead", ReplaceWith("LevelingFilteredChannels"))
typealias LevelingWhitelistedChannels = LevelingFilteredChannels
