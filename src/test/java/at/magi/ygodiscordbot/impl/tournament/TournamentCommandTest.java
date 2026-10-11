package at.magi.ygodiscordbot.impl.tournament;

import at.magi.ygodiscordbot.entity.tournament.TournamentListPage;
import at.magi.ygodiscordbot.entity.tournament.TournamentStatus;
import at.magi.ygodiscordbot.entity.tournament.TournamentSummary;
import at.magi.ygodiscordbot.testutil.FakeInteractions;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.unions.GuildMessageChannelUnion;
import net.dv8tion.jda.api.entities.channel.unions.MessageChannelUnion;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import static at.magi.ygodiscordbot.testutil.CommandAssert.assertBotCheckedManageServer;
import static at.magi.ygodiscordbot.testutil.CommandAssert.assertGuildOnly;
import static at.magi.ygodiscordbot.testutil.CommandAssert.assertOpenToEveryone;
import static at.magi.ygodiscordbot.testutil.CommandAssert.assertSubcommands;
import static at.magi.ygodiscordbot.testutil.CommandAssert.option;
import static at.magi.ygodiscordbot.testutil.CommandAssert.sub;
import static at.magi.ygodiscordbot.testutil.FakeInteractions.guild;
import static at.magi.ygodiscordbot.testutil.FakeInteractions.returns;
import static at.magi.ygodiscordbot.testutil.FakeInteractions.stringOption;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

public class TournamentCommandTest {

    private static final long FIRST = 100_000_000_000_000_001L;
    private static final long SECOND = 100_000_000_000_000_002L;

    private static String mentions(int count) {
        return LongStream.range(0, count).mapToObj(i -> "<@" + (FIRST + i) + ">").collect(Collectors.joining(" "));
    }

    @Test
    public void listPageOptionIsBounded() {
        var page = option(sub(new TournamentCommand(null, null, Runnable::run).data(), "list"), "page");
        assertEquals(page.getMinValue().longValue(), 1L);
        assertEquals(page.getMaxValue().longValue(), 10_000L);
    }

    @Test
    public void openToEveryoneInServersWithList() {
        var data = new TournamentCommand(null, null, Runnable::run).data();
        assertEquals(data.getName(), "tournament");
        assertOpenToEveryone(data);
        assertGuildOnly(data);
        assertSubcommands(data, "start", "continue", "standings", "cancel", "drop", "list");
        SubcommandData list = sub(data, "list");
        assertEquals(list.getOptions().stream().map(OptionData::getName).toList(), List.of("page", "date"));
        assertTrue(list.getOptions().stream().noneMatch(OptionData::isRequired));
        OptionData id = option(sub(data, "continue"), "id");
        assertEquals(id.getType(), OptionType.STRING);
    }

    @DataProvider
    public Object[][] listDays() {
        LocalDate day = LocalDate.of(2026, 10, 10);
        return new Object[][]{
                {"short form", "26-10-10", Optional.of(day)},
                {"ISO form", "2026-10-10", Optional.of(day)},
                {"no such day", "2026-02-30", Optional.empty()},
                {"day first", "10-10-2026", Optional.empty()},
                {"not a date", "x", Optional.empty()},
        };
    }

    @Test(dataProvider = "listDays")
    public void parsesListDay(String name, String input, Optional<LocalDate> expected) {
        assertEquals(TournamentCommand.parseListDay(input), expected, name);
    }

    @Test
    public void organizerSubcommandsNeedManageServer() {
        assertTrue(TournamentCommand.organizerProblem(false).contains("Manage Server"));
        assertNull(TournamentCommand.organizerProblem(true));
    }

    /** /tournament start by an organizer with {@link TournamentService#MIN_PLAYERS} mentions; DB work is dropped. */
    private static List<String> start(boolean lastIsMember) {
        FakeInteractions fakes = new FakeInteractions();
        Map<Long, Object> resolved = new HashMap<>();
        for (int i = 0; i < TournamentService.MIN_PLAYERS; i++) {
            long id = FIRST + i;
            boolean member = lastIsMember || i < TournamentService.MIN_PLAYERS - 1;
            resolved.put(id, member ? member(id) : FakeInteractions.user(id, "stranger"));
        }
        Object channel = FakeInteractions.fake(MessageChannelUnion.class, Map.of("canTalk", returns(true)),
                GuildMessageChannelUnion.class);
        new TournamentCommand(null, null, task -> {
        }).execute(fakes.event(Map.of("getGuild", returns(guild()), "getChannel", returns(channel),
                "getName", returns("tournament"), "getSubcommandName", returns("start"),
                "getMember", returns(member(FIRST)), "getOptions", returns(List.of(
                        stringOption("players", mentions(TournamentService.MIN_PLAYERS), resolved))))));
        return fakes.sent();
    }

    private static Member member(long id) {
        return FakeInteractions.fake(Member.class, Map.of("getIdLong", returns(id),
                "getUser", returns(FakeInteractions.user(id, "player")), "hasPermission", returns(true)));
    }

    @Test
    public void startRefusesAMentionedNonMember() {
        long last = FIRST + TournamentService.MIN_PLAYERS - 1;
        assertEquals(start(false), List.of("reply:❌ <@" + last + "> is not a member of this server. (ephemeral)"));
    }

    @Test
    public void startWithOnlyMembersGoesOnToTheDatabase() {
        assertEquals(start(true), List.of("reply (ephemeral)"));
    }

    @Test
    public void membersProblemRefusesTheFirstNonMember() {
        assertEquals(TournamentCommand.membersProblem(List.of(FIRST, SECOND), Set.of(FIRST)),
                "❌ <@" + SECOND + "> is not a member of this server.");
    }

    @Test
    public void membersProblemIsNullWhenAllAreMembers() {
        assertNull(TournamentCommand.membersProblem(List.of(FIRST, SECOND), Set.of(SECOND, FIRST)));
    }

    @Test
    public void listProblems() {
        assertEquals(TournamentCommand.listProblem(new TournamentListPage(1, 1, 0, List.of()), null),
                "No tournaments in this server yet.");
        assertTrue(TournamentCommand.listProblem(new TournamentListPage(3, 1, 2, List.of()), null)
                .contains("Page 3 does not exist"));
        assertNull(TournamentCommand.listProblem(new TournamentListPage(1, 1, 1, List.of(new TournamentSummary(
                "abcdefghj-26-10-10", LocalDate.of(2026, 10, 10), TournamentStatus.RUNNING, null))), null));
    }

    @Test
    public void readsMentionsInOrder() {
        TournamentCommand.PlayerList list = TournamentCommand.parsePlayers("<@" + SECOND + "> <@!" + FIRST + ">");
        assertNull(list.problem());
        assertEquals(list.ids(), List.of(SECOND, FIRST));
    }

    @Test
    public void commasAndLineBreaksAreFine() {
        TournamentCommand.PlayerList list = TournamentCommand.parsePlayers("<@" + FIRST + ">,\n<@" + SECOND + ">, ");
        assertEquals(list.ids(), List.of(FIRST, SECOND));
    }

    @DataProvider
    public Object[][] refusedPlayers() {
        return new Object[][]{
                {"duplicate", "<@" + FIRST + "> <@" + FIRST + ">", List.of("twice")},
                {"plain text name", "<@" + FIRST + "> @yugi", List.of("@mentions", "@yugi")},
                {"role mention", mentions(2) + " <@&" + SECOND + ">", List.of("@mentions")},
                {"one player", mentions(1), List.of("2–32")},
                {"33 players", mentions(33), List.of("2–32")},
        };
    }

    @Test(dataProvider = "refusedPlayers")
    public void parsePlayersRefusals(String name, String input, List<String> fragments) {
        String problem = TournamentCommand.parsePlayers(input).problem();
        assertNotNull(problem, name);
        for (String fragment : fragments) {
            assertTrue(problem.contains(fragment), name + ": " + problem);
        }
    }

    @Test
    public void thirtyTwoPlayersAreFine() {
        assertNull(TournamentCommand.parsePlayers(mentions(32)).problem());
    }

    @Test
    public void botCheckedManageServerSubcommands() {
        assertBotCheckedManageServer(new TournamentCommand(null, null, Runnable::run),
                "start", "continue", "standings", "cancel", "drop");
    }
}
