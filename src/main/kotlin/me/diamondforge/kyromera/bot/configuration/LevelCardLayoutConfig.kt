package me.diamondforge.kyromera.bot.configuration
import me.diamondforge.kyromera.levelcardlib.LayoutConfig

object LevelCardLayoutConfig {
    private const val TEXTOFFSET = 5
    val layout = LayoutConfig.Builder()
        .usernameOffset(TEXTOFFSET)
        .rankLevelOffset(TEXTOFFSET)
        .xpTextOffset(TEXTOFFSET)
        .build()
}