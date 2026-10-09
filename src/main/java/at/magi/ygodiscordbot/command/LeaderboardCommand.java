package at.magi.ygodiscordbot.command;

import at.magi.ygodiscordbot.format.LeaderboardMessages;
import at.magi.ygodiscordbot.leaderboard.LeaderboardPage;
import at.magi.ygodiscordbot.leaderboard.PlayerRepository;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionContextType;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * {@code /leaderboard [page]}: one page of 20 players, highest points first. Used in a server, the page is sent to
 * the user's DMs; used in the bot DM, it is posted right there.
 */
public final class LeaderboardCommand implements SlashCommand {

    private static final Logger log = LoggerFactory.getLogger(LeaderboardCommand.class);

    static final String TITLE = "🏆 Leaderboard";
    static final String UNAVAILABLE = "The leaderboard is not available right now. Please try again later.";
    static final String BUSY = "Too many requests right now. Please try again in a moment.";
    static final int MAX_PAGE = 10_000;

    private static final String PAGE = "page";

    private final PlayerRepository players;
    private final PlayerNames names;
    private final Executor dbExecutor;

    public LeaderboardCommand(PlayerRepository players, PlayerNames names, Executor dbExecutor) {
        this.players = players;
        this.names = names;
        this.dbExecutor = dbExecutor;
    }

    @Override
    public SlashCommandData data() {
        return Commands.slash("leaderboard", "Show the tournament leaderboard (sent to your DMs)")
                .addOptions(new OptionData(OptionType.INTEGER, PAGE, "Page (20 players each), default 1", false)
                        .setRequiredRange(1, MAX_PAGE))
                .setContexts(InteractionContextType.GUILD, InteractionContextType.BOT_DM);
    }

    @Override
    public void execute(SlashCommandInteractionEvent event) {
        int page = event.getOption(PAGE, 1, OptionMapping::getAsInt);
        log.info("/leaderboard page {} by {}", page, MessageSender.who(event));
        // Ephemeral in servers; in the bot DM a normal message, so it is still there after a client restart
        event.deferReply(event.isFromGuild()).queue();
        try {
            dbExecutor.execute(() -> {
                LeaderboardPage result;
                try {
                    result = players.page(page);
                } catch (SQLException | RuntimeException e) {
                    log.warn("/leaderboard failed for {}", event.getUser().getId(), e);
                    event.getHook().editOriginal(UNAVAILABLE).queue();
                    return;
                }
                reply(event, result);
            });
        } catch (RejectedExecutionException e) {
            log.warn("Request queue full, rejected /leaderboard by {}", event.getUser().getId());
            event.getHook().editOriginal(BUSY).queue();
        }
    }

    private void reply(SlashCommandInteractionEvent event, LeaderboardPage page) {
        String noPage = noPageReply(page);
        if (noPage != null) {
            event.getHook().editOriginal(noPage).queue();
            return;
        }
        names.resolve(event.getJDA(), page.rows(), found -> {
            List<String> messages = LeaderboardMessages.page(TITLE, page, found);
            if (event.isFromGuild()) {
                MessageSender.sendToDirectMessages(event, messages, "the leaderboard", "`/leaderboard`",
                        () -> log.info("Sent leaderboard page {} to {} via DM", page.page(), MessageSender.who(event)));
            } else {
                MessageSender.replyAll(event.getHook(), messages, false).queue(null,
                        failure -> log.warn("Could not send leaderboard to {}", MessageSender.who(event), failure));
            }
        }, failure -> {
            log.warn("Could not look up leaderboard names", failure);
            event.getHook().editOriginal(UNAVAILABLE).queue();
        });
    }

    /** The reply when there is no page to show, or null if the page exists. */
    static String noPageReply(LeaderboardPage page) {
        if (page.totalPlayers() == 0) {
            return LeaderboardMessages.empty();
        }
        if (!page.exists()) {
            return LeaderboardMessages.pageOutOfRange(page);
        }
        return null;
    }
}
