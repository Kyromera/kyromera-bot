package me.diamondforge.kyromera.bot.models

data class Experience(
    val userId: Long,
    val guildId: Long,
    val level: Int,
    val xp: Int,
    val rank: Long
)