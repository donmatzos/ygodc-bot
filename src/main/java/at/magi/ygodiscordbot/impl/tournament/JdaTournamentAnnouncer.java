package at.magi.ygodiscordbot.impl.tournament;

import at.magi.ygodiscordbot.impl.leaderboard.PlayerNames;
import at.magi.ygodiscordbot.utils.discord.MessageSender;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.requests.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;

/**
 * Posts into tournament channels and DMs players through JDA. A deleted channel, missing permission or closed DMs are
 * only logged: the tournament goes on and {@code /tournament standings} still shows everything.
 */
public final class JdaTournamentAnnouncer implements TournamentAnnouncer {

    private static final Logger log = LoggerFactory.getLogger(JdaTournamentAnnouncer.class);
    private static final EnumSet<Message.MentionType> NO_PINGS = EnumSet.noneOf(Message.MentionType.class);

    private final PlayerNames names;
    private volatile JDA jda;

    public JdaTournamentAnnouncer(PlayerNames names) {
        this.names = names;
    }

    /** Called once JDA is built, before any command or recovery can post. */
    public void attach(JDA jda) {
        this.jda = jda;
    }

    @Override
    public void post(long channelId, NamedText text, boolean ping) {
        JDA current = jda;
        if (current == null) {
            log.warn("Not connected to Discord yet, tournament post for channel {} skipped", channelId);
            return;
        }
        GuildMessageChannel channel = current.getChannelById(GuildMessageChannel.class, channelId);
        if (channel == null) {
            log.warn("Tournament channel {} not found, post skipped", channelId);
            return;
        }
        EnumSet<Message.MentionType> mentions = ping ? EnumSet.of(Message.MentionType.USER) : NO_PINGS;
        names.resolveIds(current, text.users(),
                found -> send(channel, text, found, mentions),
                failure -> {
                    log.warn("Could not look up names for tournament channel {}, posting without them", channelId,
                            failure);
                    send(channel, text, Map.of(), mentions);
                });
    }

    private static void send(GuildMessageChannel channel, NamedText text, Map<Long, String> found,
                             EnumSet<Message.MentionType> mentions) {
        try {
            MessageSender.sendAll(channel, text.render().apply(found), mentions).queue(null,
                    failure -> log.warn("Could not post into tournament channel {}", channel.getId(), failure));
        } catch (RuntimeException e) {
            // JDA checks the bot's cached permissions before sending
            log.warn("Could not post into tournament channel {}", channel.getId(), e);
        }
    }

    @Override
    public void dm(long userId, List<String> messages) {
        JDA current = jda;
        if (current == null) {
            log.warn("Not connected to Discord yet, tournament DM to {} skipped", userId);
            return;
        }
        current.retrieveUserById(userId)
                .flatMap(User::openPrivateChannel)
                .flatMap(channel -> MessageSender.sendAll(channel, messages, NO_PINGS))
                .queue(null, failure -> {
                    if (failure instanceof ErrorResponseException e
                            && e.getErrorResponse() == ErrorResponse.CANNOT_SEND_TO_USER) {
                        log.info("Could not DM tournament player {}: they do not accept DMs", userId);
                    } else {
                        log.warn("Could not DM tournament player {}", userId, failure);
                    }
                });
    }
}
