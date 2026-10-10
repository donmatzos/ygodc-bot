package at.magi.ygodiscordbot.impl.tournament;

import at.magi.ygodiscordbot.impl.command.DatabaseReplies;
import at.magi.ygodiscordbot.impl.command.SlashCommand;
import at.magi.ygodiscordbot.impl.leaderboard.LeaderboardAdminCommand;
import at.magi.ygodiscordbot.utils.discord.DcMessageUtils;
import at.magi.ygodiscordbot.utils.discord.MessageSender;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.User;
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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code /tournament start|continue|standings|cancel|drop}. Who may use it is decided by Discord (default Manage
 * Server, changeable under Integrations); Discord can't restrict single subcommands, so all of them are for organizers.
 * Players are entered as @-mentions in one text option: a command can have at most 25 options, a tournament 32 players.
 */
public final class TournamentCommand implements SlashCommand {

    private static final Logger log = LoggerFactory.getLogger(TournamentCommand.class);

    static final String BUSY = "Too many requests right now. Please try again in a moment.";
    static final String UNAVAILABLE = "Tournaments are not available right now. Please try again later.";
    static final DatabaseReplies.Texts TEXTS = new DatabaseReplies.Texts(BUSY, UNAVAILABLE);

    private static final String PLAYERS = "players";
    private static final String ID = "id";
    private static final String PLAYER = "player";
    /** {@code <@123>} or the legacy {@code <@!123>}; role mentions ({@code <@&…>}) don't match. */
    private static final Pattern USER_MENTION = Pattern.compile("<@!?(\\d{17,20})>");

    /** The players option read as user IDs in mention order, or why it can't be used. */
    record PlayerList(List<Long> ids, String problem) {

        static PlayerList refused(String problem) {
            return new PlayerList(List.of(), problem);
        }
    }

    private final TournamentService service;
    private final Executor dbExecutor;

    public TournamentCommand(TournamentService service, Executor dbExecutor) {
        this.service = service;
        this.dbExecutor = dbExecutor;
    }

    @Override
    public SlashCommandData data() {
        return Commands.slash("tournament", "Host Swiss tournaments (organizers)")
                .addSubcommands(
                        new SubcommandData("start", "Start a tournament in this channel")
                                .addOptions(new OptionData(OptionType.STRING, PLAYERS,
                                        "@mention 2–32 players, separated by spaces", true).setMaxLength(6000)),
                        new SubcommandData("continue", "Start the next round once all results are in")
                                .addOptions(idOption()),
                        new SubcommandData("standings", "Show the standings and open matches")
                                .addOptions(idOption()),
                        new SubcommandData("cancel", "End a tournament without a winner")
                                .addOptions(idOption()),
                        new SubcommandData("drop", "Remove a player from the remaining rounds")
                                .addOptions(idOption(), new OptionData(OptionType.USER, PLAYER, "Player", true)))
                .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER))
                .setContexts(InteractionContextType.GUILD);
    }

    private static OptionData idOption() {
        // Interim until the command is reworked: the raw text is used as the tournament code
        return new OptionData(OptionType.STRING, ID, "Tournament ID", true).setMaxLength(100);
    }

    @Override
    public void execute(SlashCommandInteractionEvent event) {
        long guild = event.getGuild().getIdLong();
        String id = event.getOption(ID, "", OptionMapping::getAsString).strip();
        switch (String.valueOf(event.getSubcommandName())) {
            case "start" -> start(event, guild);
            case "continue" -> DatabaseReplies.replyEphemeral(event, dbExecutor, TEXTS,
                    () -> service.continueRound(id, guild));
            case "standings" -> DatabaseReplies.replyAllEphemeral(event, dbExecutor, TEXTS,
                    () -> service.standings(id, guild).render().apply(Map.of()));
            case "cancel" -> DatabaseReplies.replyEphemeral(event, dbExecutor, TEXTS,
                    () -> service.cancel(id, guild));
            case "drop" -> {
                long player = event.getOption(PLAYER, OptionMapping::getAsUser).getIdLong();
                DatabaseReplies.replyEphemeral(event, dbExecutor, TEXTS, () -> service.drop(id, guild, player));
            }
            default -> event.reply("Unknown subcommand.").setEphemeral(true).queue();
        }
    }

    private void start(SlashCommandInteractionEvent event, long guild) {
        OptionMapping option = event.getOption(PLAYERS);
        PlayerList players = parsePlayers(option.getAsString());
        String problem = players.problem();
        if (problem == null && option.getMentions().getUsers().stream().anyMatch(User::isBot)) {
            problem = "❌ Bots can't play in tournaments.";
        }
        if (problem == null) {
            GuildMessageChannel channel = event.getGuildChannel();
            problem = LeaderboardAdminCommand.channelProblem(channel.canTalk(event.getMember()), channel.canTalk(),
                    channel.getAsMention());
        }
        if (problem != null) {
            log.info("/tournament start refused for {}: {}", MessageSender.who(event), problem);
            event.reply(problem).setEphemeral(true).queue();
            return;
        }
        long channel = event.getChannel().getIdLong();
        long admin = event.getUser().getIdLong();
        DatabaseReplies.replyEphemeral(event, dbExecutor, TEXTS,
                () -> service.start(guild, channel, admin, players.ids()));
    }

    /** Reads user mentions; anything else except spaces, commas and line breaks is refused, never skipped. */
    static PlayerList parsePlayers(String raw) {
        List<Long> ids = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        Matcher matcher = USER_MENTION.matcher(raw);
        while (matcher.find()) {
            long id = Long.parseLong(matcher.group(1));
            if (!seen.add(id)) {
                return PlayerList.refused("❌ " + TournamentMessages.mention(id) + " is listed twice.");
            }
            ids.add(id);
        }
        String leftover = USER_MENTION.matcher(raw).replaceAll(" ").replaceAll("[\\s,]+", " ").strip();
        if (!leftover.isEmpty()) {
            String shown = leftover.length() > 50 ? leftover.substring(0, 50) + "…" : leftover;
            return PlayerList.refused("❌ I can only read @mentions, but found `" + DcMessageUtils.safe(shown)
                    + "`. Pick each player from the @ suggestions so Discord turns them into mentions.");
        }
        if (ids.size() < TournamentService.MIN_PLAYERS || ids.size() > TournamentService.MAX_PLAYERS) {
            return PlayerList.refused(TournamentMessages.playerCount(ids.size()));
        }
        return new PlayerList(List.copyOf(ids), null);
    }
}
