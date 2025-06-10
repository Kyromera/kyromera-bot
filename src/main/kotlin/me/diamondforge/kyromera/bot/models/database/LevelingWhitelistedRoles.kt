package me.diamondforge.kyromera.bot.models.database

import org.jetbrains.exposed.sql.Table

/**
 * Legacy model for backward compatibility.
 * @deprecated Use LevelingFilteredRoles instead
 */
@Deprecated("Use LevelingFilteredRoles instead", ReplaceWith("LevelingFilteredRoles"))
typealias LevelingWhitelistedRoles = LevelingFilteredRoles
