package at.magi.ygodiscordbot.entity.tournament;

import org.testng.annotations.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.testng.Assert.assertEquals;

public class WinnerRuleTest {

    private static final long A = 1, B = 2, C = 3, D = 4;

    private static MatchRecord won(int round, long winner, long loser) {
        return new MatchRecord(round, winner, loser, winner);
    }

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
                won(1, D, B), won(1, A, e), MatchRecord.of(1, Pairing.bye(C)),
                won(2, D, C), won(2, B, A), MatchRecord.of(2, Pairing.bye(e))));
        assertEquals(WinnerRule.winner(standings, 2), Optional.of(D));
    }

    @Test
    public void byeKeepsAPlayerUndefeated() {
        Standings standings = Standings.of(List.of(A, B, C), Set.of(), List.of(
                won(1, A, B), MatchRecord.of(1, Pairing.bye(C))));
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
        Standings standings = Standings.of(List.of(A, B, C, D), Set.of(), List.of(
                won(1, A, C), won(1, B, D), new MatchRecord(2, A, B, null, true), won(2, C, D)));
        assertEquals(WinnerRule.winner(standings, 2), Optional.of(A));
    }

    @Test
    public void tiedLeadersBeforeTheMinimumPlayOn() {
        Standings standings = Standings.of(List.of(A, B, C, D), Set.of(), List.of(
                won(1, A, C), new MatchRecord(1, B, D, null, true)));
        assertEquals(WinnerRule.winner(standings, 1), Optional.empty());
    }

    @Test
    public void droppedPlayerNeverWinsOnTieBreak() {
        // Same as above, but A dropped after round 2: B and C share the lead and are equal → the lot decides
        Standings standings = Standings.of(List.of(A, B, C, D), Set.of(A), List.of(
                won(1, A, C), won(1, B, D), new MatchRecord(2, A, B, null, true), won(2, C, D)));
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
