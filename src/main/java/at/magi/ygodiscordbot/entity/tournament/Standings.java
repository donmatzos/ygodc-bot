package at.magi.ygodiscordbot.entity.tournament;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Win-loss records of a tournament's players, computed from its match records. A bye counts as a win, a double loss
 * as a loss for both; an unplayed pairing counts for nothing except "have met", so a posted pairing is never repeated.
 *
 * <p>Equal records are ranked by tie-breakers, as in Magic tournaments: OMW% (average match-win rate of the real
 * opponents of played matches; each rate floored at 1/3, byes are no opponents), then OOMW% (average OMW% of the same
 * opponents), then head-to-head (only when exactly two active players are equal), then a lot that is fixed per
 * tournament. {@link #ranked()}, {@link #rank} and {@link #decidedBy} all come from the one ordering in the constructor.
 */
public final class Standings {

    /** One player's record; {@code wins} includes byes. */
    public record Entry(long player, int wins, int losses, int byes, boolean dropped) {

        /** Wins against a real opponent; only these earn leaderboard points. */
        public int realWins() {
            return wins - byes;
        }
    }

    /** A player's tie-breakers; both rates are between 1/3 and 1. */
    public record TieBreaks(double omw, double oomw) {
    }

    /** The first ranking step that separates two players. */
    public enum Decider {
        RECORD, OMW, OOMW, HEAD_TO_HEAD, LOT
    }

    /**
     * Fewest losses, most wins, best OMW%, best OOMW%; the rates in units of 0.1 % ({@link #permille}), the precision
     * posts show, so float noise never decides and two rates that differ also look different.
     */
    record Key(int losses, int wins, long omw, long oomw) {

        static Key of(Entry entry, TieBreaks tieBreaks) {
            return new Key(entry.losses(), entry.wins(), permille(tieBreaks.omw()), permille(tieBreaks.oomw()));
        }
    }

    /** A rate in units of 0.1 %, as compared and shown. */
    public static long permille(double rate) {
        return Math.round(rate * 1000);
    }

    static final Comparator<Key> BY_KEY = Comparator.comparingInt(Key::losses)
            .thenComparing(Comparator.comparingInt(Key::wins).reversed())
            .thenComparing(Comparator.comparingLong(Key::omw).reversed())
            .thenComparing(Comparator.comparingLong(Key::oomw).reversed());

    private static final double FLOOR = 1.0 / 3;

    private static final int WINS = 0;
    private static final int LOSSES = 1;
    private static final int BYES = 2;

    private final List<Long> players;
    private final Map<Long, Entry> entries;
    private final Map<Long, Set<Long>> opponents;
    private final boolean doubleLossOrDrop;
    private final Map<Long, TieBreaks> tieBreaks;
    private final Map<Long, Map<Long, Integer>> winsAgainst;
    private final long seed;
    private final Map<Long, Key> keys = new HashMap<>();
    /** Pairs of equal players that head-to-head ordered; every other equal pair is ordered by the lot. */
    private final Set<Set<Long>> headToHeadPairs = new HashSet<>();
    private final List<Entry> ranked;
    private final Map<Long, Integer> ranks = new HashMap<>();

    private Standings(List<Long> players, Map<Long, Entry> entries, Map<Long, Set<Long>> opponents,
                      boolean doubleLossOrDrop, Map<Long, TieBreaks> tieBreaks,
                      Map<Long, Map<Long, Integer>> winsAgainst, long seed) {
        this.players = players;
        this.entries = entries;
        this.opponents = opponents;
        this.doubleLossOrDrop = doubleLossOrDrop;
        this.tieBreaks = tieBreaks;
        this.winsAgainst = winsAgainst;
        this.seed = seed;
        entries.forEach((player, entry) -> keys.put(player, Key.of(entry, tieBreaks.get(player))));
        this.ranked = rank();
        // A player shares the rank of the group above when only the lot separates it from everyone in that group
        List<Long> group = new ArrayList<>();
        for (int i = 0; i < ranked.size(); i++) {
            long player = ranked.get(i).player();
            if (!group.isEmpty() && group.stream().allMatch(other -> decidedBy(other, player) == Decider.LOT)) {
                ranks.put(player, ranks.get(group.get(0)));
            } else {
                group.clear();
                ranks.put(player, i + 1);
            }
            group.add(player);
        }
    }

    /**
     * Standings with lot seed 0. Only for callers that don't rank tied players (round-1 pairing) and for tests; a
     * running tournament uses {@link #of(List, Set, List, long)} with its ID.
     */
    public static Standings of(List<Long> players, Set<Long> dropped, List<MatchRecord> matches) {
        return of(players, dropped, matches, 0);
    }

    /**
     * @param players every player in entry order, dropped ones included
     * @param seed    the tournament's ID: the lot is the same after a restart and in every post, but differs between
     *                tournaments of the same players
     */
    public static Standings of(List<Long> players, Set<Long> dropped, List<MatchRecord> matches, long seed) {
        Map<Long, int[]> counts = new HashMap<>();
        Map<Long, Set<Long>> opponents = new HashMap<>();
        Map<Long, List<Long>> played = new HashMap<>();
        Map<Long, Map<Long, Integer>> winsAgainst = new HashMap<>();
        for (long player : players) {
            counts.put(player, new int[3]);
            opponents.put(player, new HashSet<>());
            played.put(player, new ArrayList<>());
            winsAgainst.put(player, new HashMap<>());
        }
        for (MatchRecord match : matches) {
            if (!match.isBye()) {
                opponents.get(match.player1()).add(match.player2());
                opponents.get(match.player2()).add(match.player1());
            }
            if (!match.isPlayed()) {
                continue;
            }
            if (!match.isBye()) {
                played.get(match.player1()).add(match.player2());
                played.get(match.player2()).add(match.player1());
            }
            if (match.doubleLoss()) {
                counts.get(match.player1())[LOSSES]++;
                counts.get(match.player2())[LOSSES]++;
                continue;
            }
            counts.get(match.winner())[WINS]++;
            if (match.isBye()) {
                counts.get(match.player1())[BYES]++;
            } else {
                counts.get(match.loser())[LOSSES]++;
                winsAgainst.get(match.winner()).merge(match.loser(), 1, Integer::sum);
            }
        }
        Map<Long, Entry> entries = new HashMap<>();
        for (long player : players) {
            int[] count = counts.get(player);
            entries.put(player, new Entry(player, count[WINS], count[LOSSES], count[BYES], dropped.contains(player)));
        }
        boolean doubleLossOrDrop = !dropped.isEmpty() || matches.stream().anyMatch(MatchRecord::doubleLoss);
        return new Standings(List.copyOf(players), entries, opponents, doubleLossOrDrop,
                tieBreaks(players, counts, played), winsAgainst, seed);
    }

    private static Map<Long, TieBreaks> tieBreaks(List<Long> players, Map<Long, int[]> counts,
                                                  Map<Long, List<Long>> played) {
        Map<Long, Double> matchWin = new HashMap<>();
        for (long player : players) {
            int[] count = counts.get(player);
            int matchesPlayed = count[WINS] + count[LOSSES];
            matchWin.put(player, matchesPlayed == 0 ? FLOOR : Math.max(FLOOR, (double) count[WINS] / matchesPlayed));
        }
        Map<Long, Double> omw = new HashMap<>();
        for (long player : players) {
            omw.put(player, average(played.get(player), matchWin));
        }
        Map<Long, TieBreaks> tieBreaks = new HashMap<>();
        for (long player : players) {
            tieBreaks.put(player, new TieBreaks(omw.get(player), average(played.get(player), omw)));
        }
        return tieBreaks;
    }

    /** The average of {@code rates} over {@code opponents}; the floor without opponents. */
    private static double average(List<Long> opponents, Map<Long, Double> rates) {
        return opponents.isEmpty() ? FLOOR
                : opponents.stream().mapToDouble(rates::get).average().getAsDouble();
    }

    /**
     * By key, then by lot. In a group of equal keys with exactly two active players, the one who beat the other more
     * often goes first (they swap places if the lot had them the other way round); dropped players don't count.
     */
    private List<Entry> rank() {
        List<Entry> sorted = new ArrayList<>(entries.values());
        sorted.sort(Comparator.comparing((Entry entry) -> keys.get(entry.player()), BY_KEY)
                .thenComparingLong(entry -> lot(entry.player())));
        int start = 0;
        while (start < sorted.size()) {
            Key key = keys.get(sorted.get(start).player());
            List<Integer> active = new ArrayList<>();
            int end = start;
            while (end < sorted.size() && keys.get(sorted.get(end).player()).equals(key)) {
                if (!sorted.get(end).dropped()) {
                    active.add(end);
                }
                end++;
            }
            if (active.size() == 2) {
                long upper = sorted.get(active.get(0)).player();
                long lower = sorted.get(active.get(1)).player();
                int result = headToHead(upper, lower);
                if (result != 0) {
                    headToHeadPairs.add(Set.of(upper, lower));
                }
                if (result < 0) {
                    Collections.swap(sorted, active.get(0), active.get(1));
                }
            }
            start = end;
        }
        return List.copyOf(sorted);
    }

    /** Positive when {@code player} won more matches against {@code other} than the other way round. */
    private int headToHead(long player, long other) {
        return winsAgainst.get(player).getOrDefault(other, 0) - winsAgainst.get(other).getOrDefault(player, 0);
    }

    /** A fixed random number per player and tournament (SplittableRandom's mix function). */
    private long lot(long player) {
        long z = player ^ seed;
        z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
        z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
        return z ^ (z >>> 31);
    }

    public Entry entry(long player) {
        Entry entry = entries.get(player);
        if (entry == null) {
            throw new IllegalArgumentException("Not a player of this tournament: " + player);
        }
        return entry;
    }

    public TieBreaks tieBreaks(long player) {
        entry(player);
        return tieBreaks.get(player);
    }

    /** Every player, dropped ones included, best first. */
    public List<Entry> ranked() {
        return ranked;
    }

    /** 1-based place in {@link #ranked()}; players only the lot separates share a place (1, 2, 2, 4). */
    public int rank(long player) {
        entry(player);
        return ranks.get(player);
    }

    /** Players who have not dropped, best first. */
    public List<Long> activeRanked() {
        return ranked.stream().filter(entry -> !entry.dropped()).map(Entry::player).toList();
    }

    /** The ranking step that put {@code better} ahead of {@code worse}; {@code better} must rank above. */
    public Decider decidedBy(long better, long worse) {
        Key first = keys.get(entry(better).player());
        Key second = keys.get(entry(worse).player());
        if (first.losses() != second.losses() || first.wins() != second.wins()) {
            return Decider.RECORD;
        }
        if (first.omw() != second.omw()) {
            return Decider.OMW;
        }
        if (first.oomw() != second.oomw()) {
            return Decider.OOMW;
        }
        return headToHeadPairs.contains(Set.of(better, worse)) ? Decider.HEAD_TO_HEAD : Decider.LOT;
    }

    /** Players who have not dropped, in entry order. */
    public List<Long> active() {
        return players.stream().filter(player -> !entries.get(player).dropped()).toList();
    }

    public boolean haveMet(long player, long other) {
        return opponents.get(player).contains(other);
    }

    /** True once a player dropped or a match ended as a double loss. */
    public boolean hasDoubleLossOrDrop() {
        return doubleLossOrDrop;
    }

    /** Everyone who started, dropped players included. */
    public int playerCount() {
        return players.size();
    }
}
