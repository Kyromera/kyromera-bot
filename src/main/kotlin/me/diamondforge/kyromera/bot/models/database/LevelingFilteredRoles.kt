package me.diamondforge.kyromera.bot.models.database

import org.jetbrains.exposed.sql.Table

/**
 * Represents the table of filtered roles for the leveling system.
 * 
 * These roles are either whitelisted (only users with these roles earn XP) or blacklisted (all users except those with these roles earn XP)
 * depending on the filter_mode setting in the leveling_settings table.
 */
object LevelingFilteredRoles : Table("leveling_filtered_roles") {
    val guildId = long("guildid")
    val roleId = long("roleid")

    override val primaryKey = PrimaryKey(guildId, roleId)
}