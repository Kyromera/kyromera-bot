package me.diamondforge.kyromera.bot.models.database

import org.jetbrains.exposed.sql.Table

object LevelingTimestamps : Table("leveling_timestamps") {
    val guildId = long("guildid")
    val userId = long("userid")
    val type = varchar("type", 16).check { it inList listOf("text", "vc") }
    val lastReward = long("last_reward").default(0)

    override val primaryKey = PrimaryKey(guildId, userId, type)
}