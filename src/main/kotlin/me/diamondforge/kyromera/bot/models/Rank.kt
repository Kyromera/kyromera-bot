package me.diamondforge.kyromera.bot.models

data class Rank(
    val userId: Long,
    val guildId: Long,
    val level: Int,
    val xp: Int,
    val rank: Long
)