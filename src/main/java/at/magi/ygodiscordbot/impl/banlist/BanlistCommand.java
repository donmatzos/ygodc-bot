package at.magi.ygodiscordbot.impl.banlist;

import at.magi.ygodiscordbot.command.SlashCommand;
import at.magi.ygodiscordbot.utils.discord.MessageSender;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionContextType;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * {@code /banlist format:<list>} sends the chosen list as a series of messages.
 *
 * <p>Used in a DM with the bot, the list is posted right there. Used in a server, the list is sent to the
 * user's DMs instead, so server channels stay clean and everyone gets a private, searchable copy.
 */
public final class BanlistCommand implements SlashCommand {

    private static final Logger log = LoggerFactory.getLogger(BanlistCommand.class);

    private static final String OPTION = "format";

    enum Format {
        TCG("TCG"),
        OCG("OCG"),
        GENESYS("Genesys"),
        GOAT("Goat"),
        EDISON("Edison");

        private final String label;

        Format(String label) {
            this.label = label;
        }
    }

    private final BanlistRepository banlists;

    public BanlistCommand(BanlistRepository banlists) {
        this.banlists = banlists;
    }

    @Override
    public SlashCommandData data() {
        OptionData format = new OptionData(OptionType.STRING, OPTION, "Which list to show", true);
        for (Format value : Format.values()) {
            format.addChoice(value.label, value.name().toLowerCase(Locale.ROOT));
        }
        return Commands.slash("banlist", "Show a Forbidden & Limited list or the Genesys points list")
                .addOptions(format)
                .setContexts(InteractionContextType.GUILD, InteractionContextType.BOT_DM);
    }

    @Override
    public void execute(SlashCommandInteractionEvent event) {
        Instant started = Instant.now();
        Format format = Format.valueOf(event.getOption(OPTION, OptionMapping::getAsString).toUpperCase(Locale.ROOT));
        Optional<List<String>> messages = render(format);
        if (messages.isEmpty()) {
            log.info("{} list requested by {} but not loaded yet", format.label, who(event));
            event.reply("The " + format.label + " list is not available yet. Please try again in a few minutes.")
                    .setEphemeral(true).queue();
            return;
        }
        if (event.isFromGuild()) {
            sendToDirectMessages(event, format, messages.get(), started);
        } else {
            replyHere(event, format, messages.get(), started);
        }
    }

    Optional<List<String>> render(Format format) {
        return switch (format) {
            case TCG -> banlists.tcg().map(list -> ListMessages.banlist("TCG Forbidden & Limited List",
                    "updated " + ListMessages.timestamp(list.fetchedAt()) + " · Source: YGOProDeck", list));
            case OCG -> banlists.ocg().map(list -> ListMessages.banlist("OCG Forbidden & Limited List",
                    "updated " + ListMessages.timestamp(list.fetchedAt()) + " · Source: YGOProDeck", list));
            case GENESYS -> banlists.genesys().map(ListMessages::genesys);
            case GOAT -> Optional.of(ListMessages.banlist("Goat Format List",
                    banlists.goat().name() + " list · fixed format list", banlists.goat()));
            case EDISON -> Optional.of(ListMessages.banlist("Edison Format List",
                    banlists.edison().name() + " list · fixed format list", banlists.edison()));
        };
    }

    private void replyHere(SlashCommandInteractionEvent event, Format format, List<String> messages, Instant started) {
        MessageSender.followUps(event.reply(messages.get(0)), event.getHook(), messages, false).queue(
                last -> log.info("Sent {} list ({} messages) to {} in their DM with the bot, took {} ms",
                        format.label, messages.size(), who(event), millisSince(started)),
                error -> log.warn("Could not send {} list to {} in their DM with the bot", format.label, who(event), error));
    }

    private void sendToDirectMessages(SlashCommandInteractionEvent event, Format format, List<String> messages,
                                      Instant started) {
        event.deferReply(true).queue();
        MessageSender.sendToDirectMessages(event, messages, "the " + format.label + " list", "`/banlist`",
                () -> log.info("Sent {} list ({} messages) to {} via DM, requested in server {}, took {} ms",
                        format.label, messages.size(), who(event), event.getGuild().getId(), millisSince(started)));
    }

    private static String who(SlashCommandInteractionEvent event) {
        return MessageSender.who(event);
    }

    private static long millisSince(Instant started) {
        return Duration.between(started, Instant.now()).toMillis();
    }
}
