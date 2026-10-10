package at.magi.ygodiscordbot.entity.tournament;

import java.util.LinkedHashMap;
import java.util.Map;

/** Leaderboard points for a finished tournament: 1 per real win, plus the number of rounds for the winner. */
public final class TournamentPoints {

    private TournamentPoints() {
    }

    /** Players who earned points, best first; players with 0 points are left out. */
    public static Map<Long, Long> award(Standings standings, int roundsPlayed, long winner) {
        Map<Long, Long> points = new LinkedHashMap<>();
        for (Standings.Entry entry : standings.ranked()) {
            long earned = entry.realWins() + (entry.player() == winner ? roundsPlayed : 0);
            if (earned > 0) {
                points.put(entry.player(), earned);
            }
        }
        return points;
    }
}
