package at.magi.ygodiscordbot.entity.tournament;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static at.magi.ygodiscordbot.entity.tournament.TournamentFixtures.A;
import static at.magi.ygodiscordbot.entity.tournament.TournamentFixtures.B;
import static at.magi.ygodiscordbot.entity.tournament.TournamentFixtures.C;
import static at.magi.ygodiscordbot.entity.tournament.TournamentFixtures.D;
import static at.magi.ygodiscordbot.entity.tournament.TournamentFixtures.bye;
import static at.magi.ygodiscordbot.entity.tournament.TournamentFixtures.doubleLoss;
import static at.magi.ygodiscordbot.entity.tournament.TournamentFixtures.players;
import static at.magi.ygodiscordbot.entity.tournament.TournamentFixtures.won;
import static at.magi.ygodiscordbot.entity.tournament.TournamentFixtures.workedExample;
import static org.testng.Assert.assertEquals;

public class WinnerRuleTest {

    @DataProvider
    public static Object[][] minimumRounds() {
        return new Object[][]{
                {"0 players", 0, 0}, {"1 player", 1, 0}, {"2 players", 2, 1}, {"3 players", 3, 2},
                {"4 players", 4, 2}, {"5 players", 5, 3}, {"8 players", 8, 3}, {"9 players", 9, 4},
                {"32 players", 32, 5}};
    }

    @Test(dataProvider = "minimumRounds")
    public void minimumRoundsIsLog2OfThePlayers(String name, int players, int expected) {
        assertEquals(WinnerRule.minimumRounds(players), expected, name);
    }

    @Test
    public void noWinnerWhileSeveralAreUndefeated() {
        Standings standings = Standings.of(List.of(A, B, C, D), Set.of(), List.of(won(1, A, B), won(1, C, D)));
        assertEquals(WinnerRule.winner(standings, 1), Optional.empty());
    }

    @Test
    public void onlyUndefeatedPlayerWins() {
        // The spec's example: A 2-0, B and C 1-1, D 0-2
        Standings standings = Standings.of(List.of(A, B, C, D), Set.of(), List.of(
                won(1, A, B), won(1, C, D), won(2, A, C), won(2, B, D)));
        assertEquals(WinnerRule.winner(standings, 2), Optional.of(A));
    }

    @Test
    public void earlyLeaderWinsWithoutDoubleLossOrDrop() {
        // 5 players: after round 2 only D is undefeated (C lost a play-down match), one round before ⌈log₂ 5⌉ = 3.
        // Nothing irregular happened, so D has won.
        long e = 5;
        Standings standings = Standings.of(List.of(A, B, C, D, e), Set.of(), List.of(
                won(1, D, B), won(1, A, e), bye(1, C),
                won(2, D, C), won(2, B, A), bye(2, e)));
        assertEquals(WinnerRule.winner(standings, 2), Optional.of(D));
    }

    @Test
    public void byeKeepsAPlayerUndefeated() {
        Standings standings = Standings.of(List.of(A, B, C), Set.of(), List.of(
                won(1, A, B), bye(1, C)));
        assertEquals(WinnerRule.winner(standings, 1), Optional.empty());
    }

    @Test
    public void doubleLossDoesNotEndTheTournamentEarly() {
        // C is the only player without a loss after round 1, but 4 players play at least 2 rounds
        Standings standings = Standings.of(List.of(A, B, C, D), Set.of(), List.of(
                new MatchRecord(1, A, B, null, true), won(1, C, D)));
        assertEquals(WinnerRule.winner(standings, 1), Optional.empty());
        assertEquals(WinnerRule.winner(standings, 2), Optional.of(C));
    }

    @DataProvider
    public static Object[][] tiedLeadersByRounds() {
        // A, B and C share 1 loss and the lead; A has the best OMW%. From PLAY_OFF_ROUNDS on, the three play off.
        return new Object[][]{
                {"2 rounds: tie-breaker", 2, true}, {"3 rounds: tie-breaker", 3, true},
                {"4 rounds: play-off", 4, false}, {"5 rounds: play-off", 5, false}, {"6 rounds: play-off", 6, false}};
    }

    @Test(dataProvider = "tiedLeadersByRounds")
    public void tiedLeadersAreDecidedByTieBreakerOrPlayOff(String name, int rounds, boolean tieBreaker) {
        Optional<Long> expectedWinner = tieBreaker ? Optional.of(A) : Optional.empty();
        List<Long> expectedPlayOff = tieBreaker ? List.of() : workedExample().activeRanked().subList(0, 3);
        assertEquals(WinnerRule.winner(workedExample(), rounds), expectedWinner, name);
        assertEquals(WinnerRule.playOff(workedExample(), rounds), expectedPlayOff, name);
    }

    @Test
    public void uniqueLeaderWinsAfterAPlayOff() {
        // Play-off round 5: A beat B, C had the bye, so A and C are still tied; round 6: A beat C
        List<MatchRecord> matches = new java.util.ArrayList<>(List.of(
                won(1, A, C), won(1, B, D), doubleLoss(2, A, B), won(2, C, D),
                won(5, A, B), bye(5, C)));
        Standings afterFive = Standings.of(List.of(A, B, C, D), Set.of(), matches);
        assertEquals(WinnerRule.winner(afterFive, 5), Optional.empty());
        assertEquals(Set.copyOf(WinnerRule.playOff(afterFive, 5)), Set.of(A, C));
        matches.add(won(6, A, C));
        Standings afterSix = Standings.of(List.of(A, B, C, D), Set.of(), matches);
        assertEquals(WinnerRule.winner(afterSix, 6), Optional.of(A));
        assertEquals(WinnerRule.playOff(afterSix, 6), List.of());
    }

    @Test
    public void noPlayOffBeforeTheMinimum() {
        // 16 players need 4 rounds; with two undefeated after 4 rounds they play off, after 3 they play on normally
        Standings standings = Standings.of(players(1, 16), Set.of(), List.of(won(1, 1, 2), won(1, 3, 4)));
        assertEquals(WinnerRule.playOff(standings, 3), List.of());
        assertEquals(WinnerRule.playOff(standings, 4).size(), 14);   // everyone without a loss
    }

    @Test
    public void tiedLeadersBeforeTheMinimumPlayOn() {
        Standings standings = Standings.of(List.of(A, B, C, D), Set.of(), List.of(
                won(1, A, C), doubleLoss(1, B, D)));
        assertEquals(WinnerRule.winner(standings, 1), Optional.empty());
    }

    @Test
    public void droppedPlayerNeverWinsOnTieBreak() {
        // Same as above, but A dropped after round 2: B and C share the lead and are equal → the lot decides
        Standings standings = workedExample(Set.of(A));
        Optional<Long> winner = WinnerRule.winner(standings, 2);
        assertEquals(winner, Optional.of(standings.ranked().get(1).player()));
        assertEquals(standings.ranked().get(0).player(), A);
    }

    @Test
    public void droppedLeaderHandsTheLeadToTheFewestLosses() {
        Standings standings = Standings.of(List.of(A, B, C, D), Set.of(A), List.of(
                won(1, A, B), won(1, C, D), won(2, C, B)));
        assertEquals(WinnerRule.winner(standings, 2), Optional.of(C));
    }

    @Test
    public void lastActivePlayerWinsEvenBeforeTheMinimum() {
        Standings standings = Standings.of(List.of(A, B, C, D), Set.of(B, C, D), List.of());
        assertEquals(WinnerRule.winner(standings, 1), Optional.of(A));
    }

    @Test
    public void nobodyLeftMeansNoWinner() {
        Standings standings = Standings.of(List.of(A, B), Set.of(A, B), List.of());
        assertEquals(WinnerRule.winner(standings, 5), Optional.empty());
    }
}
