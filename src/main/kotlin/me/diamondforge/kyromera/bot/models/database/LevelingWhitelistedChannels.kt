package me.diamondforge.kyromera.bot.models.database

import org.jetbrains.exposed.sql.*

object LevelingWhitelistedChannels : Table("leveling_whitelisted_channels") {
    val guildId = long("guildid")
    val channelId = long("channelid")
    
    override val primaryKey = PrimaryKey(guildId, channelId)
}