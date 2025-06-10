package me.diamondforge.kyromera.bot.models

import kotlinx.serialization.Serializable

@Serializable
data class RoleMultiplier(
    val guildId: Long,
    val roleId: Long,
    val multiplier: Double
) {
    companion object {
        const val DEFAULT_MULTIPLIER = 1.0
    }
}