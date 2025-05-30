package me.diamondforge.kyromera.bot.listeners

import io.github.freya022.botcommands.api.core.annotations.BEventListener
import io.github.freya022.botcommands.api.core.service.annotations.BService
import me.diamondforge.kyromera.bot.services.LevelService
import net.dv8tion.jda.api.events.message.MessageReceivedEvent

@BService
class MessageListener(private val levelService: LevelService) {
    @BEventListener
    suspend fun onMessageCreate(event: MessageReceivedEvent) {
        levelService.handleMessageCreated(event)
    }

}