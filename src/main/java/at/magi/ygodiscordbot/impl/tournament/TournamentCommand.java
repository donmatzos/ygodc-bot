package at.magi.ygodiscordbot.impl.tournament;

import at.magi.ygodiscordbot.entity.tournament.TournamentCode;
import at.magi.ygodiscordbot.entity.tournament.TournamentListPage;
import at.magi.ygodiscordbot.impl.command.CommandChecks;
import at.magi.ygodiscordbot.impl.command.DatabaseReplies;
import at.magi.ygodiscordbot.impl.command.SlashCommand;
import at.magi.ygodiscordbot.utils.discord.ChannelChecks;
import at.magi.ygodiscordbot.utils.discord.DcMessageUtils;
import at.magi.ygodiscordbot.utils.discord.DisplayNames;
import at.magi.ygodiscordbot.utils.discord.MessageSender;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionContextType;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * {@code /tournament start|continue|standings|cancel|drop|list}. {@code list} is for everyone. The other subcommands
 * check Manage Server in the bot, because Discord can't restrict single subcommands; Integrations overrides can hide
 * the whole command but can't grant them, same as {@code /leaderboard add}.
 * Players are entered as @-mentions in one text option: a command can have at most 25 options, a tournament 32 players.
 */
public final class TournamentCommand implements SlashCommand {

    private static final Logger log = LoggerFactory.getLogger(TournamentCommand.class);

    static final String UNAVAILABLE = "Tournaments are not available right now. Please try again later.";
    static final DatabaseReplies.Texts TEXTS = new DatabaseReplies.Texts(CommandChecks.BUSY, UNAVAILABLE);

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

    private static final String PAGE = "page";
    private static final String DATE = "date";
    static final String ORGANIZERS_ONLY = "❌ Only members with **Manage Server** can run tournaments. "
            + "Anyone can use `/tournament list`.";

    private final TournamentService service;
    private final DisplayNames names;
    private final Executor dbExecutor;

    public TournamentCommand(TournamentService service, DisplayNames names, Executor dbExecutor) {
        this.service = service;
        this.names = names;
        this.dbExecutor = dbExecutor;
    }

    @Override
    public SlashCommandData data() {
        return Commands.slash("tournament", "Swiss tournaments: list them (everyone) or host them (Manage Server)")
                .addSubcommands(
                        new SubcommandData("start", "Start a tournament in this channel (Manage Server)")
                                .addOptions(new OptionData(OptionType.STRING, PLAYERS,
                                        "@mention 2–32 players, separated by spaces", true).setMaxLength(6000)),
                        new SubcommandData("continue", "Start the next round once all results are in (Manage Server)")
                                .addOptions(idOption()),
                        new SubcommandData("standings", "Show the standings and open matches (Manage Server)")
                                .addOptions(idOption()),
                        new SubcommandData("cancel", "End a tournament without a winner (Manage Server)")
                                .addOptions(idOption()),
                        new SubcommandData("drop", "Remove a player from the remaining rounds (Manage Server)")
                                .addOptions(idOption(), new OptionData(OptionType.USER, PLAYER, "Player", true)),
                        new SubcommandData("list", "List this server's tournaments, most recent first")
                                .addOptions(new OptionData(OptionType.INTEGER, PAGE, "Page (20 per page)", false)
                                                .setRequiredRange(1, 10_000),
                                        new OptionData(OptionType.STRING, DATE, "Only this day, as YY-MM-dd or YYYY-MM-dd", false)
                                                .setRequiredLength(8, 10)))
                .setContexts(InteractionContextType.GUILD);
    }

    private static OptionData idOption() {
        return new OptionData(OptionType.STRING, ID, "Tournament ID, e.g. " + TournamentCode.EXAMPLE, true)
                .setRequiredLength(TournamentCode.LENGTH, TournamentCode.LENGTH + 4);   // room for stray spaces
    }

    static String organizerProblem(boolean hasManageServer) {
        return hasManageServer ? null : ORGANIZERS_ONLY;
    }

    static String listProblem(TournamentListPage page, LocalDate day) {
        if (page.total() == 0) {
            return TournamentMessages.listEmpty(day);
        }
        return page.rows().isEmpty() ? TournamentMessages.listPageOutOfRange(page) : null;
    }

    @Override
    public Set<String> botCheckedManageServer() {
        return data().getSubcommands().stream().map(SubcommandData::getName)
                .filter(name -> !"list".equals(name)).collect(Collectors.toSet());
    }

    @Override
    public void execute(SlashCommandInteractionEvent event) {
        long guild = event.getGuild().getIdLong();
        String subcommand = String.valueOf(event.getSubcommandName());
        if ("list".equals(subcommand)) {
            list(event, guild);
            return;
        }
        String problem = organizerProblem(CommandChecks.canManageServer(event));
        if (problem != null) {
            CommandChecks.refuse(event, problem);
            return;
        }
        if ("start".equals(subcommand)) {
            start(event, guild);
            return;
        }
        String raw = event.getOption(ID, "", OptionMapping::getAsString);
        Optional<String> parsed = TournamentCode.parse(raw);
        if (parsed.isEmpty()) {
            event.reply(TournamentMessages.invalidCode(raw)).setEphemeral(true).queue();
            return;
        }
        String code = parsed.get();
        switch (subcommand) {
            case "continue" -> DatabaseReplies.replyEphemeral(event, dbExecutor, TEXTS,
                    () -> service.continueRound(code, guild));
            case "standings" -> DatabaseReplies.deferEphemeral(event, dbExecutor, TEXTS,
                    hook -> replyNamed(hook, service.standings(code, guild)));
            case "cancel" -> DatabaseReplies.replyEphemeral(event, dbExecutor, TEXTS, () -> service.cancel(code, guild));
            case "drop" -> {
                long player = event.getOption(PLAYER, OptionMapping::getAsUser).getIdLong();
                DatabaseReplies.replyEphemeral(event, dbExecutor, TEXTS, () -> service.drop(code, guild, player));
            }
            default -> CommandChecks.unknownSubcommand(event);
        }
    }

    /** The list filter takes the code's short form and the ISO form the list table shows; real days only. */
    static Optional<LocalDate> parseListDay(String raw) {
        Optional<LocalDate> short_ = TournamentCode.parseDay(raw);
        if (short_.isPresent()) {
            return short_;
        }
        try {
            return Optional.of(LocalDate.parse(raw.trim()));
        } catch (java.time.format.DateTimeParseException e) {
            return Optional.empty();
        }
    }

    private void list(SlashCommandInteractionEvent event, long guild) {
        int page = event.getOption(PAGE, 1, OptionMapping::getAsInt);
        String rawDay = event.getOption(DATE, OptionMapping::getAsString);
        LocalDate day = null;
        if (rawDay != null) {
            Optional<LocalDate> parsed = parseListDay(rawDay);
            if (parsed.isEmpty()) {
                event.reply("❌ `" + DcMessageUtils.safe(rawDay) + "` is not a day. Use YY-MM-dd or YYYY-MM-dd, e.g. `26-10-10` or `2026-10-10`.")
                        .setEphemeral(true).queue();
                return;
            }
            day = parsed.get();
        }
        LocalDate filter = day;
        DatabaseReplies.deferEphemeral(event, dbExecutor, TEXTS, hook -> {
            TournamentListPage found = service.listPage(guild, filter, page);
            String problem = listProblem(found, filter);
            replyNamed(hook, problem == null ? TournamentMessages.list(found, filter) : NamedText.plain(problem));
        });
    }

    /** Looks up the names the text needs, then replaces the deferred (ephemeral) reply with it. */
    private void replyNamed(InteractionHook hook, NamedText text) {
        names.resolveIds(hook.getJDA(), text.users(),
                found -> send(hook, text.render().apply(found)),
                failure -> {
                    log.warn("Could not look up names for a tournament reply", failure);
                    send(hook, text.render().apply(Map.of()));
                });
    }

    private static void send(InteractionHook hook, List<String> messages) {
        MessageSender.replyAll(hook, messages, true)
                .queue(null, failure -> log.warn("Could not send a tournament reply", failure));
    }

    private void start(SlashCommandInteractionEvent event, long guild) {
        OptionMapping option = event.getOption(PLAYERS);
        PlayerList players = parsePlayers(option.getAsString());
        String problem = players.problem();
        if (problem == null && option.getMentions().getUsers().stream().anyMatch(User::isBot)) {
            problem = "❌ Bots can't play in tournaments.";
        }
        if (problem == null) {
            Set<Long> memberIds = option.getMentions().getMembers().stream()
                    .map(Member::getIdLong).collect(Collectors.toSet());
            problem = membersProblem(players.ids(), memberIds);
        }
        if (problem == null) {
            GuildMessageChannel channel = event.getGuildChannel();
            problem = ChannelChecks.channelProblem(channel.canTalk(event.getMember()), channel.canTalk(),
                    channel.getAsMention());
        }
        if (problem != null) {
            CommandChecks.refuse(event, problem);
            return;
        }
        long channel = event.getChannel().getIdLong();
        long admin = event.getUser().getIdLong();
        DatabaseReplies.replyEphemeral(event, dbExecutor, TEXTS,
                () -> service.start(guild, channel, admin, players.ids()));
    }

    /** Refuses the first player who is not a member of this server (a raw ID can name anyone), else null. */
    static String membersProblem(List<Long> ids, Set<Long> memberIds) {
        return ids.stream().filter(id -> !memberIds.contains(id)).findFirst()
                .map(id -> "❌ " + TournamentMessages.mention(id) + " is not a member of this server.")
                .orElse(null);
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
