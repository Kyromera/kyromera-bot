package me.diamondforge.kyromera.bot.commands


import dev.minn.jda.ktx.messages.MessageCreate
import io.github.freya022.botcommands.api.commands.annotations.Command
import io.github.freya022.botcommands.api.commands.application.provider.GlobalApplicationCommandManager
import io.github.freya022.botcommands.api.commands.application.provider.GlobalApplicationCommandProvider
import io.github.freya022.botcommands.api.commands.application.slash.GuildSlashEvent
import io.github.freya022.botcommands.api.core.BContext
import io.github.freya022.botcommands.api.core.entities.InputUser
import io.github.oshai.kotlinlogging.KotlinLogging
import me.diamondforge.kyromera.bot.configuration.LevelCardLayoutConfig.layout
import me.diamondforge.kyromera.bot.services.LevelService
import me.diamondforge.kyromera.levelcardlib.wrapper.JDALevelCard
import me.diamondforge.kyromera.levelcardlib.wrapper.createLevelCard
import me.diamondforge.kyromera.levelcardlib.wrapper.toByteArray
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.utils.FileUpload


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
        event.deferReply().queue()
        val target = user ?: event.user
        val currtime = System.currentTimeMillis()
        val userExperience = levelService.getExperience(event.guild.idLong, target.idLong)
        val (min, max) = levelService.MinAndMaxXpForLevel(userExperience.level)
        val card = JDALevelCard.Builder(target)
            .xp(min, max, userExperience.xp)
            .rank(userExperience.rank.toInt())
            .layoutConfig(layout)
            .build().toByteArray()
        val fileUpload = FileUpload.fromData(card, "levelcard.png")
        val messageData = MessageCreate { files += fileUpload }
        logger.trace { "Level card generated in ${System.currentTimeMillis() - currtime}ms for user ${target.id} (${target.name})" }
        event.hook.sendMessage(messageData).queue()
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