package me.diamondforge.kyromera.bot.models.database

import org.jetbrains.exposed.sql.*

object LevelingWhitelistedRoles : Table("leveling_whitelisted_roles") {
    val guildId = long("guildid")
    val roleId = long("roleid")
    
    override val primaryKey = PrimaryKey(guildId, roleId)
}