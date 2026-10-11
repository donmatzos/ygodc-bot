package at.magi.ygodiscordbot.entity.tournament;

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

    @Test
    public void minimumRoundsIsLog2OfThePlayers() {
        assertEquals(WinnerRule.minimumRounds(2), 1);
        assertEquals(WinnerRule.minimumRounds(3), 2);
        assertEquals(WinnerRule.minimumRounds(4), 2);
        assertEquals(WinnerRule.minimumRounds(5), 3);
        assertEquals(WinnerRule.minimumRounds(32), 5);
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

    @Test
    public void tieBreakerDecidesTiedLeadersAfterTheMinimum() {
        // Both undefeated players ran out of time in round 2: A, B and C share 1 loss, A has the best OMW%
        assertEquals(WinnerRule.winner(workedExample(), 2), Optional.of(A));
    }

    @Test
    public void tieBreakerDecidesUpToThreeRounds() {
        assertEquals(WinnerRule.winner(workedExample(), 3), Optional.of(A));
        assertEquals(WinnerRule.playOff(workedExample(), 3), List.of());
    }

    @Test
    public void tiedLeadersPlayOffFromFourRounds() {
        for (int rounds = 4; rounds <= 6; rounds++) {
            assertEquals(WinnerRule.winner(workedExample(), rounds), Optional.empty());
            assertEquals(WinnerRule.playOff(workedExample(), rounds), workedExample().activeRanked().subList(0, 3));
        }
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
