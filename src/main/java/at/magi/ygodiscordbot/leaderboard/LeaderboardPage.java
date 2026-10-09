package at.magi.ygodiscordbot.leaderboard;

import java.util.List;

/**
 * One page of the leaderboard, highest points first.
 *
 * @param page         1-based page number that was requested
 * @param pageCount    number of pages, 0 for an empty leaderboard
 * @param totalPlayers players on the whole leaderboard
 * @param rows         at most {@value #PAGE_SIZE} rows, empty if the page does not exist
 */
public record LeaderboardPage(int page, int pageCount, long totalPlayers, List<RankedPlayer> rows) {

    public static final int PAGE_SIZE = 20;

    public LeaderboardPage {
        rows = List.copyOf(rows);
    }

    public static int pageCount(long totalPlayers) {
        return (int) ((totalPlayers + PAGE_SIZE - 1) / PAGE_SIZE);
    }

    /** Row offset of a 1-based page, as long so large page numbers cannot overflow. */
    public static long offset(int page) {
        return (page - 1L) * PAGE_SIZE;
    }

    public boolean exists() {
        return page >= 1 && page <= pageCount;
    }
}
