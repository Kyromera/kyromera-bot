package me.diamondforge.kyromera.bot.models.database

import org.jetbrains.exposed.sql.*

object LevelingRoles : Table("leveling_roles") {
    val guildId = long("guildid")
    val roleId = long("roleid")
    val roleType = varchar("role_type", 16).check { it inList listOf("reward", "multiplier") }
    val level = integer("level").nullable()
    val multiplier = double("multiplier").nullable().check { it greaterEq 0.0 }

    override val primaryKey = PrimaryKey(guildId, roleId, roleType)

    init {
        index(false, guildId, roleType)
        index("idx_roles_multiplier", false, multiplier)
    }
}
