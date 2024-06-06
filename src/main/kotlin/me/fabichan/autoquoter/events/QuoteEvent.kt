package me.fabichan.autoquoter.events

import dev.minn.jda.ktx.generics.getChannel
import dev.minn.jda.ktx.messages.EmbedBuilder
import dev.minn.jda.ktx.messages.MessageCreate
import io.github.freya022.botcommands.api.core.annotations.BEventListener
import io.github.freya022.botcommands.api.core.db.Database
import io.github.freya022.botcommands.api.core.db.preparedStatement
import io.github.freya022.botcommands.api.core.service.annotations.BService
import io.github.freya022.botcommands.api.core.utils.awaitOrNullOn
import io.github.oshai.kotlinlogging.KotlinLogging
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import net.dv8tion.jda.api.requests.ErrorResponse
import net.dv8tion.jda.api.utils.messages.MessageCreateData

private val logger by lazy { KotlinLogging.logger {} }

@BService
class QuoteEvent(private val database: Database) {

    @BEventListener
    suspend fun onMessage(event: MessageReceivedEvent) {
        if (event.author.isBot) return
        if (!event.isFromGuild) return

        ProcessMessageWithLinks(event)
    }

    private val messageUrlRegex = Regex(
        "(?:https?://)?(?:\\w+\\.)?discord(?:app)?\\.com/channels/(\\d+)/(\\d+)/(\\d+)",
        RegexOption.IGNORE_CASE
    )


    private suspend fun retrieveMessagesByLink(guild: Guild, content: String): List<Message> {
        return messageUrlRegex.findAll(content).toList()
            .map { it.destructured }
            .mapNotNull { (guildId, channelId, messageId) ->
                if (guildId != guild.id) return@mapNotNull null

                val channel = guild.getChannel<GuildMessageChannel>(channelId)
                    ?: return@mapNotNull null

                channel.retrieveMessageById(messageId).awaitOrNullOn(ErrorResponse.UNKNOWN_MESSAGE)
            }
    }


    private suspend fun ProcessMessageWithLinks(event: MessageReceivedEvent) {
        val messages = retrieveMessagesByLink(event.guild, event.message.contentRaw)
        
        if (messages.isEmpty()) return

        for (i in 0 until minOf(3, messages.size)) {
            val message = messages[i]
            try {
                val m = BuildQuoteEmbed(message)
                event.channel.sendMessage(m).queue()
                recordQuoteStats(message)
            } catch (e: Exception) {

            }
        }
    }

    private suspend fun BuildQuoteEmbed(quotedMessage: Message): MessageCreateData {
        val ftitle = "AutoQuoter"

        val eb = EmbedBuilder { }


        if (quotedMessage.embeds.isEmpty()) {
            eb.footer {
                name = ftitle
            }
            eb.timestamp = quotedMessage.timeCreated

            if (quotedMessage.contentRaw.isNotEmpty()) {
                val text = "\"" + quotedMessage.contentRaw + "\""
                if (quotedMessage.contentRaw.length > 4096) {
                    eb.description = text.substring(0, 4089) + " [...]"
                } else {
                    eb.description = text
                }

                if (quotedMessage.attachments.isNotEmpty()) {
                    val attachment = quotedMessage.attachments[0]
                    if (attachment.isImage) {
                        eb.image = attachment.url
                    }
                }
            }
        } else {
            val oldEmbed = quotedMessage.embeds[0]

            // check image 
            if (oldEmbed.image != null) {
                eb.image = oldEmbed.image?.url
            } else if (quotedMessage.attachments.isNotEmpty()) {
                val attachment = quotedMessage.attachments[0]
                if (attachment.isImage) {
                    eb.image = attachment.url
                }
            }
            
            for (field in oldEmbed.fields) {
                eb.field {
                    name = field.name.toString()
                    value = field.value.toString()
                    inline = field.isInline
                }
            }

            eb.description = oldEmbed.description


            if (oldEmbed.footer != null) {
                eb.footer {
                    name = (oldEmbed.footer?.text + " - " + ftitle).truncate(4096)
                }
            } else {
                eb.footer {
                    name = ftitle
                }
            }

            eb.timestamp = quotedMessage.timeCreated

        }

        eb.author {
            name = "Sent by " + getUserName(quotedMessage.author)
            iconUrl = quotedMessage.author.effectiveAvatarUrl
        }

        eb.color = 0x00FFFF

        val embed = eb.build()

        val msgdata = MessageCreate {
            embeds += embed
        }
        return msgdata
    }


    private fun String.truncate(length: Int): String {
        return if (this.length > length) {
            this.substring(0, length)
        } else {
            this
        }
    }

    private fun getUserName(user: User): String {
        @Suppress("DEPRECATION")
        return if (user.discriminator != "0000") {
            @Suppress("DEPRECATION")
            "${user.name}#${user.discriminator}"
        } else {
            user.name
        }
    }
    
    
    private suspend fun recordQuoteStats(quotedMessage: Message) {
        logger.info { "Quoted message from ${quotedMessage.author.name} (${quotedMessage.author.id}) in ${quotedMessage.guild.name}/${quotedMessage.channel.name} (${quotedMessage.guild.id}/${quotedMessage.channel.id})" }
        database.preparedStatement("INSERT INTO quotestats (user_id, channel_id, guild_id, timestamp) VALUES (?, ?, ?, ?)") {
            setLong(1, quotedMessage.author.idLong)
            setLong(2, quotedMessage.channel.idLong)
            setLong(3, quotedMessage.guild.idLong)
            setLong(4, quotedMessage.timeCreated.toEpochSecond())
            executeUpdate()
        }
    }        
}