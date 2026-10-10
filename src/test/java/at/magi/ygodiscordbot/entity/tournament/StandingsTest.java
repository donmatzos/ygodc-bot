package at.magi.ygodiscordbot.entity.tournament;

import org.testng.annotations.Test;

import java.util.List;
import java.util.Set;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

public class StandingsTest {

    private static final long A = 1, B = 2, C = 3, D = 4, E = 5;

    private static MatchRecord won(int round, long winner, long loser) {
        return new MatchRecord(round, winner, loser, winner);
    }

    @Test
    public void countsWinsLossesAndByes() {
        Standings standings = Standings.of(List.of(A, B, C), Set.of(), List.of(
                won(1, A, B), MatchRecord.of(1, Pairing.bye(C))));
        assertEquals(standings.entry(A), new Standings.Entry(A, 1, 0, 0, false));
        assertEquals(standings.entry(B), new Standings.Entry(B, 0, 1, 0, false));
        assertEquals(standings.entry(C), new Standings.Entry(C, 1, 0, 1, false));
        assertEquals(standings.entry(C).realWins(), 0);
    }

    @Test
    public void ranksByFewestLossesThenMostWinsThenId() {
        // The spec's example: A 2-0, B and C 1-1, D 0-2
        Standings standings = Standings.of(List.of(D, C, B, A), Set.of(), List.of(
                won(1, A, B), won(1, C, D), won(2, A, C), won(2, B, D)));
        assertEquals(standings.ranked().stream().map(Standings.Entry::player).toList(), List.of(A, B, C, D));
    }

    @Test
    public void unplayedPairingsCountOnlyAsMet() {
        Standings standings = Standings.of(List.of(A, B), Set.of(), List.of(new MatchRecord(1, A, B, null)));
        assertTrue(standings.haveMet(A, B));
        assertTrue(standings.haveMet(B, A));
        assertEquals(standings.entry(A).wins(), 0);
        assertEquals(standings.entry(B).losses(), 0);
    }

    @Test
    public void byesAreNoMeeting() {
        Standings standings = Standings.of(List.of(A, B, C), Set.of(), List.of(
                won(1, A, B), MatchRecord.of(1, Pairing.bye(C))));
        assertFalse(standings.haveMet(C, A));
        assertFalse(standings.haveMet(C, B));
    }

    @Test
    public void droppedPlayersStayInStandingsButAreNotActive() {
        Standings standings = Standings.of(List.of(A, B, C, D, E), Set.of(B), List.of());
        assertEquals(standings.active(), List.of(A, C, D, E));
        assertTrue(standings.entry(B).dropped());
        assertEquals(standings.ranked().size(), 5);
    }

    @Test
    public void matchRecordHelpers() {
        MatchRecord open = new MatchRecord(2, A, B, null);
        assertFalse(open.isPlayed());
        assertNull(open.loser());
        assertEquals(open.status(), MatchStatus.RUNNING);
        MatchRecord done = open.withWinner(B);
        assertEquals(done.loser(), Long.valueOf(A));
        assertEquals(done.status(), MatchStatus.FINISHED);
        assertTrue(done.involves(B));
        assertFalse(done.involves(C));
        MatchRecord bye = MatchRecord.of(3, Pairing.bye(C));
        assertTrue(bye.isBye());
        assertTrue(bye.isPlayed());
        assertEquals(bye.winner(), Long.valueOf(C));
        assertNull(bye.loser());
    }

    @Test
    public void doubleLossIsALossForBoth() {
        MatchRecord doubleLoss = new MatchRecord(1, A, B, null).asDoubleLoss();
        assertTrue(doubleLoss.isPlayed());
        assertTrue(doubleLoss.doubleLoss());
        assertEquals(doubleLoss.status(), MatchStatus.FINISHED);
        assertNull(doubleLoss.winner());
        assertNull(doubleLoss.loser());
        assertEquals(doubleLoss.withWinner(A), new MatchRecord(1, A, B, A));
        Standings standings = Standings.of(List.of(A, B), Set.of(), List.of(doubleLoss));
        assertEquals(standings.entry(A), new Standings.Entry(A, 0, 1, 0, false));
        assertEquals(standings.entry(B), new Standings.Entry(B, 0, 1, 0, false));
        assertTrue(standings.haveMet(A, B));
        assertEquals(standings.playerCount(), 2);
    }

    @Test
    public void doubleLossNeedsTwoPlayersAndNoWinner() {
        expectThrows(IllegalArgumentException.class, () -> new MatchRecord(1, A, B, A, true));
        expectThrows(IllegalArgumentException.class, () -> new MatchRecord(1, A, null, null, true));
    }
}
