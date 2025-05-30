package me.diamondforge.kyromera.bot.models.database

import org.jetbrains.exposed.sql.*

object LevelingUsers : Table("leveling_users") {
    val guildId = long("guildid")
    val userId = long("userid")
    val xp = integer("xp").default(0)
    val level = integer("level").default(0)
    val pingActive = bool("pingactive").default(false)

    override val primaryKey = PrimaryKey(guildId, userId)
}