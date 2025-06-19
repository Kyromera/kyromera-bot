package me.diamondforge.kyromera.bot.commands

import io.github.freya022.botcommands.api.commands.annotations.Command
import io.github.freya022.botcommands.api.commands.application.provider.GlobalApplicationCommandManager
import io.github.freya022.botcommands.api.commands.application.provider.GlobalApplicationCommandProvider
import io.github.freya022.botcommands.api.commands.application.slash.GuildSlashEvent
import io.github.freya022.botcommands.api.core.BContext
import io.github.freya022.botcommands.api.core.entities.InputUser
import io.github.freya022.botcommands.api.core.entities.inputUser
import io.github.oshai.kotlinlogging.KotlinLogging
import me.diamondforge.kyromera.bot.configuration.LevelCardLayoutConfig.layout
import me.diamondforge.kyromera.bot.services.LevelService
import me.diamondforge.kyromera.levelcardlib.wrapper.JDALevelCard
import me.diamondforge.kyromera.levelcardlib.wrapper.toByteArray
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.utils.FileUpload
import kotlin.time.measureTimedValue

private val logger = KotlinLogging.logger { }

@Command
class RankCommand(
    private val context: BContext,
    private val levelService: LevelService,
) : GlobalApplicationCommandProvider {
    suspend fun onCommand(
        event: GuildSlashEvent,
        user: InputUser = event.inputUser,
    ) {
        event.deferReply().queue()
        val (card, duration) =
            measureTimedValue {
                val userExperience = levelService.getExperience(event.guild.idLong, user.idLong)
                val (min, max) = levelService.MinAndMaxXpForLevel(userExperience.level)
                JDALevelCard
                    .Builder(user)
                    .xp(min, max, userExperience.xp)
                    .rank(userExperience.rank.toInt())
                    .layoutConfig(layout)
                    .showStatusIndicator(false)
                    .build()
                    .toByteArray()
            }
        val fileUpload = FileUpload.fromData(card, "levelcard.png")
        logger.trace { "Level card generated in ${duration.inWholeMilliseconds}ms for user ${user.id} (${user.name})" }

        event.hook.sendFiles(fileUpload).queue()
    }

    override fun declareGlobalApplicationCommands(manager: GlobalApplicationCommandManager) {
        manager.slashCommand("rank", function = ::onCommand) {
            description = "Shows the level card for a user."

            botPermissions += Permission.MESSAGE_SEND
            botPermissions += Permission.MESSAGE_EMBED_LINKS

            option("user", "user") {
                description = "The user whose level card will be shown. Defaults to yourself if not specified."
            }
        }
    }
}
