package at.magi.ygodiscordbot.impl.leaderboard;

import at.magi.ygodiscordbot.entity.leaderboard.LeaderboardPage;
import at.magi.ygodiscordbot.entity.leaderboard.PointChange;
import at.magi.ygodiscordbot.entity.leaderboard.Points;
import at.magi.ygodiscordbot.impl.command.CommandChecks;
import at.magi.ygodiscordbot.impl.command.DatabaseReplies;
import at.magi.ygodiscordbot.impl.command.SlashCommand;
import at.magi.ygodiscordbot.utils.discord.DisplayNames;
import at.magi.ygodiscordbot.utils.discord.MessageSender;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionContextType;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * {@code /leaderboard page|get|add|update}: view pages of 20 players (used in a server, the page is sent to the
 * user's DMs; in the bot DM it is posted right there), read one player's points, and add players or set their
 * points. Discord can't restrict single subcommands, so the bot checks Manage Server itself for add and update.
 */
public final class LeaderboardCommand implements SlashCommand {

    private static final Logger log = LoggerFactory.getLogger(LeaderboardCommand.class);

    static final String TITLE = "🏆 Leaderboard";
    static final String UNAVAILABLE = "The leaderboard is not available right now. Please try again later.";
    static final DatabaseReplies.Texts TEXTS = new DatabaseReplies.Texts(CommandChecks.BUSY, UNAVAILABLE);
    static final int MAX_PAGE = 10_000;

    static final String PLAYER = "player";

    private static final String PAGE = "page";
    private static final String POINTS = "points";
    private static final String NOT_ON_BOARD_HINT = " Add them with `/leaderboard add` first.";

    private final PlayerRepository players;
    private final DisplayNames names;
    private final Executor dbExecutor;

    public LeaderboardCommand(PlayerRepository players, DisplayNames names, Executor dbExecutor) {
        this.players = players;
        this.names = names;
        this.dbExecutor = dbExecutor;
    }

    @Override
    public SlashCommandData data() {
        return Commands.slash("leaderboard", "Tournament leaderboard")
                .addSubcommands(
                        new SubcommandData("page", "Show a leaderboard page (sent to your DMs)")
                                .addOptions(new OptionData(OptionType.INTEGER, PAGE, "Page (20 players each), default 1",
                                        false).setRequiredRange(1, MAX_PAGE)),
                        new SubcommandData("get", "Show a player's points and rank")
                                .addOptions(new OptionData(OptionType.USER, PLAYER, "Player (default: you)", false)),
                        new SubcommandData("add", "Add a player with 0 points (Manage Server)")
                                .addOptions(new OptionData(OptionType.USER, PLAYER, "Player to add", true)),
                        new SubcommandData("update", "Set a player's points (Manage Server)")
                                .addOptions(new OptionData(OptionType.USER, PLAYER, "Player", true),
                                        new OptionData(OptionType.INTEGER, POINTS,
                                                String.format(Locale.ROOT, "New total (0–%,d)", Points.MAX), true)
                                                .setRequiredRange(0, Points.MAX)))
                .setContexts(InteractionContextType.GUILD, InteractionContextType.BOT_DM);
    }

    @Override
    public Set<String> botCheckedManageServer() {
        return Set.of("add", "update");
    }

    @Override
    public void execute(SlashCommandInteractionEvent event) {
        switch (String.valueOf(event.getSubcommandName())) {
            case "page" -> page(event);
            case "get" -> get(event);
            case "add" -> add(event);
            case "update" -> update(event);
            default -> CommandChecks.unknownSubcommand(event);
        }
    }

    private void page(SlashCommandInteractionEvent event) {
        int page = event.getOption(PAGE, 1, OptionMapping::getAsInt);
        // Ephemeral in servers; in the bot DM a normal message, so it is still there after a client restart
        DatabaseReplies.defer(event, event.isFromGuild(), dbExecutor, TEXTS, hook -> reply(event, players.page(page)));
    }

    private void reply(SlashCommandInteractionEvent event, LeaderboardPage page) {
        String noPage = noPageReply(page);
        if (noPage != null) {
            event.getHook().editOriginal(noPage).queue();
            return;
        }
        names.resolve(event.getJDA(), page.rows(), found -> {
            List<String> messages = LeaderboardMessages.page(TITLE, page, found);
            MessageSender.deliver(event, messages, "the leaderboard", "`/leaderboard page`",
                    () -> log.info("Sent leaderboard page {} to {}", page.page(), MessageSender.who(event)));
        }, failure -> {
            log.warn("Could not look up leaderboard names", failure);
            event.getHook().editOriginal(UNAVAILABLE).queue();
        });
    }

    private void get(SlashCommandInteractionEvent event) {
        User player = event.getOption(PLAYER, event.getUser(), OptionMapping::getAsUser);
        String name = player.getEffectiveName();
        DatabaseReplies.replyEphemeral(event, dbExecutor, TEXTS, () -> players.find(player.getIdLong())
                .map(found -> LeaderboardMessages.playerPoints(name, found))
                .orElse(LeaderboardMessages.notOnBoard(name)));
    }

    private void add(SlashCommandInteractionEvent event) {
        User player = event.getOption(PLAYER, OptionMapping::getAsUser);
        if (refused(event, player)) {
            return;
        }
        String name = player.getEffectiveName();
        DatabaseReplies.replyEphemeral(event, dbExecutor, TEXTS, () -> players.create(player.getIdLong())
                ? LeaderboardMessages.added(name)
                : LeaderboardMessages.alreadyOnBoard(name));
    }

    private void update(SlashCommandInteractionEvent event) {
        User player = event.getOption(PLAYER, OptionMapping::getAsUser);
        long points = event.getOption(POINTS, 0L, OptionMapping::getAsLong);
        if (refused(event, player)) {
            return;
        }
        String name = player.getEffectiveName();
        DatabaseReplies.replyEphemeral(event, dbExecutor, TEXTS, () -> {
            PointChange change = players.setPoints(player.getIdLong(), points);
            return change == null
                    ? LeaderboardMessages.notOnBoard(name) + NOT_ON_BOARD_HINT
                    : LeaderboardMessages.pointsSet(name, change);
        });
    }

    /** Replies with the reason and returns true if the caller may not change this player's entry. */
    private static boolean refused(SlashCommandInteractionEvent event, User player) {
        String problem = changeProblem(event.isFromGuild(), CommandChecks.canManageServer(event), player.isBot());
        if (problem != null) {
            CommandChecks.refuse(event, problem);
        }
        return problem != null;
    }

    /** Why the caller may not add or update this player, or null if they may. */
    static String changeProblem(boolean inGuild, boolean canManageServer, boolean targetIsBot) {
        String problem = permissionProblem(inGuild, canManageServer);
        return problem != null ? problem : targetProblem(targetIsBot);
    }

    /** Why the caller may not change the leaderboard at all, or null. */
    static String permissionProblem(boolean inGuild, boolean canManageServer) {
        if (!inGuild) {
            return "❌ The leaderboard can only be changed in a server.";
        }
        return canManageServer ? null : "❌ You need **Manage Server** to change the leaderboard.";
    }

    /** Why this player can't be on the leaderboard, or null. */
    static String targetProblem(boolean targetIsBot) {
        return targetIsBot ? "❌ Bots can't be on the leaderboard." : null;
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
