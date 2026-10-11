package at.magi.ygodiscordbot.impl.tournament;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * The matches of the current rounds by their 5-digit ID, and the IDs issued so far. An ID is never issued twice
 * while the bot runs, so a stale ID never points at another match. Plain class for the {@code db} thread only (like
 * {@link TournamentService}): no synchronization.
 */
final class MatchRegistry {

    private final Random random;
    private final int minId;
    private final int maxId;
    private final Set<Integer> issued = new HashSet<>();
    /** Matches of the current rounds (finished ones too, so an organizer can correct them until the round closes). */
    private final Map<Integer, ActiveMatch> matches = new HashMap<>();

    MatchRegistry(Random random, int minId, int maxId) {
        this.random = random;
        this.minId = minId;
        this.maxId = maxId;
    }

    /** Registers a match under a new ID; throws {@link IllegalStateException} when all IDs have been used. */
    ActiveMatch create(long tournamentId, int round, long player1, long player2) {
        ActiveMatch match = new ActiveMatch(newId(), tournamentId, round, player1, player2);
        matches.put(match.id(), match);
        return match;
    }

    /** The match with that ID, or null if there is none (closed round, other bot run, never issued). */
    ActiveMatch get(int id) {
        return matches.get(id);
    }

    /** Unplayed matches of the tournament's current round, by match ID. */
    List<ActiveMatch> open(ActiveTournament tournament) {
        return matches.values().stream()
                .filter(match -> match.tournamentId() == tournament.id)
                .filter(match -> !tournament.record(match.round(), match.player1()).isPlayed())
                .sorted(Comparator.comparingInt(ActiveMatch::id))
                .toList();
    }

    /** Drops the tournament's matches (round closed or tournament over); their IDs stay issued. */
    void removeAll(long tournamentId) {
        matches.values().removeIf(match -> match.tournamentId() == tournamentId);
    }

    private int newId() {
        int range = maxId - minId + 1;
        // Random pick first; if it is taken, scan from there for the next free ID (wrapping), so it always terminates
        int start = random.nextInt(range);
        for (int i = 0; i < range; i++) {
            int id = minId + (start + i) % range;
            if (issued.add(id)) {
                return id;
            }
        }
        throw new IllegalStateException("All " + range + " match IDs are used up until the bot restarts");
    }
}
