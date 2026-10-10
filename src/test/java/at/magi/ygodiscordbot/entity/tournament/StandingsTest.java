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
    public void ranksByFewestLossesThenMostWinsThenTieBreakers() {
        // The spec's example: A 2-0, B and C 1-1, D 0-2
        Standings standings = Standings.of(List.of(D, C, B, A), Set.of(), List.of(
                won(1, A, B), won(1, C, D), won(2, A, C), won(2, B, D)));
        // B: opponents A (1) and D (1/3) → 2/3; C: D (1/3) and A (1) → 2/3. Same OOMW% too, never met → lot
        assertEquals(standings.ranked().get(0).player(), A);
        assertEquals(standings.ranked().get(3).player(), D);
        assertEquals(standings.rank(B), 2);
        assertEquals(standings.rank(C), 2);
        assertEquals(standings.decidedBy(standings.ranked().get(1).player(), standings.ranked().get(2).player()),
                Standings.Decider.LOT);
    }

    // --- Tie-breakers ---

    /** Round 1: A beat C, B beat D. Round 2: A and B double loss, C beat D. A, B, C are 1-1, D 0-2. */
    private static Standings workedExample() {
        return Standings.of(List.of(A, B, C, D), Set.of(), List.of(
                won(1, A, C), won(1, B, D), new MatchRecord(2, A, B, null, true), won(2, C, D)));
    }

    @Test
    public void workedExampleFigures() {
        Standings standings = workedExample();
        double third = 1.0 / 3;
        assertEquals(standings.tieBreaks(A).omw(), 0.5, 1e-9);
        assertEquals(standings.tieBreaks(B).omw(), (third + 0.5) / 2, 1e-9);     // D's 0-2 is floored at 1/3
        assertEquals(standings.tieBreaks(C).omw(), (0.5 + third) / 2, 1e-9);
        assertEquals(standings.tieBreaks(D).omw(), 0.5, 1e-9);
        assertEquals(standings.tieBreaks(A).oomw(), (third + 0.5) / 2, 1e-9);
        assertEquals(standings.tieBreaks(B).oomw(), 0.5, 1e-9);
        assertEquals(standings.tieBreaks(C).oomw(), 0.5, 1e-9);
        assertEquals(standings.tieBreaks(D).oomw(), (third + 0.5) / 2, 1e-9);
    }

    @Test
    public void ranksTiedRecordsByOpponentWinRate() {
        Standings standings = workedExample();
        List<Long> order = standings.ranked().stream().map(Standings.Entry::player).toList();
        assertEquals(order.get(0), Long.valueOf(A));
        assertEquals(Set.copyOf(order.subList(1, 3)), Set.of(B, C));
        assertEquals(order.get(3), Long.valueOf(D));
        assertEquals(standings.rank(A), 1);
        assertEquals(standings.rank(B), 2);
        assertEquals(standings.rank(C), 2);
        assertEquals(standings.rank(D), 4);
        assertEquals(standings.decidedBy(A, B), Standings.Decider.OMW);
        assertEquals(standings.decidedBy(A, D), Standings.Decider.RECORD);
    }

    @Test
    public void byesAreNoOpponentsButCountAsWins() {
        // Round 1: A beat B, C bye. Round 2: A beat C, B bye.
        Standings standings = Standings.of(List.of(A, B, C), Set.of(), List.of(
                won(1, A, B), MatchRecord.of(1, Pairing.bye(C)), won(2, A, C), MatchRecord.of(2, Pairing.bye(B))));
        // B and C are 1-1 including their bye → MW% 1/2 each
        assertEquals(standings.tieBreaks(A).omw(), 0.5, 1e-9);
        // B's and C's only real opponent is A (MW% 1)
        assertEquals(standings.tieBreaks(B).omw(), 1.0, 1e-9);
        assertEquals(standings.tieBreaks(C).omw(), 1.0, 1e-9);
    }

    @Test
    public void onlyByesGiveTheFloor() {
        Standings standings = Standings.of(List.of(A, B, C), Set.of(), List.of(
                won(1, A, B), MatchRecord.of(1, Pairing.bye(C))));
        assertEquals(standings.tieBreaks(C).omw(), 1.0 / 3, 1e-9);
        assertEquals(standings.tieBreaks(C).oomw(), 1.0 / 3, 1e-9);
        assertEquals(standings.tieBreaks(A).omw(), 1.0 / 3, 1e-9);   // B is 0-1
    }

    @Test
    public void unplayedPairingsDontCountForTieBreakers() {
        Standings standings = Standings.of(List.of(A, B, C, D), Set.of(), List.of(
                won(1, A, B), won(1, C, D), new MatchRecord(2, A, C, null)));
        assertEquals(standings.tieBreaks(A).omw(), 1.0 / 3, 1e-9);   // only B, not C
    }

    @Test
    public void droppedOpponentStillCounts() {
        Standings standings = Standings.of(List.of(A, B, C, D), Set.of(B), List.of(
                won(1, B, A), won(1, C, D)));
        assertEquals(standings.tieBreaks(A).omw(), 1.0, 1e-9);
    }

    @Test
    public void oomwBreaksEqualOmw() {
        // 8 players, 2 rounds: 2 and 5 are 2-0 with OMW% 5/12; OOMW% 3/4 vs 17/24
        long p0 = 10, p1 = 11, p2 = 12, p3 = 13, p4 = 14, p5 = 15, p6 = 16, p7 = 17;
        Standings standings = Standings.of(List.of(p0, p1, p2, p3, p4, p5, p6, p7), Set.of(), List.of(
                won(1, p2, p0), won(1, p6, p1), won(1, p5, p7), won(1, p3, p4),
                won(2, p7, p0), won(2, p2, p3), won(2, p4, p6), won(2, p5, p1)));
        assertEquals(standings.tieBreaks(p2).omw(), 5.0 / 12, 1e-9);
        assertEquals(standings.tieBreaks(p5).omw(), 5.0 / 12, 1e-9);
        assertEquals(standings.ranked().get(0).player(), p2);
        assertEquals(standings.ranked().get(1).player(), p5);
        assertEquals(standings.rank(p5), 2);
        assertEquals(standings.decidedBy(p2, p5), Standings.Decider.OOMW);
    }

    /** 6 players, 3 rounds: 5 leads on OMW%, then 1 and 2 are equal on everything but 2 beat 1. */
    static Standings headToHeadExample() {
        long p0 = 10, p1 = 11, p2 = 12, p3 = 13, p4 = 14, p5 = 15;
        return Standings.of(List.of(p0, p1, p2, p3, p4, p5), Set.of(), List.of(
                won(1, p0, p3), won(1, p1, p4), won(1, p5, p2),
                won(2, p2, p3), won(2, p1, p5), won(2, p0, p4),
                won(3, p3, p4), won(3, p2, p1), won(3, p5, p0)));
    }

    @Test
    public void headToHeadBreaksAPairOfEqualPlayers() {
        Standings standings = headToHeadExample();
        assertEquals(standings.ranked().stream().map(Standings.Entry::player).toList(),
                List.of(15L, 12L, 11L, 10L, 13L, 14L));
        assertEquals(standings.rank(12), 2);
        assertEquals(standings.rank(11), 3);
        assertEquals(standings.decidedBy(12, 11), Standings.Decider.HEAD_TO_HEAD);
        assertEquals(standings.decidedBy(15, 12), Standings.Decider.OMW);
    }

    @Test
    public void lotIsDeterministic() {
        assertEquals(workedExample().ranked(), workedExample().ranked());
    }

    @Test
    public void lotDiffersBetweenTournaments() {
        // The same two players with equal everything: across tournaments either may come first
        boolean lowerFirst = false;
        boolean higherFirst = false;
        for (long tournament = 1; tournament < 40; tournament++) {
            long first = Standings.of(List.of(A, B), Set.of(), List.of(), tournament).ranked().get(0).player();
            lowerFirst |= first == A;
            higherFirst |= first == B;
        }
        assertTrue(lowerFirst && higherFirst);
    }

    @Test
    public void headToHeadIsNotUsedForThreeEqualPlayers() {
        // A beat B, C beat A, B beat C, each with one bye: all 2-1 with equal figures, so only the lot orders them
        for (long tournament = 1; tournament < 20; tournament++) {
            Standings standings = Standings.of(List.of(A, B, C), Set.of(), List.of(
                    won(1, A, B), MatchRecord.of(1, Pairing.bye(C)),
                    won(2, C, A), MatchRecord.of(2, Pairing.bye(B)),
                    won(3, B, C), MatchRecord.of(3, Pairing.bye(A))), tournament);
            List<Standings.Entry> ranked = standings.ranked();
            assertEquals(standings.decidedBy(ranked.get(0).player(), ranked.get(1).player()), Standings.Decider.LOT);
            assertEquals(standings.decidedBy(ranked.get(1).player(), ranked.get(2).player()), Standings.Decider.LOT);
            assertEquals(standings.decidedBy(ranked.get(0).player(), ranked.get(2).player()), Standings.Decider.LOT);
            assertEquals(List.of(standings.rank(A), standings.rank(B), standings.rank(C)), List.of(1, 1, 1));
        }
    }

    @Test
    public void droppedPlayersDontBlockHeadToHead() {
        // A beat B, X beat Y, Y beat A, B beat X: all four 1-1 with equal figures. X and Y dropped, so A and B are the
        // only active players of the group and A's win over B decides, whatever the lot.
        long x = 7, y = 8;
        for (long tournament = 1; tournament < 20; tournament++) {
            Standings standings = Standings.of(List.of(A, B, x, y), Set.of(x, y), List.of(
                    won(1, A, B), won(1, x, y), won(2, y, A), won(2, B, x)), tournament);
            assertEquals(standings.activeRanked(), List.of(A, B));
            assertEquals(standings.decidedBy(A, B), Standings.Decider.HEAD_TO_HEAD);
            assertTrue(standings.rank(B) > standings.rank(A), "tournament " + tournament);
        }
    }

    @Test
    public void activeRankedLeavesOutDroppedPlayers() {
        Standings standings = Standings.of(List.of(A, B, C), Set.of(A), List.of(won(1, A, B)));
        assertEquals(standings.activeRanked().size(), 2);
        assertFalse(standings.activeRanked().contains(A));
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
