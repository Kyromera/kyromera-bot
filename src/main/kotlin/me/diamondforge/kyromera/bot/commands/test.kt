package me.diamondforge.kyromera.bot.commands


import dev.minn.jda.ktx.coroutines.await
import dev.minn.jda.ktx.messages.Embed
import dev.minn.jda.ktx.messages.MessageCreate
import dev.minn.jda.ktx.messages.send
import io.github.freya022.botcommands.api.commands.annotations.Command
import io.github.freya022.botcommands.api.commands.application.provider.GlobalApplicationCommandManager
import io.github.freya022.botcommands.api.commands.application.provider.GlobalApplicationCommandProvider
import io.github.freya022.botcommands.api.commands.application.slash.GuildSlashEvent
import io.github.freya022.botcommands.api.core.BContext
import io.github.freya022.botcommands.api.core.utils.send
import io.github.oshai.kotlinlogging.KotlinLogging
import me.diamondforge.kyromera.bot.services.LevelService
import me.diamondforge.kyromera.levelcardlib.wrapper.createLevelCard
import net.dv8tion.jda.api.JDAInfo
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.interactions.components.buttons.Button
import net.dv8tion.jda.api.utils.FileUpload
import net.dv8tion.jda.api.utils.TimeFormat
import java.awt.SystemColor.text
import java.awt.image.BufferedImage
import java.io.File
import java.lang.management.ManagementFactory

// some test
private val logger = KotlinLogging.logger { }

@Command
class test(
    private val context: BContext,
    private val levelService: LevelService


    ) : GlobalApplicationCommandProvider {
    suspend fun onCommand(
        event: GuildSlashEvent,
    ) {
        event.deferReply().await()
        val userexperience = levelService.getExperience(event.guild.idLong, event.user.idLong)
        val (min, max) = levelService.MinAndMaxXpForLevel(userexperience.level)
        val card = event.member.createLevelCard()
            .level(userexperience.level)
            .xp(min, max, userexperience.xp)
            .accentColor(0xFF2CBCC9.toInt())
            .showStatusIndicator(false)
            .rank(userexperience.rank.toInt())
            .build()
            .toFile()
            .toFileUpload()
        
        
        MessageCreate { files += card }.send(event.hook).queue()
    }
    
    fun BufferedImage.toFile(): File {
        val file = File.createTempFile("image", ".png")
        javax.imageio.ImageIO.write(this, "png", file)
        return file
    }
    
    fun File.toFileUpload(): FileUpload {
        return FileUpload.fromData(this, this.name)
    }

    override fun declareGlobalApplicationCommands(manager: GlobalApplicationCommandManager) {
        manager.slashCommand("test", function = ::onCommand) {
            description = "test"

            botPermissions += Permission.MESSAGE_SEND
            botPermissions += Permission.MESSAGE_EMBED_LINKS
        }
    }
}