package me.diamondforge.kyromera.bot.models

import kotlinx.serialization.Serializable

@Serializable
data class CachedXp(
    val guildId: Long,
    val userId: Long,
    val xp: Int,
    val lastUpdated: Long = System.currentTimeMillis(),
)
