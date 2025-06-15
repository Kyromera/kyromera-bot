package me.diamondforge.kyromera.bot.commands


import dev.minn.jda.ktx.coroutines.await
import dev.minn.jda.ktx.messages.MessageCreate
import io.github.freya022.botcommands.api.commands.annotations.Command
import io.github.freya022.botcommands.api.commands.application.provider.GlobalApplicationCommandManager
import io.github.freya022.botcommands.api.commands.application.provider.GlobalApplicationCommandProvider
import io.github.freya022.botcommands.api.commands.application.slash.GuildSlashEvent
import io.github.freya022.botcommands.api.core.BContext
import io.github.freya022.botcommands.api.core.entities.InputUser
import io.github.freya022.botcommands.api.core.entities.asInputUser
import io.github.oshai.kotlinlogging.KotlinLogging
import me.diamondforge.kyromera.bot.services.LevelService
import me.diamondforge.kyromera.levelcardlib.wrapper.createLevelCard
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.utils.FileUpload
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.IOException
import javax.imageio.ImageIO


// some test
private val logger = KotlinLogging.logger { }

@Command
class RankCommand(
    private val context: BContext,
    private val levelService: LevelService


    ) : GlobalApplicationCommandProvider {
    suspend fun onCommand(
        event: GuildSlashEvent,
        user: InputUser?
    ) {
        event.deferReply().await()
        val target = user ?: event.user
        val currtime = System.currentTimeMillis()
        val userExperience = levelService.getExperience(event.guild.idLong, target.idLong)
        var (min, max) = levelService.MinAndMaxXpForLevel(userExperience.level)
        max += 1
        val card = target.asInputUser().createLevelCard()
            .level(userExperience.level)
            .xp(min, max, userExperience.xp)
            .rank(userExperience.rank.toInt())
            .build().toByteArray("png")
        val fileUpload = FileUpload.fromData(card, "levelcard.png")
        val messageData = MessageCreate { files += fileUpload }
        logger.trace { "Level card generated in ${System.currentTimeMillis() - currtime}ms for user ${target.id} (${target.name})" }
        event.hook.sendMessage(messageData).await()
    }

    @Throws(IOException::class)
    fun BufferedImage.toByteArray(formatName: String): ByteArray {
        val baos = ByteArrayOutputStream()
        ImageIO.write(this, formatName, baos)
        baos.flush()
        baos.close()
        return baos.toByteArray()
    }
    

    override fun declareGlobalApplicationCommands(manager: GlobalApplicationCommandManager) {
        manager.slashCommand("rank", function = ::onCommand) {
            description = "Show Level Card for a user."

            botPermissions += Permission.MESSAGE_SEND
            botPermissions += Permission.MESSAGE_EMBED_LINKS
            option("user", "user") {
                description = "The User to show the level card for. Defaults to yourself if not specified."
            }
        }
    }
}