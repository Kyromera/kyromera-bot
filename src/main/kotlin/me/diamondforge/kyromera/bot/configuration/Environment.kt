package me.diamondforge.kyromera.bot.configuration

import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.exists


object Environment {
    val configFolder: Path = Path("config").also {
        if (!it.exists()) {
            it.toFile().mkdirs()
        }
    }
    val logFolder: Path = Path("logs")
    val dataFolder: Path = Path("data")
    val logbackConfigPath: Path = configFolder.resolve("logback.xml")
    val isDev: Boolean = configFolder.resolve(".dev").exists()
    val isDbTest: Boolean = configFolder.resolve(".dbtest").exists()
}