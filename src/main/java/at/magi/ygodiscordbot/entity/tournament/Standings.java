package at.magi.ygodiscordbot.entity.tournament;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Win-loss records of a tournament's players, computed from its match records. A bye counts as a win, a double loss
 * as a loss for both; an unplayed pairing counts for nothing except "have met", so a posted pairing is never repeated.
 */
public final class Standings {

    /** One player's record; {@code wins} includes byes. */
    public record Entry(long player, int wins, int losses, int byes, boolean dropped) {

        /** Wins against a real opponent; only these earn leaderboard points. */
        public int realWins() {
            return wins - byes;
        }
    }

    /** Fewest losses first, then most wins, then player ID so the order is stable. */
    public static final Comparator<Entry> RANKING = Comparator.comparingInt(Entry::losses)
            .thenComparing(Comparator.comparingInt(Entry::wins).reversed())
            .thenComparingLong(Entry::player);

    private static final int WINS = 0;
    private static final int LOSSES = 1;
    private static final int BYES = 2;

    private final List<Long> players;
    private final Map<Long, Entry> entries;
    private final Map<Long, Set<Long>> opponents;
    private final boolean doubleLossOrDrop;

    private Standings(List<Long> players, Map<Long, Entry> entries, Map<Long, Set<Long>> opponents,
                      boolean doubleLossOrDrop) {
        this.players = players;
        this.entries = entries;
        this.opponents = opponents;
        this.doubleLossOrDrop = doubleLossOrDrop;
    }

    /** @param players every player in entry order, dropped ones included */
    public static Standings of(List<Long> players, Set<Long> dropped, List<MatchRecord> matches) {
        Map<Long, int[]> counts = new HashMap<>();
        Map<Long, Set<Long>> opponents = new HashMap<>();
        for (long player : players) {
            counts.put(player, new int[3]);
            opponents.put(player, new HashSet<>());
        }
        for (MatchRecord match : matches) {
            if (!match.isBye()) {
                opponents.get(match.player1()).add(match.player2());
                opponents.get(match.player2()).add(match.player1());
            }
            if (!match.isPlayed()) {
                continue;
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
            }
        }
        Map<Long, Entry> entries = new HashMap<>();
        for (long player : players) {
            int[] count = counts.get(player);
            entries.put(player, new Entry(player, count[WINS], count[LOSSES], count[BYES], dropped.contains(player)));
        }
        boolean doubleLossOrDrop = !dropped.isEmpty() || matches.stream().anyMatch(MatchRecord::doubleLoss);
        return new Standings(List.copyOf(players), entries, opponents, doubleLossOrDrop);
    }

    public Entry entry(long player) {
        Entry entry = entries.get(player);
        if (entry == null) {
            throw new IllegalArgumentException("Not a player of this tournament: " + player);
        }
        return entry;
    }

    /** Every player, dropped ones included, best first. */
    public List<Entry> ranked() {
        return entries.values().stream().sorted(RANKING).toList();
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
