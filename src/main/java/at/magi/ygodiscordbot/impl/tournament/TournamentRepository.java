package at.magi.ygodiscordbot.impl.tournament;

import at.magi.ygodiscordbot.entity.tournament.MatchRecord;
import at.magi.ygodiscordbot.entity.tournament.NewTournament;
import at.magi.ygodiscordbot.entity.tournament.Pairing;
import at.magi.ygodiscordbot.entity.tournament.TournamentRecord;
import at.magi.ygodiscordbot.entity.tournament.TournamentStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Tournaments in plain JDBC. Times are epoch milliseconds like in {@code decklist}, so no time zone is involved.
 * Matches only exist here as pairings with results; their 5-digit IDs live in memory.
 */
public class TournamentRepository implements TournamentStore {

    private static final Logger log = LoggerFactory.getLogger(TournamentRepository.class);

    static final List<String> SCHEMA = List.of("""
            CREATE TABLE IF NOT EXISTS tournament (
                id            BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
                guild_id      BIGINT      NOT NULL,
                channel_id    BIGINT      NOT NULL,
                created_by    BIGINT      NOT NULL,
                status        VARCHAR(10) NOT NULL,
                winner_id     BIGINT      NULL,
                started_at    BIGINT      NOT NULL,
                finished_at   BIGINT      NULL,
                current_round INT         NOT NULL DEFAULT 0,
                INDEX idx_tournament_status (status)
            ) ENGINE = InnoDB
            """, """
            CREATE TABLE IF NOT EXISTS tournament_player (
                tournament_id    BIGINT NOT NULL,
                player_id        BIGINT NOT NULL,
                position         INT    NOT NULL,
                dropped_in_round INT    NULL,
                PRIMARY KEY (tournament_id, player_id),
                CONSTRAINT fk_tournament_player FOREIGN KEY (tournament_id) REFERENCES tournament (id) ON DELETE CASCADE
            ) ENGINE = InnoDB
            """, """
            CREATE TABLE IF NOT EXISTS tournament_match (
                tournament_id BIGINT NOT NULL,
                round         INT    NOT NULL,
                player1_id    BIGINT NOT NULL,
                player2_id    BIGINT NULL,
                winner_id     BIGINT NULL,
                double_loss   BOOLEAN NOT NULL DEFAULT FALSE,
                PRIMARY KEY (tournament_id, round, player1_id),
                CONSTRAINT fk_tournament_match FOREIGN KEY (tournament_id) REFERENCES tournament (id) ON DELETE CASCADE
            ) ENGINE = InnoDB
            """);

    private static final String INSERT_MATCH =
            "INSERT INTO tournament_match (tournament_id, round, player1_id, player2_id, winner_id) VALUES (?, ?, ?, ?, ?)";

    @FunctionalInterface
    private interface TransactionWork<T> {
        T run(Connection connection) throws SQLException;
    }

    private record Header(long guildId, long channelId, long createdBy, TournamentStatus status, Long winner,
                          Instant startedAt, Instant finishedAt, int currentRound) {
    }

    private final DataSource dataSource;
    private volatile boolean schemaReady;

    public TournamentRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** Creates the tables if they do not exist yet. Retried on the next call if the database is down. */
    public synchronized void ensureSchema() throws SQLException {
        if (schemaReady) {
            return;
        }
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            for (String table : SCHEMA) {
                statement.execute(table);
            }
        }
        schemaReady = true;
        log.info("Tournament tables are ready");
    }

    @Override
    public long create(NewTournament tournament, List<Pairing> round1) throws SQLException {
        return inTransaction(connection -> {
            long id;
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO tournament (guild_id, channel_id, created_by, status, started_at, current_round)
                    VALUES (?, ?, ?, ?, ?, 1)
                    """, Statement.RETURN_GENERATED_KEYS)) {
                insert.setLong(1, tournament.guildId());
                insert.setLong(2, tournament.channelId());
                insert.setLong(3, tournament.createdBy());
                insert.setString(4, TournamentStatus.RUNNING.name());
                insert.setLong(5, tournament.startedAt().toEpochMilli());
                insert.executeUpdate();
                try (ResultSet keys = insert.getGeneratedKeys()) {
                    keys.next();
                    id = keys.getLong(1);
                }
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO tournament_player (tournament_id, player_id, position) VALUES (?, ?, ?)")) {
                List<Long> players = tournament.players();
                for (int i = 0; i < players.size(); i++) {
                    insert.setLong(1, id);
                    insert.setLong(2, players.get(i));
                    insert.setInt(3, i);
                    insert.addBatch();
                }
                insert.executeBatch();
            }
            insertPairings(connection, id, 1, round1);
            log.info("Created tournament {} with {} players", id, tournament.players().size());
            return id;
        });
    }

    @Override
    public void savePairings(long tournamentId, int round, List<Pairing> pairings) throws SQLException {
        inTransaction(connection -> {
            insertPairings(connection, tournamentId, round, pairings);
            return null;
        });
    }

    private static void insertPairings(Connection connection, long tournamentId, int round, List<Pairing> pairings)
            throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(INSERT_MATCH)) {
            for (Pairing pairing : pairings) {
                MatchRecord match = MatchRecord.of(round, pairing);
                insert.setLong(1, tournamentId);
                insert.setInt(2, round);
                insert.setLong(3, match.player1());
                setNullableLong(insert, 4, match.player2());
                setNullableLong(insert, 5, match.winner());
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    @Override
    public void deletePairings(long tournamentId, int round) throws SQLException {
        update("DELETE FROM tournament_match WHERE tournament_id = ? AND round = ?", tournamentId, round);
    }

    @Override
    public void startRound(long tournamentId, int round) throws SQLException {
        expectOneRow(update("UPDATE tournament SET current_round = ? WHERE id = ? AND status = 'RUNNING'",
                round, tournamentId), "Tournament " + tournamentId + " is not running");
    }

    @Override
    public void recordWinner(long tournamentId, int round, long player1, long winner) throws SQLException {
        // Connector/J reports found (not changed) rows, so writing the same winner again still counts 1
        expectOneRow(update("""
                UPDATE tournament_match SET winner_id = ?, double_loss = FALSE
                WHERE tournament_id = ? AND round = ? AND player1_id = ?
                """, winner, tournamentId, round, player1),
                "No match of player " + player1 + " in round " + round + " of tournament " + tournamentId);
    }

    @Override
    public void recordDoubleLoss(long tournamentId, int round, long player1) throws SQLException {
        expectOneRow(update("""
                UPDATE tournament_match SET winner_id = NULL, double_loss = TRUE
                WHERE tournament_id = ? AND round = ? AND player1_id = ? AND player2_id IS NOT NULL
                """, tournamentId, round, player1),
                "No match (bye excluded) of player " + player1 + " in round " + round + " of tournament " + tournamentId);
    }

    @Override
    public void drop(long tournamentId, long player, int round, MatchRecord forfeit) throws SQLException {
        inTransaction(connection -> {
            try (PreparedStatement drop = connection.prepareStatement(
                    "UPDATE tournament_player SET dropped_in_round = ? WHERE tournament_id = ? AND player_id = ?")) {
                drop.setInt(1, round);
                drop.setLong(2, tournamentId);
                drop.setLong(3, player);
                expectOneRow(drop.executeUpdate(), "Player " + player + " is not in tournament " + tournamentId);
            }
            if (forfeit != null) {
                try (PreparedStatement win = connection.prepareStatement(
                        "UPDATE tournament_match SET winner_id = ?, double_loss = FALSE"
                                + " WHERE tournament_id = ? AND round = ? AND player1_id = ?")) {
                    win.setLong(1, forfeit.winner());
                    win.setLong(2, tournamentId);
                    win.setInt(3, forfeit.round());
                    win.setLong(4, forfeit.player1());
                    expectOneRow(win.executeUpdate(), "No match of player " + forfeit.player1() + " in round "
                            + forfeit.round() + " of tournament " + tournamentId);
                }
            }
            return null;
        });
    }

    @Override
    public void finish(long tournamentId, long winner, Instant at) throws SQLException {
        end(tournamentId, TournamentStatus.FINISHED, winner, at);
    }

    @Override
    public void abandon(long tournamentId, Instant at) throws SQLException {
        end(tournamentId, TournamentStatus.ABANDONED, null, at);
    }

    private void end(long tournamentId, TournamentStatus status, Long winner, Instant at) throws SQLException {
        expectOneRow(update("""
                UPDATE tournament SET status = ?, winner_id = ?, finished_at = ?
                WHERE id = ? AND status = 'RUNNING'
                """, status.name(), winner, at.toEpochMilli(), tournamentId),
                "Tournament " + tournamentId + " is not running");
        log.info("Tournament {} is now {}", tournamentId, status);
    }

    @Override
    public Optional<TournamentRecord> load(long tournamentId) throws SQLException {
        ensureSchema();
        try (Connection connection = dataSource.getConnection()) {
            return load(connection, tournamentId);
        }
    }

    @Override
    public List<TournamentRecord> loadRunning() throws SQLException {
        ensureSchema();
        try (Connection connection = dataSource.getConnection()) {
            List<Long> ids = new ArrayList<>();
            try (Statement select = connection.createStatement();
                 ResultSet result = select.executeQuery("SELECT id FROM tournament WHERE status = 'RUNNING' ORDER BY id")) {
                while (result.next()) {
                    ids.add(result.getLong(1));
                }
            }
            List<TournamentRecord> running = new ArrayList<>();
            for (long id : ids) {
                load(connection, id).ifPresent(running::add);
            }
            return running;
        }
    }

    private static Optional<TournamentRecord> load(Connection connection, long id) throws SQLException {
        Header header;
        try (PreparedStatement select = connection.prepareStatement("""
                SELECT guild_id, channel_id, created_by, status, winner_id, started_at, finished_at, current_round
                FROM tournament WHERE id = ?
                """)) {
            select.setLong(1, id);
            try (ResultSet result = select.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                Long finishedAt = nullableLong(result, 7);
                header = new Header(result.getLong(1), result.getLong(2), result.getLong(3),
                        TournamentStatus.valueOf(result.getString(4)), nullableLong(result, 5),
                        Instant.ofEpochMilli(result.getLong(6)),
                        finishedAt == null ? null : Instant.ofEpochMilli(finishedAt), result.getInt(8));
            }
        }
        List<Long> players = new ArrayList<>();
        Map<Long, Integer> dropped = new HashMap<>();
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT player_id, dropped_in_round FROM tournament_player WHERE tournament_id = ? ORDER BY position")) {
            select.setLong(1, id);
            try (ResultSet result = select.executeQuery()) {
                while (result.next()) {
                    long player = result.getLong(1);
                    players.add(player);
                    int round = result.getInt(2);
                    if (!result.wasNull()) {
                        dropped.put(player, round);
                    }
                }
            }
        }
        List<MatchRecord> matches = new ArrayList<>();
        try (PreparedStatement select = connection.prepareStatement("""
                SELECT round, player1_id, player2_id, winner_id, double_loss FROM tournament_match
                WHERE tournament_id = ? ORDER BY round, player1_id
                """)) {
            select.setLong(1, id);
            try (ResultSet result = select.executeQuery()) {
                while (result.next()) {
                    matches.add(new MatchRecord(result.getInt(1), result.getLong(2), nullableLong(result, 3),
                            nullableLong(result, 4), result.getBoolean(5)));
                }
            }
        }
        return Optional.of(new TournamentRecord(id, header.guildId(), header.channelId(), header.createdBy(),
                header.status(), header.winner(), header.startedAt(), header.finishedAt(), header.currentRound(),
                List.copyOf(players), Map.copyOf(dropped), List.copyOf(matches)));
    }

    private int update(String sql, Object... parameters) throws SQLException {
        ensureSchema();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                if (parameters[i] == null) {
                    statement.setNull(i + 1, Types.BIGINT);
                } else {
                    statement.setObject(i + 1, parameters[i]);
                }
            }
            return statement.executeUpdate();
        }
    }

    private static void expectOneRow(int rows, String problem) throws SQLException {
        if (rows != 1) {
            throw new SQLException(problem);
        }
    }

    private static void setNullableLong(PreparedStatement statement, int index, Long value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.BIGINT);
        } else {
            statement.setLong(index, value);
        }
    }

    private static Long nullableLong(ResultSet result, int column) throws SQLException {
        long value = result.getLong(column);
        return result.wasNull() ? null : value;
    }

    /** Same commit/rollback handling as {@code PlayerRepository.write}. */
    private <T> T inTransaction(TransactionWork<T> work) throws SQLException {
        ensureSchema();
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            boolean ended = false;
            try {
                T result = work.run(connection);
                connection.commit();
                ended = true;
                return result;
            } catch (SQLException | RuntimeException e) {
                try {
                    connection.rollback();
                    ended = true;
                } catch (SQLException rollbackError) {
                    e.addSuppressed(rollbackError);
                }
                throw e;
            } finally {
                // setAutoCommit(true) would COMMIT a transaction that failed to roll back
                if (ended) {
                    restoreAutoCommit(connection);
                }
            }
        }
    }

    private static void restoreAutoCommit(Connection connection) {
        try {
            connection.setAutoCommit(true);
        } catch (SQLException e) {
            log.warn("Could not restore auto-commit, the connection pool resets it", e);
        }
    }
}
