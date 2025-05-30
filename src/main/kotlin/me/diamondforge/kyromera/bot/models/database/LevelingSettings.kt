package me.diamondforge.kyromera.bot.models.database

import org.jetbrains.exposed.sql.Table

object LevelingSettings : Table("leveling_settings") {
    val guildId = long("guildid").uniqueIndex()
    val textEnabled = bool("text_enabled").default(true)
    val vcEnabled = bool("vc_enabled").default(true)
    val textMulti = double("text_multi").default(1.0).check { it greaterEq 0.0 }
    val vcMulti = double("vc_multi").default(1.0).check { it greaterEq 0.0 }
    val levelupChannel = long("levelup_channel").nullable()
    val levelupMessage = text("levelup_message").nullable()
    val levelupMessageReward = text("levelup_message_reward").nullable()
    val retainRoles = bool("retain_roles").default(false)
    val lastRecalc = long("last_recalc").default(0)
    val whitelistMode = bool("whitelist_mode").default(false)
    val levelupAnnounceMode = varchar("levelup_announce_mode", 16).default("current").check {
        it inList listOf("disabled", "current", "dm", "custom")
    }

    override val primaryKey = PrimaryKey(guildId)
}