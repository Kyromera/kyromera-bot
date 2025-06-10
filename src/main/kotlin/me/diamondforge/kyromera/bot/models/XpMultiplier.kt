package me.diamondforge.kyromera.bot.models

import kotlinx.serialization.Serializable

@Serializable
data class XpMultiplier(
    val guildId: Long,
    val vcMultiplier: Double = DEFAULT_MULTIPLIER,
    val textMultiplier: Double = DEFAULT_MULTIPLIER,
    val textEnabled: Boolean = true,
    val vcEnabled: Boolean = true,
    val stackRoleMultipliers: Boolean = false,
) {
    companion object {
        const val DEFAULT_MULTIPLIER = 1.0
    }
}
