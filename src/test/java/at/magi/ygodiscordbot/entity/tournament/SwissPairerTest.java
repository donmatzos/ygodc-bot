package at.magi.ygodiscordbot.entity.tournament;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.stream.IntStream;

import static at.magi.ygodiscordbot.entity.tournament.TournamentFixtures.A;
import static at.magi.ygodiscordbot.entity.tournament.TournamentFixtures.B;
import static at.magi.ygodiscordbot.entity.tournament.TournamentFixtures.C;
import static at.magi.ygodiscordbot.entity.tournament.TournamentFixtures.D;
import static at.magi.ygodiscordbot.entity.tournament.TournamentFixtures.E;
import static at.magi.ygodiscordbot.entity.tournament.TournamentFixtures.bye;
import static at.magi.ygodiscordbot.entity.tournament.TournamentFixtures.players;
import static at.magi.ygodiscordbot.entity.tournament.TournamentFixtures.won;
import static at.magi.ygodiscordbot.entity.tournament.TournamentFixtures.workedExample;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

public class SwissPairerTest {

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

    /** Extra invariants of one simulated round, checked on top of those in {@link #simulate}. */
    private interface RoundCheck {
        void check(Standings standings, Round round, int roundNumber);
    }

    private record Outcome(int playOffs) {
    }

    private static final int DOUBLE_LOSS_PERCENT = 15;

    @DataProvider
    public static Object[][] sizes() {
        return IntStream.rangeClosed(2, 32).mapToObj(size -> new Object[]{size}).toArray(Object[][]::new);
    }

    /**
     * Plays one whole tournament with random results (a match is a double loss with the given chance) and asserts
     * after every round: {@code round.playOff()} is {@link WinnerRule#playOff}, a plain round pairs every active player
     * once (at most one bye, last), a play-off round pairs exactly the tied leaders, plus the given check. In the end
     * the winner is the top-ranked player, with the fewest losses, and the tournament did not run longer than allowed:
     * {@link WinnerRule#minimumRounds}, or a sanity bound of 20 for play-offs (in the bot the 48-hour timeout ends
     * endless tournaments). Failures name size and seed.
     */
    private static Outcome simulate(int size, int seed, int doubleLossPercent, RoundCheck check) {
        try {
            Random random = new Random(seed * 1000L + size);
            List<Long> players = players(1, size);
            List<MatchRecord> matches = new ArrayList<>();
            Optional<Long> winner = Optional.empty();
            int minimum = WinnerRule.minimumRounds(size);
            int bound = doubleLossPercent == 0 || minimum < WinnerRule.PLAY_OFF_ROUNDS ? minimum : 20;
            int playOffs = 0;
            int round = 0;
            while (winner.isEmpty()) {
                round++;
                assertTrue(round <= bound, "needs round " + round);
                Standings standings = Standings.of(players, Set.of(), matches, seed);
                Round pairing = SwissPairer.pair(standings, round, random);
                List<Long> playOff = WinnerRule.playOff(standings, round - 1);
                assertEquals(pairing.playOff(), playOff, "round " + round + " play-off");
                if (playOff.isEmpty()) {
                    assertComplete(pairing.pairings(), standings);
                } else {
                    assertEquals(paired(pairing.pairings()), Set.copyOf(playOff), "round " + round + " play-off");
                    playOffs++;
                }
                check.check(standings, pairing, round);
                for (Pairing p : pairing.pairings()) {
                    MatchRecord match = MatchRecord.of(round, p);
                    if (!p.isBye()) {
                        match = doubleLossPercent > 0 && random.nextInt(100) < doubleLossPercent ? match.asDoubleLoss()
                                : match.withWinner(random.nextBoolean() ? p.player1() : p.player2());
                    }
                    matches.add(match);
                }
                winner = WinnerRule.winner(Standings.of(players, Set.of(), matches, seed), round);
            }
            Standings last = Standings.of(players, Set.of(), matches, seed);
            assertEquals(winner.get(), Long.valueOf(last.ranked().get(0).player()), "winner");
            assertEquals(last.entry(winner.get()).losses(), last.ranked().get(0).losses(), "winner losses");
            if (last.hasDoubleLossOrDrop()) {
                assertTrue(round >= minimum, "winner after only " + round + " rounds");
            }
            return new Outcome(playOffs);
        } catch (AssertionError e) {
            throw new AssertionError("size " + size + " seed " + seed + ": " + e.getMessage(), e);
        }
    }

    /** Without rematches, and the bye (round 2 on) to a player with the most losses. */
    private static void assertNoRematchAndByeToMostLosses(Standings standings, Round round, int roundNumber) {
        for (Pairing pairing : round.pairings()) {
            if (pairing.isBye()) {
                if (roundNumber > 1) {
                    int byeLosses = standings.entry(pairing.player1()).losses();
                    for (long other : standings.active()) {
                        assertTrue(standings.entry(other).losses() <= byeLosses,
                                "bye must go to the most losses: " + pairing);
                    }
                }
            } else if (round.playOff().isEmpty()) {
                assertFalse(standings.haveMet(pairing.player1(), pairing.player2()), "rematch: " + pairing);
            }
        }
    }

    /**
     * Whole tournaments with random results for every size 2…32 except 5 (see
     * {@link #fivePlayersRematchOnlyWhenUnavoidable}) and several seeds: a winner is clear after ⌈log₂ n⌉ rounds or
     * one earlier (an undefeated player lost a play-down match), without rematches.
     */
    @Test(dataProvider = "sizes", timeOut = 20_000)
    public void everySizeEndsWithAUniqueWinnerWithoutRematches(int size) {
        if (size == 5) {
            return;
        }
        for (int seed = 0; seed < 20; seed++) {
            simulate(size, seed, 0, SwissPairerTest::assertNoRematchAndByeToMostLosses);
        }
    }

    /**
     * With 5 players everyone has met all others after 4 rounds and a bye, and the forced bye can make the
     * rematch-free pairings split the two undefeated players. Then a rematch or a bye to the next player down is
     * allowed, but only when no rematch-free pairing with a most-losses bye exists (checked by brute force). Leaders
     * still tied after ⌈log₂ 5⌉ rounds are decided by the tie-breakers. The brute-force branch never fires for these
     * seeds (random results keep a rematch-free pairing available); the hand-built histories of
     * {@link #byeGoesToTheNextPlayerDownToAvoidARematch} and {@link #oneRematchWhenNoRematchFreePairingExists} pin
     * down what the pairer does when it matters.
     */
    @Test(timeOut = 20_000)
    public void fivePlayersRematchOnlyWhenUnavoidable() {
        for (int seed = 0; seed < 200; seed++) {
            simulate(5, seed, 0, (standings, round, roundNumber) -> {
                List<Pairing> pairings = round.pairings();
                long bye = pairings.get(pairings.size() - 1).player1();
                boolean rematch = pairings.stream()
                        .anyMatch(p -> !p.isBye() && standings.haveMet(p.player1(), p.player2()));
                int mostLosses = standings.ranked().get(standings.ranked().size() - 1).losses();
                if (roundNumber > 1 && (rematch || standings.entry(bye).losses() < mostLosses)) {
                    assertFalse(rematchFreeWithMostLossesBye(standings, mostLosses),
                            "round " + roundNumber + " had a better pairing than " + pairings);
                }
            });
        }
    }

    /**
     * After three rounds D has beaten E every time. Giving the bye to E (3 losses, the most) would leave A, B, C, D
     * with only rematches; giving it to C (2 losses, the next player down) leaves a rematch-free pairing, which wins.
     */
    @Test
    public void byeGoesToTheNextPlayerDownToAvoidARematch() {
        Standings standings = Standings.of(List.of(A, B, C, D, E), Set.of(), List.of(
                bye(1, A), won(1, B, C), won(1, D, E),
                bye(2, B), won(2, A, C), won(2, D, E),
                bye(3, C), won(3, A, B), won(3, D, E)));
        assertEquals(standings.entry(E).losses(), 3);
        assertEquals(standings.entry(C).losses(), 2);
        for (int seed = 0; seed < 20; seed++) {
            List<Pairing> pairings = SwissPairer.pair(standings, 4, new Random(seed)).pairings();
            assertComplete(pairings, standings);
            assertEquals(pairings.get(2), Pairing.bye(C), "seed " + seed + ": " + pairings);
            assertTrue(pairings.subList(0, 2).stream().noneMatch(p -> standings.haveMet(p.player1(), p.player2())),
                    "seed " + seed + ": rematch in " + pairings);
        }
    }

    /**
     * B, C, D and E have met each other, A has met nobody (three byes). Whoever gets the bye, the other four contain
     * at least one pair that has met: the pairer takes exactly one rematch and gives the bye to E, who has the most
     * losses.
     */
    @Test
    public void oneRematchWhenNoRematchFreePairingExists() {
        Standings standings = Standings.of(List.of(A, B, C, D, E), Set.of(), List.of(
                bye(1, A), won(1, B, C), won(1, D, E),
                bye(2, A), won(2, B, D), won(2, C, E),
                bye(3, A), won(3, B, E), won(3, C, D)));
        assertEquals(standings.entry(E).losses(), 3);
        for (int seed = 0; seed < 20; seed++) {
            List<Pairing> pairings = SwissPairer.pair(standings, 4, new Random(seed)).pairings();
            assertComplete(pairings, standings);
            assertEquals(pairings.get(2), Pairing.bye(E), "seed " + seed + ": " + pairings);
            assertEquals(pairings.subList(0, 2).stream()
                    .filter(p -> standings.haveMet(p.player1(), p.player2())).count(), 1L, "seed " + seed + ": " + pairings);
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

    /**
     * Same with 15 % double losses: rounds stay complete (play-offs: only the tied leaders play) and the winner has the
     * fewest losses.
     */
    @Test(dataProvider = "sizes", timeOut = 20_000)
    public void doubleLossesStillEndWithAWinner(int size) {
        int playOffs = 0;
        for (int seed = 0; seed < 20; seed++) {
            playOffs += simulate(size, seed, DOUBLE_LOSS_PERCENT, (standings, round, roundNumber) -> { }).playOffs();
        }
        // From 9 players on, ⌈log₂ n⌉ ≥ PLAY_OFF_ROUNDS: tied leaders play off instead of going to the tie-breakers
        if (WinnerRule.minimumRounds(size) >= WinnerRule.PLAY_OFF_ROUNDS) {
            assertTrue(playOffs > 0, "size " + size + ": no simulation reached a play-off");
        }
    }

    private static Set<Long> paired(List<Pairing> pairings) {
        Set<Long> paired = new HashSet<>();
        for (Pairing pairing : pairings) {
            paired.add(pairing.player1());
            if (!pairing.isBye()) {
                paired.add(pairing.player2());
            }
        }
        return paired;
    }

    @Test
    public void playOffPairsOnlyTheTiedLeaders() {
        // Rounds 3 and 4 left out: only the records matter here
        Standings standings = workedExample();
        List<Pairing> pairings = SwissPairer.pair(standings, 5, new Random(1)).pairings();
        assertEquals(paired(pairings), Set.of(1L, 2L, 3L));
        assertEquals(pairings.size(), 2);
        assertTrue(pairings.get(1).isBye());
    }

    @Test
    public void playOffAllowsARematch() {
        // Two players, 1-1 each after beating each other once (rounds 3 and 4 left out): they meet again
        Standings standings = Standings.of(List.of(1L, 2L), Set.of(), List.of(
                new MatchRecord(1, 1, 2L, 1L), new MatchRecord(2, 1, 2L, 2L)));
        assertEquals(WinnerRule.playOff(standings, 4), standings.activeRanked());
        Round round = SwissPairer.pair(standings, 5, new Random(1));
        List<Pairing> pairings = round.pairings();
        assertEquals(pairings.size(), 1);
        assertEquals(Set.of(pairings.get(0).player1(), pairings.get(0).player2()), Set.of(1L, 2L));
        assertEquals(round.playOff(), WinnerRule.playOff(standings, 4));
    }

    @Test
    public void roundsBeforeTheFourthHaveNoPlayOff() {
        assertEquals(SwissPairer.pair(Standings.of(players(1, 4), Set.of(), List.of()), 1, new Random(1)).playOff(),
                List.of());

        // After 3 rounds players 1 and 2 are tied at 2-1 (3 and 4 at 1-2): too early to play off, round 4 is a plain round
        List<MatchRecord> matches = new ArrayList<>(List.of(
                new MatchRecord(1, 1, 2L, 1L), new MatchRecord(1, 3, 4L, 3L),
                new MatchRecord(2, 1, 3L, 1L), new MatchRecord(2, 2, 4L, 2L),
                new MatchRecord(3, 1, 4L, 4L), new MatchRecord(3, 2, 3L, 2L)));
        Round round = SwissPairer.pair(Standings.of(players(1, 4), Set.of(), matches), 4, new Random(1));
        assertEquals(round.playOff(), List.of());
        assertEquals(round.pairings().size(), 2);

        // After 4 rounds 1 and 2 are still tied (3-1): now they play off
        matches.addAll(List.of(new MatchRecord(4, 1, 3L, 1L), new MatchRecord(4, 2, 4L, 2L)));
        Standings fourRounds = Standings.of(players(1, 4), Set.of(), matches);
        assertEquals(SwissPairer.pair(fourRounds, 5, new Random(1)).playOff(), List.of(1L, 2L));
    }

    @Test
    public void sameRecordsArePairedTogether() {
        // After round 1: 1 and 3 are 1-0, 2 and 4 are 0-1
        List<MatchRecord> matches = List.of(new MatchRecord(1, 1, 2L, 1L), new MatchRecord(1, 3, 4L, 3L));
        Standings standings = Standings.of(players(1, 4), Set.of(), matches);
        List<Pairing> pairings = SwissPairer.pair(standings, 2, new Random(1)).pairings();
        for (Pairing pairing : pairings) {
            assertEquals(standings.entry(pairing.player1()).losses(), standings.entry(pairing.player2()).losses());
        }
    }

    @Test
    public void byeGoesToTheMostLossesAndPrefersNoSecondBye() {
        // 5 had a bye in round 1; 2 and 4 lost. Round 2: the bye goes to 2 or 4, never to 5.
        List<MatchRecord> matches = List.of(new MatchRecord(1, 1, 2L, 1L), new MatchRecord(1, 3, 4L, 3L),
                MatchRecord.of(1, Pairing.bye(5)));
        Standings standings = Standings.of(players(1, 5), Set.of(), matches);
        for (int seed = 0; seed < 50; seed++) {
            List<Pairing> pairings = SwissPairer.pair(standings, 2, new Random(seed)).pairings();
            long bye = pairings.get(pairings.size() - 1).player1();
            assertTrue(bye == 2 || bye == 4, "bye went to " + bye);
        }
    }

    @Test
    public void firstRoundByeIsRandom() {
        Set<Long> byes = new HashSet<>();
        Standings standings = Standings.of(players(1, 3), Set.of(), List.of());
        for (int seed = 0; seed < 50; seed++) {
            List<Pairing> pairings = SwissPairer.pair(standings, 1, new Random(seed)).pairings();
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
        Standings standings = Standings.of(players(1, 4), Set.of(), matches);
        List<Pairing> pairings = SwissPairer.pair(standings, 4, new Random(3)).pairings();
        assertComplete(pairings, standings);

        // 1 met only 2: a rematch-free pairing exists and must be found
        Standings partial = Standings.of(players(1, 4), Set.of(), List.of(new MatchRecord(1, 1, 2L, 1L),
                new MatchRecord(1, 3, 4L, 4L)));
        for (Pairing pairing : SwissPairer.pair(partial, 2, new Random(5)).pairings()) {
            assertFalse(partial.haveMet(pairing.player1(), pairing.player2()), pairing.toString());
        }
    }

    @Test
    public void droppedPlayersAreNotPaired() {
        Standings standings = Standings.of(players(1, 4), Set.of(3L), List.of());
        List<Pairing> pairings = SwissPairer.pair(standings, 1, new Random(0)).pairings();
        assertComplete(pairings, standings);
        assertTrue(pairings.stream().noneMatch(p -> p.player1() == 3 || Long.valueOf(3).equals(p.player2())));
    }

    @Test
    public void needsTwoActivePlayers() {
        Standings standings = Standings.of(players(1, 2), Set.of(2L), List.of());
        expectThrows(IllegalArgumentException.class, () -> SwissPairer.pair(standings, 1, new Random(0)));
    }
}
