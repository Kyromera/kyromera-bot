package me.diamondforge.kyromera.bot.models

import kotlinx.serialization.Serializable

@Serializable
data class RewardRole(
    val guildId: Long,
    val roleId: Long,
    val level: Int
)
