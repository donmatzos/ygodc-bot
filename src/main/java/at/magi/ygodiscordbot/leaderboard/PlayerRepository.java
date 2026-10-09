package at.magi.ygodiscordbot.leaderboard;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Tournament points per Discord user, in plain JDBC. Calls block on the database, so callers run them off the
 * JDA event thread. Ranks are computed per query, so they always match the current points.
 */
public class PlayerRepository {

    private static final Logger log = LoggerFactory.getLogger(PlayerRepository.class);

    /** {@code id} is the Discord user ID. The index serves the ORDER BY of every page query. */
    static final String SCHEMA = """
            CREATE TABLE IF NOT EXISTS players (
                id      BIGINT NOT NULL PRIMARY KEY,
                points  BIGINT NOT NULL DEFAULT 0,
                INDEX idx_players_points (points)
            ) ENGINE = InnoDB
            """;

    /** RANK() is evaluated before LIMIT/OFFSET, so every page gets ranks over the whole table. */
    private static final String PAGE_QUERY = """
            SELECT id, points, RANK() OVER (ORDER BY points DESC) AS player_rank
            FROM players
            ORDER BY points DESC, id
            LIMIT ? OFFSET ?
            """;

    private final DataSource dataSource;
    private volatile boolean schemaReady;

    public PlayerRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** Creates the table if it does not exist yet. Retried on the next call if the database is down. */
    public synchronized void ensureSchema() throws SQLException {
        if (schemaReady) {
            return;
        }
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(SCHEMA);
        }
        schemaReady = true;
        log.info("Players table is ready");
    }

    /** One 1-based page, highest points first. A page past the end has no rows. */
    public LeaderboardPage page(int page) throws SQLException {
        if (page < 1) {
            throw new IllegalArgumentException("page must be at least 1: " + page);
        }
        ensureSchema();
        try (Connection connection = dataSource.getConnection()) {
            long total;
            try (Statement count = connection.createStatement();
                 ResultSet result = count.executeQuery("SELECT COUNT(*) FROM players")) {
                result.next();
                total = result.getLong(1);
            }
            int pageCount = LeaderboardPage.pageCount(total);
            if (page > pageCount) {
                return new LeaderboardPage(page, pageCount, total, List.of());
            }
            try (PreparedStatement select = connection.prepareStatement(PAGE_QUERY)) {
                select.setInt(1, LeaderboardPage.PAGE_SIZE);
                select.setLong(2, LeaderboardPage.offset(page));
                try (ResultSet result = select.executeQuery()) {
                    List<RankedPlayer> rows = new ArrayList<>(LeaderboardPage.PAGE_SIZE);
                    while (result.next()) {
                        rows.add(new RankedPlayer(result.getLong("player_rank"), result.getLong("id"),
                                result.getLong("points")));
                    }
                    return new LeaderboardPage(page, pageCount, total, rows);
                }
            }
        }
    }
}
