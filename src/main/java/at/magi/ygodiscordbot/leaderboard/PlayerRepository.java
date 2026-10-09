package at.magi.ygodiscordbot.leaderboard;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.LongUnaryOperator;

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

    /** RANK() over one row: 1 + the number of players with more points. */
    private static final String FIND_QUERY = """
            SELECT p.points, (SELECT COUNT(*) FROM players o WHERE o.points > p.points) + 1 AS player_rank
            FROM players p
            WHERE p.id = ?
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

    /** Adds a player with 0 points. False if the player is already on the leaderboard. */
    public boolean create(long id) throws SQLException {
        ensureSchema();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement insert = connection.prepareStatement("INSERT INTO players (id) VALUES (?)")) {
            insert.setLong(1, id);
            insert.executeUpdate();
            return true;
        } catch (SQLIntegrityConstraintViolationException e) {
            // Duplicate primary key. Not "ON DUPLICATE KEY UPDATE": Connector/J reports found rows by default,
            // so the affected-row count could not tell "inserted" from "already there".
            return false;
        }
    }

    public Optional<RankedPlayer> find(long id) throws SQLException {
        ensureSchema();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement select = connection.prepareStatement(FIND_QUERY)) {
            select.setLong(1, id);
            try (ResultSet result = select.executeQuery()) {
                return result.next()
                        ? Optional.of(new RankedPlayer(result.getLong("player_rank"), id, result.getLong("points")))
                        : Optional.empty();
            }
        }
    }

    /** Sets an existing player's points. Null if the player is not on the leaderboard. */
    public PointChange setPoints(long id, long points) throws SQLException {
        if (points < 0 || points > Points.MAX) {
            throw new IllegalArgumentException("points out of range: " + points);
        }
        return write(id, current -> points, false);
    }

    /**
     * Adds (or with a negative delta removes) points, kept within 0 … {@link Points#MAX}. A player without an entry
     * is created when points are added; removing from a missing player returns null.
     */
    public PointChange changePoints(long id, long delta) throws SQLException {
        return write(id, current -> Points.apply(current, delta), delta > 0);
    }

    /** Sets every player's points to 0 (full leaderboard refresh); the players stay on the board. */
    public int resetAllPoints() throws SQLException {
        ensureSchema();
        try (Connection connection = dataSource.getConnection();
             Statement update = connection.createStatement()) {
            int rows = update.executeUpdate("UPDATE players SET points = 0");
            log.info("Reset the points of {} players", rows);
            return rows;
        }
    }

    /**
     * Reads the current points under a row lock, so concurrent writers can't lose an update. A missing player is
     * inserted first in its own statement: locking a row that doesn't exist takes a gap lock, and two writers
     * holding it would deadlock on their inserts.
     */
    private PointChange write(long id, LongUnaryOperator change, boolean createIfMissing) throws SQLException {
        boolean created = createIfMissing && create(id);
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            boolean ended = false;
            try {
                PointChange result = writeLocked(connection, id, change, created);
                connection.commit();
                ended = true;
                return result;
            } catch (SQLException | RuntimeException e) {
                try {
                    connection.rollback();
                    ended = true;
                } catch (SQLException rollbackError) {
                    // The original error is the one worth reporting
                    e.addSuppressed(rollbackError);
                }
                throw e;
            } finally {
                // setAutoCommit(true) would COMMIT a transaction that failed to roll back; the pool rolls it back
                // (or drops the connection) when it is returned instead
                if (ended) {
                    restoreAutoCommit(connection);
                }
            }
        }
    }

    /** Never throws: after a commit the change is done, and the pool resets auto-commit on return anyway. */
    private static void restoreAutoCommit(Connection connection) {
        try {
            connection.setAutoCommit(true);
        } catch (SQLException e) {
            log.warn("Could not restore auto-commit, the connection pool resets it", e);
        }
    }

    private static PointChange writeLocked(Connection connection, long id, LongUnaryOperator change, boolean created)
            throws SQLException {
        long before;
        try (PreparedStatement select = connection.prepareStatement("SELECT points FROM players WHERE id = ? FOR UPDATE")) {
            select.setLong(1, id);
            try (ResultSet result = select.executeQuery()) {
                if (!result.next()) {
                    return null;
                }
                before = result.getLong(1);
            }
        }
        long after = change.applyAsLong(before);
        try (PreparedStatement update = connection.prepareStatement("UPDATE players SET points = ? WHERE id = ?")) {
            update.setLong(1, after);
            update.setLong(2, id);
            update.executeUpdate();
        }
        return new PointChange(before, after, created);
    }
}
