package at.magi.ygodiscordbot.entity.tournament;

import java.util.List;

/** One page of a server's tournaments, most recent first. {@code page} may be past {@code pageCount} (then no rows). */
public record TournamentListPage(int page, int pageCount, int total, List<TournamentSummary> rows) {

    public static final int PAGE_SIZE = 20;

    public static int pageCount(int total) {
        return Math.max(1, (total + PAGE_SIZE - 1) / PAGE_SIZE);
    }
}
