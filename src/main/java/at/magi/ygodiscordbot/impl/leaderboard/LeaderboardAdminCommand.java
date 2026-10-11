package at.magi.ygodiscordbot.impl.leaderboard;

import at.magi.ygodiscordbot.entity.leaderboard.LeaderboardPage;
import at.magi.ygodiscordbot.impl.command.CommandChecks;
import at.magi.ygodiscordbot.impl.command.DatabaseReplies;
import at.magi.ygodiscordbot.impl.command.SlashCommand;
import at.magi.ygodiscordbot.utils.discord.ChannelChecks;
import at.magi.ygodiscordbot.utils.discord.DisplayNames;
import at.magi.ygodiscordbot.utils.discord.MessageSender;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionContextType;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumSet;
import java.util.concurrent.Executor;

/**
 * {@code /leaderboard-admin share [channel]}: posts the top 20 into a server channel.
 *
 * <p>Who may use it is decided by Discord: by default members with Manage Server, and server owners can change
 * that under Server Settings → Integrations. The bot therefore does not check that permission itself; it only
 * checks that both the member and the bot can post in the target channel.
 */
public final class LeaderboardAdminCommand implements SlashCommand {

    private static final Logger log = LoggerFactory.getLogger(LeaderboardAdminCommand.class);

    /** The posted leaderboard never notifies anyone, whatever a player name looks like. */
    static final EnumSet<Message.MentionType> SHARE_MENTIONS = EnumSet.noneOf(Message.MentionType.class);

    private static final String CHANNEL = "channel";

    private final PlayerRepository players;
    private final DisplayNames names;
    private final Executor dbExecutor;

    public LeaderboardAdminCommand(PlayerRepository players, DisplayNames names, Executor dbExecutor) {
        this.players = players;
        this.names = names;
        this.dbExecutor = dbExecutor;
    }

    @Override
    public SlashCommandData data() {
        return Commands.slash("leaderboard-admin", "Leaderboard tools for tournament organizers")
                .addSubcommands(new SubcommandData("share", "Post the top 20 into a channel")
                        .addOptions(new OptionData(OptionType.CHANNEL, CHANNEL,
                                "Where to post (default: this channel)", false)
                                .setChannelTypes(ChannelType.TEXT, ChannelType.NEWS)))
                .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER))
                .setContexts(InteractionContextType.GUILD);
    }

    @Override
    public void execute(SlashCommandInteractionEvent event) {
        if (!"share".equals(event.getSubcommandName())) {
            CommandChecks.unknownSubcommand(event);
            return;
        }
        GuildChannel chosen = event.getOption(CHANNEL, event.getGuildChannel(), OptionMapping::getAsChannel);
        String problem = chosen instanceof GuildMessageChannel target
                ? ChannelChecks.channelProblem(target.canTalk(event.getMember()), target.canTalk(), target.getAsMention())
                : "I can only post the leaderboard into text channels.";
        if (problem != null) {
            CommandChecks.refuse(event, problem);
            return;
        }
        GuildMessageChannel target = (GuildMessageChannel) chosen;
        // Only after Discord accepted the defer: a retry after a timeout must not post the leaderboard twice
        DatabaseReplies.deferEphemeral(event, dbExecutor, LeaderboardCommand.TEXTS,
                hook -> post(event, target, players.page(1)));
    }

    private void post(SlashCommandInteractionEvent event, GuildMessageChannel target, LeaderboardPage page) {
        LeaderboardCommand.renderPage(event, names, page, messages -> {
            try {
                MessageSender.sendAll(target, messages, SHARE_MENTIONS)
                        .queue(last -> {
                                    log.info("Posted the leaderboard into #{} ({}) for {}", target.getName(),
                                            target.getId(), MessageSender.who(event));
                                    event.getHook()
                                            .editOriginal("✅ Posted the leaderboard in " + target.getAsMention() + ".")
                                            .queue();
                                },
                                failure -> postFailed(event, target, failure));
            } catch (RuntimeException e) {
                // JDA checks the bot's cached permissions before sending (permissions changed since canTalk)
                postFailed(event, target, e);
            }
        });
    }

    private static void postFailed(SlashCommandInteractionEvent event, GuildMessageChannel target, Throwable failure) {
        log.warn("Could not post leaderboard into {}", target.getId(), failure);
        event.getHook().editOriginal("I could not post in " + target.getAsMention()
                + ". Check that I can view the channel and send messages there.").queue();
    }
}
