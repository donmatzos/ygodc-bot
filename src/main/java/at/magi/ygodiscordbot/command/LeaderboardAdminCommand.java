package at.magi.ygodiscordbot.command;

import at.magi.ygodiscordbot.format.LeaderboardMessages;
import at.magi.ygodiscordbot.leaderboard.LeaderboardPage;
import at.magi.ygodiscordbot.leaderboard.PlayerRepository;
import net.dv8tion.jda.api.Permission;
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

import java.sql.SQLException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * {@code /leaderboard-admin share [channel]}: posts the top 20 into a server channel.
 *
 * <p>Who may use it is decided by Discord: by default members with Manage Server, and server owners can change
 * that under Server Settings → Integrations. The bot therefore does not check that permission itself; it only
 * checks that both the member and the bot can post in the target channel.
 */
public final class LeaderboardAdminCommand implements SlashCommand {

    private static final Logger log = LoggerFactory.getLogger(LeaderboardAdminCommand.class);

    private static final String CHANNEL = "channel";

    private final PlayerRepository players;
    private final PlayerNames names;
    private final Executor dbExecutor;

    public LeaderboardAdminCommand(PlayerRepository players, PlayerNames names, Executor dbExecutor) {
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
            event.reply("Unknown subcommand.").setEphemeral(true).queue();
            return;
        }
        GuildChannel chosen = event.getOption(CHANNEL, event.getGuildChannel(), OptionMapping::getAsChannel);
        if (!(chosen instanceof GuildMessageChannel target)) {
            event.reply("I can only post the leaderboard into text channels.").setEphemeral(true).queue();
            return;
        }
        String problem = channelProblem(target.canTalk(event.getMember()), target.canTalk(), target.getAsMention());
        if (problem != null) {
            event.reply(problem).setEphemeral(true).queue();
            return;
        }
        log.info("/leaderboard-admin share by {} into #{} ({}) in server {}", MessageSender.who(event),
                target.getName(), target.getId(), event.getGuild().getId());
        event.deferReply(true).queue();
        try {
            dbExecutor.execute(() -> {
                LeaderboardPage page;
                try {
                    page = players.page(1);
                } catch (SQLException | RuntimeException e) {
                    log.warn("/leaderboard-admin share failed for {}", event.getUser().getId(), e);
                    event.getHook().editOriginal(LeaderboardCommand.UNAVAILABLE).queue();
                    return;
                }
                post(event, target, page);
            });
        } catch (RejectedExecutionException e) {
            log.warn("Request queue full, rejected /leaderboard-admin share by {}", event.getUser().getId());
            event.getHook().editOriginal(LeaderboardCommand.BUSY).queue();
        }
    }

    private void post(SlashCommandInteractionEvent event, GuildMessageChannel target, LeaderboardPage page) {
        String empty = LeaderboardCommand.noPageReply(page);
        if (empty != null) {
            event.getHook().editOriginal(empty).queue();
            return;
        }
        names.resolve(event.getJDA(), page.rows(), found -> {
            try {
                MessageSender.sendAll(target, LeaderboardMessages.page(LeaderboardCommand.TITLE, page, found))
                        .queue(last -> event.getHook()
                                        .editOriginal("✅ Posted the leaderboard in " + target.getAsMention() + ".")
                                        .queue(),
                                failure -> postFailed(event, target, failure));
            } catch (RuntimeException e) {
                // JDA checks the bot's cached permissions before sending (permissions changed since canTalk)
                postFailed(event, target, e);
            }
        }, failure -> {
            log.warn("Could not look up leaderboard names", failure);
            event.getHook().editOriginal(LeaderboardCommand.UNAVAILABLE).queue();
        });
    }

    private static void postFailed(SlashCommandInteractionEvent event, GuildMessageChannel target, Throwable failure) {
        log.warn("Could not post leaderboard into {}", target.getId(), failure);
        event.getHook().editOriginal("I could not post in " + target.getAsMention()
                + ". Check that I can view the channel and send messages there.").queue();
    }

    /** Why the leaderboard cannot be posted in the channel, or null if it can. */
    static String channelProblem(boolean memberCanTalk, boolean botCanTalk, String channelMention) {
        if (!memberCanTalk) {
            return "❌ You can't send messages in " + channelMention + ", so I won't post there for you.";
        }
        if (!botCanTalk) {
            return "❌ I can't post in " + channelMention + ". Give me **View Channel** and **Send Messages** there.";
        }
        return null;
    }
}
