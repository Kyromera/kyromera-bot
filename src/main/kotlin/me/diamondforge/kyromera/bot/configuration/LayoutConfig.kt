package me.diamondforge.kyromera.bot.configuration
import me.diamondforge.kyromera.levelcardlib.LayoutConfig

object LayoutConfig {
    val layout = LayoutConfig.Builder()
        .usernameOffset()
        .rankLevelOffset()
        .xpTextOffset()
        .build()
}