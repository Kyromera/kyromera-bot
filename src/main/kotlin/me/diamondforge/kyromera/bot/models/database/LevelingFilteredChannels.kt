package me.diamondforge.kyromera.bot.models.database

import org.jetbrains.exposed.sql.Table

/**
 * Represents the table of filtered channels for the leveling system.
 * 
 * These channels are either whitelisted (only these channels earn XP) or blacklisted (all channels except these earn XP)
 * depending on the filter_mode setting in the leveling_settings table.
 */
object LevelingFilteredChannels : Table("leveling_filtered_channels") {
    val guildId = long("guildid")
    val channelId = long("channelid")

    override val primaryKey = PrimaryKey(guildId, channelId)
}