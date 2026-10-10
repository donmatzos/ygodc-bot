package at.magi.ygodiscordbot.impl.tournament;

import at.magi.ygodiscordbot.utils.discord.MessageSender;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumSet;
import java.util.List;

/**
 * Posts into tournament channels through JDA. A deleted channel or missing permission is only logged: the tournament
 * goes on and {@code /tournament standings} still shows everything.
 */
public final class JdaTournamentAnnouncer implements TournamentAnnouncer {

    private static final Logger log = LoggerFactory.getLogger(JdaTournamentAnnouncer.class);

    private volatile JDA jda;

    /** Called once JDA is built, before any command or recovery can post. */
    public void attach(JDA jda) {
        this.jda = jda;
    }

    @Override
    public void post(long channelId, List<String> messages, boolean ping) {
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
        EnumSet<Message.MentionType> mentions = ping
                ? EnumSet.of(Message.MentionType.USER)
                : EnumSet.noneOf(Message.MentionType.class);
        try {
            MessageSender.sendAll(channel, messages, mentions)
                    .queue(null, failure -> log.warn("Could not post into tournament channel {}", channelId, failure));
        } catch (RuntimeException e) {
            // JDA checks the bot's cached permissions before sending
            log.warn("Could not post into tournament channel {}", channelId, e);
        }
    }
}
