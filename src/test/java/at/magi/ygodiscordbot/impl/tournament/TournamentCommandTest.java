package at.magi.ygodiscordbot.impl.tournament;

import at.magi.ygodiscordbot.entity.tournament.TournamentListPage;
import at.magi.ygodiscordbot.entity.tournament.TournamentStatus;
import at.magi.ygodiscordbot.entity.tournament.TournamentSummary;
import net.dv8tion.jda.api.interactions.InteractionContextType;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import org.testng.annotations.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import static org.testng.Assert.assertEquals;
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
        var page = new TournamentCommand(null, null, Runnable::run).data().getSubcommands().get(5).getOptions().get(0);
        assertEquals(page.getMinValue().longValue(), 1L);
        assertEquals(page.getMaxValue().longValue(), 10_000L);
    }

    @Test
    public void openToEveryoneInServersWithList() {
        var data = new TournamentCommand(null, null, Runnable::run).data();
        assertEquals(data.getName(), "tournament");
        assertEquals(data.getDefaultPermissions(), DefaultMemberPermissions.ENABLED);
        assertEquals(data.getContexts(), Set.of(InteractionContextType.GUILD));
        assertEquals(data.getSubcommands().stream().map(SubcommandData::getName).toList(),
                List.of("start", "continue", "standings", "cancel", "drop", "list"));
        SubcommandData list = data.getSubcommands().get(5);
        assertEquals(list.getOptions().stream().map(OptionData::getName).toList(), List.of("page", "date"));
        assertTrue(list.getOptions().stream().noneMatch(OptionData::isRequired));
        OptionData id = data.getSubcommands().get(1).getOptions().get(0);
        assertEquals(id.getType(), OptionType.STRING);
    }

    @Test
    public void listDateAcceptsShortAndIsoForms() {
        java.time.LocalDate day = java.time.LocalDate.of(2026, 10, 10);
        assertEquals(TournamentCommand.parseListDay("26-10-10"), java.util.Optional.of(day));
        assertEquals(TournamentCommand.parseListDay("2026-10-10"), java.util.Optional.of(day));
        assertEquals(TournamentCommand.parseListDay("2026-02-30"), java.util.Optional.empty());
        assertEquals(TournamentCommand.parseListDay("10-10-2026"), java.util.Optional.empty());
        assertEquals(TournamentCommand.parseListDay("x"), java.util.Optional.empty());
    }

    @Test
    public void organizerSubcommandsNeedManageServer() {
        assertTrue(TournamentCommand.organizerProblem(false).contains("Manage Server"));
        assertNull(TournamentCommand.organizerProblem(true));
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

    @Test
    public void duplicatesAreRefused() {
        String problem = TournamentCommand.parsePlayers("<@" + FIRST + "> <@" + FIRST + ">").problem();
        assertTrue(problem.contains("twice"), problem);
    }

    @Test
    public void plainTextNamesAreRefused() {
        String problem = TournamentCommand.parsePlayers("<@" + FIRST + "> @yugi").problem();
        assertTrue(problem.contains("@mentions"), problem);
        assertTrue(problem.contains("@yugi"), problem);
    }

    @Test
    public void roleMentionsAreRefused() {
        String problem = TournamentCommand.parsePlayers(mentions(2) + " <@&" + SECOND + ">").problem();
        assertTrue(problem.contains("@mentions"), problem);
    }

    @Test
    public void playerCountIsLimited() {
        assertTrue(TournamentCommand.parsePlayers(mentions(1)).problem().contains("2–32"));
        assertTrue(TournamentCommand.parsePlayers(mentions(33)).problem().contains("2–32"));
        assertNull(TournamentCommand.parsePlayers(mentions(32)).problem());
    }

    @Test
    public void botCheckedManageServerSubcommands() {
        assertEquals(new TournamentCommand(null, null, Runnable::run).botCheckedManageServer(), Set.of("start", "continue", "standings", "cancel", "drop"));
    }
}
