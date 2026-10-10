package at.magi.ygodiscordbot.entity.tournament;

import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.stream.LongStream;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

public class SwissPairerTest {

    private static List<Long> players(int count) {
        return LongStream.rangeClosed(1, count).boxed().toList();
    }

    private static int roundsFor(int players) {
        return 32 - Integer.numberOfLeadingZeros(players - 1); // ⌈log₂ n⌉
    }

    /** Every active player exactly once, at most one bye, and the bye last. */
    private static void assertComplete(List<Pairing> pairings, Standings standings) {
        Set<Long> seen = new HashSet<>();
        for (Pairing pairing : pairings) {
            assertTrue(seen.add(pairing.player1()), "paired twice: " + pairing);
            if (!pairing.isBye()) {
                assertTrue(seen.add(pairing.player2()), "paired twice: " + pairing);
            }
        }
        assertEquals(seen, new HashSet<>(standings.active()));
        long byes = pairings.stream().filter(Pairing::isBye).count();
        assertEquals(byes, standings.active().size() % 2);
        if (byes == 1) {
            assertTrue(pairings.get(pairings.size() - 1).isBye());
        }
    }

    /**
     * Plays whole tournaments with random results for every size 2…32 except 5 (see
     * {@link #fivePlayersRematchOnlyWhenUnavoidable}) and several seeds: a winner is clear after ⌈log₂ n⌉ rounds or
     * one earlier (an undefeated player lost a play-down match), without rematches.
     */
    @Test(timeOut = 20_000)
    public void everySizeEndsWithAUniqueWinnerWithoutRematches() {
        for (int size = 2; size <= 32; size++) {
            if (size == 5) {
                continue;
            }
            for (int seed = 0; seed < 20; seed++) {
                Random random = new Random(seed * 1000L + size);
                List<Long> players = players(size);
                List<MatchRecord> matches = new ArrayList<>();
                Optional<Long> winner = Optional.empty();
                int round = 0;
                while (winner.isEmpty()) {
                    round++;
                    assertTrue(round <= roundsFor(size), "size " + size + " seed " + seed + " needs round " + round);
                    Standings standings = Standings.of(players, Set.of(), matches);
                    List<Pairing> pairings = SwissPairer.pair(standings, round, random);
                    assertComplete(pairings, standings);
                    for (Pairing pairing : pairings) {
                        if (pairing.isBye()) {
                            if (round > 1) {
                                int byeLosses = standings.entry(pairing.player1()).losses();
                                for (long other : standings.active()) {
                                    assertTrue(standings.entry(other).losses() <= byeLosses,
                                            "bye must go to the most losses: " + pairing);
                                }
                            }
                            matches.add(MatchRecord.of(round, pairing));
                        } else {
                            assertFalse(standings.haveMet(pairing.player1(), pairing.player2()),
                                    "rematch in size " + size + " seed " + seed + ": " + pairing);
                            long won = random.nextBoolean() ? pairing.player1() : pairing.player2();
                            matches.add(MatchRecord.of(round, pairing).withWinner(won));
                        }
                    }
                    winner = WinnerRule.winner(Standings.of(players, Set.of(), matches), round);
                }
                Standings last = Standings.of(players, Set.of(), matches);
                assertEquals(last.entry(winner.get()).losses(), last.ranked().get(0).losses());
            }
        }
    }

    /**
     * With 5 players everyone has met all others after 4 rounds and a bye, and the forced bye can make the
     * rematch-free pairings split the two undefeated players. Then a rematch or a bye to the next player down is
     * allowed, but only when no rematch-free pairing with a most-losses bye exists (checked by brute force). Leaders
     * still tied after ⌈log₂ 5⌉ rounds are decided by the tie-breakers.
     */
    @Test(timeOut = 20_000)
    public void fivePlayersRematchOnlyWhenUnavoidable() {
        for (int seed = 0; seed < 200; seed++) {
            Random random = new Random(seed * 1000L + 5);
            List<Long> players = players(5);
            List<MatchRecord> matches = new ArrayList<>();
            Optional<Long> winner = Optional.empty();
            int round = 0;
            while (winner.isEmpty()) {
                round++;
                assertTrue(round <= roundsFor(5), "seed " + seed + " needs round " + round);
                Standings standings = Standings.of(players, Set.of(), matches);
                List<Pairing> pairings = SwissPairer.pair(standings, round, random);
                assertComplete(pairings, standings);
                long bye = pairings.get(pairings.size() - 1).player1();
                boolean rematch = pairings.stream()
                        .anyMatch(p -> !p.isBye() && standings.haveMet(p.player1(), p.player2()));
                int mostLosses = standings.ranked().get(standings.ranked().size() - 1).losses();
                if (round > 1 && (rematch || standings.entry(bye).losses() < mostLosses)) {
                    assertFalse(rematchFreeWithMostLossesBye(standings, mostLosses),
                            "seed " + seed + " round " + round + " had a better pairing than " + pairings);
                }
                for (Pairing pairing : pairings) {
                    matches.add(pairing.isBye() ? MatchRecord.of(round, pairing) : MatchRecord.of(round, pairing)
                            .withWinner(random.nextBoolean() ? pairing.player1() : pairing.player2()));
                }
                winner = WinnerRule.winner(Standings.of(players, Set.of(), matches), round);
            }
        }
    }

    /** Brute force over all byes and pairings of a small field. */
    private static boolean rematchFreeWithMostLossesBye(Standings standings, int mostLosses) {
        for (long bye : standings.active()) {
            if (standings.entry(bye).losses() == mostLosses) {
                List<Long> rest = new ArrayList<>(standings.active());
                rest.remove(Long.valueOf(bye));
                if (pairsWithoutRematch(rest, standings)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean pairsWithoutRematch(List<Long> players, Standings standings) {
        if (players.isEmpty()) {
            return true;
        }
        long first = players.get(0);
        for (long other : players.subList(1, players.size())) {
            if (!standings.haveMet(first, other)) {
                List<Long> rest = new ArrayList<>(players.subList(1, players.size()));
                rest.remove(Long.valueOf(other));
                if (pairsWithoutRematch(rest, standings)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Same with 15 % double losses: rounds stay complete and the winner has the fewest losses. */
    @Test(timeOut = 20_000)
    public void doubleLossesStillEndWithAWinner() {
        for (int size = 2; size <= 32; size++) {
            for (int seed = 0; seed < 20; seed++) {
                Random random = new Random(seed * 1000L + size);
                List<Long> players = players(size);
                List<MatchRecord> matches = new ArrayList<>();
                Optional<Long> winner = Optional.empty();
                int round = 0;
                while (winner.isEmpty()) {
                    round++;
                    // Tied leaders are decided by the tie-breakers after ⌈log₂ n⌉ rounds
                    assertTrue(round <= roundsFor(size), "size " + size + " seed " + seed + " needs round " + round);
                    Standings standings = Standings.of(players, Set.of(), matches);
                    List<Pairing> pairings = SwissPairer.pair(standings, round, random);
                    assertComplete(pairings, standings);
                    for (Pairing pairing : pairings) {
                        MatchRecord match = MatchRecord.of(round, pairing);
                        if (!pairing.isBye()) {
                            match = random.nextInt(100) < 15 ? match.asDoubleLoss()
                                    : match.withWinner(random.nextBoolean() ? pairing.player1() : pairing.player2());
                        }
                        matches.add(match);
                    }
                    winner = WinnerRule.winner(Standings.of(players, Set.of(), matches), round);
                }
                Standings last = Standings.of(players, Set.of(), matches);
                assertEquals(winner.get(), Long.valueOf(last.ranked().get(0).player()));
                if (last.hasDoubleLossOrDrop()) {
                    assertTrue(round >= WinnerRule.minimumRounds(size));
                }
            }
        }
    }

    @Test
    public void sameRecordsArePairedTogether() {
        // After round 1: 1 and 3 are 1-0, 2 and 4 are 0-1
        List<MatchRecord> matches = List.of(new MatchRecord(1, 1, 2L, 1L), new MatchRecord(1, 3, 4L, 3L));
        Standings standings = Standings.of(players(4), Set.of(), matches);
        List<Pairing> pairings = SwissPairer.pair(standings, 2, new Random(1));
        for (Pairing pairing : pairings) {
            assertEquals(standings.entry(pairing.player1()).losses(), standings.entry(pairing.player2()).losses());
        }
    }

    @Test
    public void byeGoesToTheMostLossesAndPrefersNoSecondBye() {
        // 5 had a bye in round 1; 2 and 4 lost. Round 2: the bye goes to 2 or 4, never to 5.
        List<MatchRecord> matches = List.of(new MatchRecord(1, 1, 2L, 1L), new MatchRecord(1, 3, 4L, 3L),
                MatchRecord.of(1, Pairing.bye(5)));
        Standings standings = Standings.of(players(5), Set.of(), matches);
        for (int seed = 0; seed < 50; seed++) {
            List<Pairing> pairings = SwissPairer.pair(standings, 2, new Random(seed));
            long bye = pairings.get(pairings.size() - 1).player1();
            assertTrue(bye == 2 || bye == 4, "bye went to " + bye);
        }
    }

    @Test
    public void firstRoundByeIsRandom() {
        Set<Long> byes = new HashSet<>();
        Standings standings = Standings.of(players(3), Set.of(), List.of());
        for (int seed = 0; seed < 50; seed++) {
            List<Pairing> pairings = SwissPairer.pair(standings, 1, new Random(seed));
            byes.add(pairings.get(pairings.size() - 1).player1());
        }
        assertEquals(byes, Set.of(1L, 2L, 3L));
    }

    @Test
    public void rematchOnlyWhenUnavoidableAndAsFewAsPossible() {
        // 4 players who all met each other: round 4 needs exactly 2 rematches (one per match), nothing more
        List<MatchRecord> matches = List.of(
                new MatchRecord(1, 1, 2L, 1L), new MatchRecord(1, 3, 4L, 3L),
                new MatchRecord(2, 1, 3L, 1L), new MatchRecord(2, 2, 4L, 2L),
                new MatchRecord(3, 1, 4L, 1L), new MatchRecord(3, 2, 3L, 2L));
        Standings standings = Standings.of(players(4), Set.of(), matches);
        List<Pairing> pairings = SwissPairer.pair(standings, 4, new Random(3));
        assertComplete(pairings, standings);

        // 1 met only 2: a rematch-free pairing exists and must be found
        Standings partial = Standings.of(players(4), Set.of(), List.of(new MatchRecord(1, 1, 2L, 1L),
                new MatchRecord(1, 3, 4L, 4L)));
        for (Pairing pairing : SwissPairer.pair(partial, 2, new Random(5))) {
            assertFalse(partial.haveMet(pairing.player1(), pairing.player2()), pairing.toString());
        }
    }

    @Test
    public void droppedPlayersAreNotPaired() {
        Standings standings = Standings.of(players(4), Set.of(3L), List.of());
        List<Pairing> pairings = SwissPairer.pair(standings, 1, new Random(0));
        assertComplete(pairings, standings);
        assertTrue(pairings.stream().noneMatch(p -> p.player1() == 3 || Long.valueOf(3).equals(p.player2())));
    }

    @Test
    public void needsTwoActivePlayers() {
        Standings standings = Standings.of(players(2), Set.of(2L), List.of());
        expectThrows(IllegalArgumentException.class, () -> SwissPairer.pair(standings, 1, new Random(0)));
    }
}
