package at.magi.ygodiscordbot.entity.tournament;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Random;

/**
 * Swiss pairing: players with the same record meet, nobody meets the same opponent twice unless no other pairing
 * exists (then as few rematches as possible). With an odd count one player gets a bye: in round 1 a random one,
 * later the one with the most losses, then the fewest byes so far.
 *
 * <p>Backtracking over the record-sorted players is enough for at most 32 players and ~6 rounds: everyone has met
 * only a few of up to 31 others, so the first branch nearly always completes. A weighted matching (Edmonds' blossom)
 * would only matter if pairing quality had to go beyond "same record, no rematch".
 */
public final class SwissPairer {

    private SwissPairer() {
    }

    /**
     * The pairings of {@code round}; a bye, if any, comes last. All previous rounds must be closed. In a
     * {@link WinnerRule#playOff} round only the tied leaders are paired, with the same rules among them (so a rematch
     * only when they have all met); the others sit the round out.
     */
    public static List<Pairing> pair(Standings standings, int round, Random random) {
        List<Long> playOff = round > 1 ? WinnerRule.playOff(standings, round - 1) : List.of();
        List<Long> players = new ArrayList<>(playOff.isEmpty() ? standings.active() : playOff);
        if (players.size() < 2) {
            throw new IllegalArgumentException("Need at least 2 active players: " + players);
        }
        Collections.shuffle(players, random);
        List<Long> byeCandidates = new ArrayList<>(players);
        if (round > 1) {
            // Stable sorts: equal records keep the random order
            players.sort(Comparator.comparingInt(player -> standings.entry(player).losses()));
            byeCandidates.sort(Comparator.<Long>comparingInt(player -> standings.entry(player).losses()).reversed()
                    .thenComparingInt(player -> standings.entry(player).byes()));
        }
        boolean odd = players.size() % 2 == 1;
        for (int allowedRematches = 0; allowedRematches <= players.size() / 2; allowedRematches++) {
            if (!odd) {
                List<Pairing> found = search(players, standings, allowedRematches);
                if (found != null) {
                    return found;
                }
                continue;
            }
            for (long bye : byeCandidates) {
                List<Long> rest = new ArrayList<>(players);
                rest.remove(Long.valueOf(bye));
                List<Pairing> found = search(rest, standings, allowedRematches);
                if (found != null) {
                    found.add(Pairing.bye(bye));
                    return found;
                }
            }
        }
        throw new IllegalStateException("No pairing found although rematches were allowed: " + players);
    }

    private static List<Pairing> search(List<Long> players, Standings standings, int allowedRematches) {
        Deque<Pairing> chosen = new ArrayDeque<>();
        return search(players, standings, allowedRematches, chosen) ? new ArrayList<>(chosen) : null;
    }

    /** Pairs the first unpaired player with the closest record it may meet, backtracking when the rest fails. */
    private static boolean search(List<Long> unpaired, Standings standings, int rematchesLeft, Deque<Pairing> chosen) {
        if (unpaired.isEmpty()) {
            return true;
        }
        long first = unpaired.get(0);
        int losses = standings.entry(first).losses();
        List<Long> candidates = new ArrayList<>(unpaired.subList(1, unpaired.size()));
        candidates.sort(Comparator.comparingInt(candidate -> Math.abs(standings.entry(candidate).losses() - losses)));
        for (long opponent : candidates) {
            boolean rematch = standings.haveMet(first, opponent);
            if (rematch && rematchesLeft == 0) {
                continue;
            }
            chosen.addLast(new Pairing(first, opponent));
            List<Long> rest = new ArrayList<>(unpaired.subList(1, unpaired.size()));
            rest.remove(Long.valueOf(opponent));
            if (search(rest, standings, rematchesLeft - (rematch ? 1 : 0), chosen)) {
                return true;
            }
            chosen.removeLast();
        }
        return false;
    }
}
