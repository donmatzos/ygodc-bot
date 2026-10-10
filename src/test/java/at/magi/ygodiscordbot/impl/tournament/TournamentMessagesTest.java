package at.magi.ygodiscordbot.impl.tournament;

import at.magi.ygodiscordbot.entity.tournament.MatchRecord;
import at.magi.ygodiscordbot.entity.tournament.Pairing;
import at.magi.ygodiscordbot.entity.tournament.Standings;
import at.magi.ygodiscordbot.entity.tournament.TournamentListPage;
import at.magi.ygodiscordbot.entity.tournament.TournamentStatus;
import at.magi.ygodiscordbot.entity.tournament.TournamentSummary;
import org.testng.annotations.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

public class TournamentMessagesTest {

    private static final String CODE = "abcdefghj-26-10-10";
    private static final Map<Long, String> NAMES = Map.of(1L, "Yugi", 2L, "Kaiba", 3L, "Joey", 4L, "Mai");

    private static String render(NamedText text) {
        return String.join("\n", text.render().apply(NAMES));
    }

    @Test
    public void standingsTableSharesRanksAndMarksDrops() {
        // Yugi and Joey are equal on everything (only the lot orders them), so are Kaiba and Mai
        Standings standings = Standings.of(List.of(1L, 2L, 3L, 4L), Set.of(4L), List.of(
                new MatchRecord(1, 1, 2L, 1L), new MatchRecord(1, 3, 4L, 3L)));
        List<Long> order = standings.ranked().stream().map(Standings.Entry::player).toList();
        assertEquals(Set.copyOf(order.subList(0, 2)), Set.of(1L, 3L));
        assertEquals(TournamentMessages.standingsTable(standings, NAMES), List.of("""
                **Standings**
                ```
                Rank  Player  W-L  OMW%%
                ----  ------  ---  ----
                   1  %-6s  1-0    33
                   1  %-6s  1-0    33
                   3  %-6s  0-1   100%s
                   3  %-6s  0-1   100%s
                ```""".formatted(NAMES.get(order.get(0)), NAMES.get(order.get(1)),
                NAMES.get(order.get(2)), order.get(2) == 4L ? " (dropped)" : "",
                NAMES.get(order.get(3)), order.get(3) == 4L ? " (dropped)" : "")));
    }

    @Test
    public void standingsTableRanksByTieBreakers() {
        // Round 1: Yugi beat Joey, Kaiba beat Mai. Round 2: Yugi and Kaiba double loss, Joey beat Mai.
        // Yugi, Kaiba, Joey are 1-1; Yugi has the best OMW% (50 %), Kaiba and Joey 42 %.
        String table = String.join("\n", TournamentMessages.standingsTable(workedExample(), NAMES));
        assertTrue(table.contains("   1  Yugi    1-1    50"), table);
        assertTrue(table.contains("   2  "), table);
        assertTrue(table.contains("  1-1    42"), table);
        assertTrue(table.contains("   4  Mai     0-2    50"), table);
    }

    @Test
    public void unknownNameFallsBack() {
        Standings standings = Standings.of(List.of(1L, 9L), Set.of(), List.of());
        String table = String.join("\n", TournamentMessages.standingsTable(standings, NAMES));
        assertTrue(table.contains("Unknown user (9)"), table);
    }

    @Test
    public void announcementNamesCodeAndDay() {
        String post = render(TournamentMessages.announced(CODE, LocalDate.of(2026, 10, 10),
                List.of(new ActiveMatch(48213, 12, 1, 1, 2)), List.of(3L)));
        assertTrue(post.contains("Tournament `" + CODE + "`"), post);
        assertTrue(post.contains("2026-10-10"), post);
        assertTrue(post.contains("`48213` · <@1> vs <@2>"), post);
        assertTrue(post.contains("<@3> gets a free win"), post);
        assertTrue(post.contains("/match finish"), post);
        assertTrue(post.contains("/match doubleloss"), post);
    }

    @Test
    public void continuePostRepeatsMatchupsAndStandings() {
        Standings standings = Standings.of(List.of(1L, 2L), Set.of(), List.of(new MatchRecord(1, 1, 2L, 1L)));
        String post = render(TournamentMessages.roundStart(CODE, 2,
                List.of(new ActiveMatch(48213, 12, 2, 1, 2)), List.of(), standings));
        assertTrue(post.contains("Tournament `" + CODE + "` · Round 2"), post);
        assertTrue(post.contains("`48213` · <@1> vs <@2>"), post);
        assertTrue(post.contains("   1  Yugi    1-0"), post);
    }

    @Test
    public void listTable() {
        TournamentListPage page = new TournamentListPage(1, 1, 3, List.of(
                new TournamentSummary("abcdefghj-26-10-10", LocalDate.of(2026, 10, 10), TournamentStatus.FINISHED, 1L),
                new TournamentSummary("kmnpqrstu-26-10-09", LocalDate.of(2026, 10, 9), TournamentStatus.ABANDONED, null),
                new TournamentSummary("vwxyz2345-26-10-09", LocalDate.of(2026, 10, 9), TournamentStatus.RUNNING, null)));
        assertEquals(render(TournamentMessages.list(page, null)), """
                ## 🏆 Tournaments

                Page 1 of 1 · 3 tournaments
                ```
                Tournament ID       Date        Winner
                ------------------  ----------  ------
                abcdefghj-26-10-10  2026-10-10  Yugi
                kmnpqrstu-26-10-09  2026-10-09  (abandoned)
                vwxyz2345-26-10-09  2026-10-09  (running)
                ```""");
        assertEquals(TournamentMessages.list(page, null).users(), Set.of(1L));
    }

    @Test
    public void listOfOneDayNamesTheDay() {
        TournamentListPage page = new TournamentListPage(1, 1, 1, List.of(new TournamentSummary(
                "abcdefghj-26-10-10", LocalDate.of(2026, 10, 10), TournamentStatus.FINISHED, 1L)));
        assertTrue(render(TournamentMessages.list(page, LocalDate.of(2026, 10, 10)))
                .contains("26-10-10 · Page 1 of 1 · 1 tournament\n"));
        assertEquals(TournamentMessages.listEmpty(LocalDate.of(2026, 10, 10)), "No tournaments on 26-10-10.");
        assertEquals(TournamentMessages.listEmpty(null), "No tournaments in this server yet.");
    }

    @Test
    public void resultDmNamesTournamentRoundAndMatch() {
        String dm = String.join("\n", TournamentMessages.matchResultDm(CODE, 2, 48213,
                new MatchRecord(2, 1, 2L, 2L), false));
        assertTrue(dm.contains("`" + CODE + "`"), dm);
        assertTrue(dm.contains("Round 2"), dm);
        assertTrue(dm.contains("`48213`"), dm);
        assertTrue(dm.contains("<@2> beat <@1>"), dm);
        String corrected = String.join("\n", TournamentMessages.matchResultDm(CODE, 2, 48213,
                new MatchRecord(2, 1, 2L, null, true), true));
        assertTrue(corrected.contains("both lose"), corrected);
        assertTrue(corrected.contains("corrected"), corrected);
    }

    @Test
    public void everyReplyNamesTheTournament() {
        assertTrue(TournamentMessages.matchFinished(CODE, 48213, 1, false).contains(CODE));
        assertTrue(TournamentMessages.doubleLossRecorded(CODE, 48213, 1, 2, false).contains(CODE));
        assertTrue(TournamentMessages.roundComplete(CODE, 1).contains(CODE));
        assertTrue(TournamentMessages.started(CODE, 4).contains(CODE));
        assertTrue(TournamentMessages.alreadyFinished(CODE, 48213, new MatchRecord(1, 1, 2L, 1L)).contains(CODE));
        assertTrue(TournamentMessages.notYourMatch(CODE, 48213).contains(CODE));
        assertTrue(TournamentMessages.winnerNotInMatch(CODE, 48213, 3L).contains(CODE));
    }

    @Test
    public void resultsNameWinnersAndByes() {
        Standings standings = Standings.of(List.of(1L, 2L, 3L), Set.of(), List.of(
                new MatchRecord(1, 1, 2L, 2L), MatchRecord.of(1, Pairing.bye(3))));
        String post = render(TournamentMessages.roundResults(CODE, 1,
                List.of(new MatchRecord(1, 1, 2L, 2L), MatchRecord.of(1, Pairing.bye(3))), standings));
        assertTrue(post.contains("<@2> beat <@1>"), post);
        assertTrue(post.contains("<@3> had a free win"), post);
        assertTrue(post.contains("**Standings**"), post);
    }

    @Test
    public void resultsWithoutStandingsHaveNoTable() {
        String post = render(TournamentMessages.roundResults(CODE, 1, List.of(new MatchRecord(1, 1, 2L, 2L)), null));
        assertTrue(!post.contains("**Standings**"), post);
    }

    @Test
    public void doubleLossesNameBothPlayers() {
        MatchRecord doubleLoss = new MatchRecord(1, 1, 2L, null, true);
        Standings standings = Standings.of(List.of(1L, 2L), Set.of(), List.of(doubleLoss));
        String post = render(TournamentMessages.roundResults(CODE, 1, List.of(doubleLoss), standings));
        assertTrue(post.contains("<@1> and <@2> both lose (time limit)"), post);
        assertTrue(TournamentMessages.alreadyFinished(CODE, 48213, doubleLoss).contains("double loss"));
    }

    @Test
    public void winnerPostWarnsAboutUnsavedPoints() {
        Standings standings = Standings.of(List.of(1L, 2L), Set.of(), List.of(new MatchRecord(1, 1, 2L, 1L)));
        String post = render(TournamentMessages.winner(CODE, 1, 1, standings, Map.of(1L, 2L), List.of(1L)));
        assertTrue(post.contains("<@1> wins after 1 round!"), post);
        assertTrue(post.contains("<@1> +2"), post);
        assertTrue(post.contains("not saved"), post);
        assertTrue(post.contains("**Standings**"), post);
    }

    private static Standings workedExample() {
        return Standings.of(List.of(1L, 2L, 3L, 4L), Set.of(), List.of(
                new MatchRecord(1, 1, 3L, 1L), new MatchRecord(1, 2, 4L, 2L),
                new MatchRecord(2, 1, 2L, null, true), new MatchRecord(2, 3, 4L, 3L)));
    }

    @Test
    public void winnerPostNamesTheDecidingTieBreaker() {
        Standings standings = workedExample();
        long runnerUp = standings.ranked().get(1).player();
        String post = render(TournamentMessages.winner(CODE, 1, 2, standings, Map.of(), List.of()));
        assertTrue(post.contains("Tied on 1 loss with <@" + runnerUp
                + ">, decided by opponents' win rate (50% vs 42%)."), post);
    }

    @Test
    public void winnerPostNamesTheLot() {
        Standings standings = Standings.of(List.of(1L, 2L), Set.of(), List.of(
                new MatchRecord(1, 1, 2L, null, true)));
        long winner = standings.ranked().get(0).player();
        long runnerUp = standings.ranked().get(1).player();
        String post = render(TournamentMessages.winner(CODE, winner, 1, standings, Map.of(), List.of()));
        assertTrue(post.contains("Tied on 1 loss with <@" + runnerUp + ">, all tie-breakers equal, decided by lot."),
                post);
    }

    @Test
    public void clearWinnerHasNoTieLine() {
        Standings standings = Standings.of(List.of(1L, 2L), Set.of(), List.of(new MatchRecord(1, 1, 2L, 1L)));
        String post = render(TournamentMessages.winner(CODE, 1, 1, standings, Map.of(), List.of()));
        assertTrue(!post.contains("Tied"), post);
    }

    @Test
    public void nextPairingsTellHowToContinue() {
        String post = render(TournamentMessages.nextPairings(CODE, 2,
                List.of(new MatchRecord(2, 1, 3L, null), MatchRecord.of(2, Pairing.bye(2)))));
        assertTrue(post.contains("<@1> vs <@3>"), post);
        assertTrue(post.contains("<@2> gets a free win"), post);
        assertTrue(post.contains("/tournament continue id:" + CODE), post);
    }

    @Test
    public void thenKeepsOrderAndJoinsUsers() {
        NamedText both = NamedText.plain("first").then(new NamedText(Set.of(1L), names -> List.of(names.get(1L))));
        assertEquals(both.render().apply(NAMES), List.of("first", "Yugi"));
        assertEquals(both.users(), Set.of(1L));
    }

    @Test
    public void invalidCodeShowsTheExample() {
        String reply = TournamentMessages.invalidCode("nope`");
        assertTrue(reply.contains("nope'"), reply);
        assertTrue(reply.contains(at.magi.ygodiscordbot.entity.tournament.TournamentCode.EXAMPLE), reply);
    }
}
