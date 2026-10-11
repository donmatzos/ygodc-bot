package at.magi.ygodiscordbot.impl.tournament;

import at.magi.ygodiscordbot.entity.tournament.TournamentCode;
import at.magi.ygodiscordbot.impl.database.Jdbc;
import at.magi.ygodiscordbot.impl.database.LazySchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** The tournament tables and the in-place migration of tables from before tournament codes. */
final class TournamentSchema {

    private static final Logger log = LoggerFactory.getLogger(TournamentSchema.class);

    private static final List<String> TABLES = List.of("""
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
                code          VARCHAR(18) NOT NULL,
                played_on     DATE        NOT NULL,
                UNIQUE INDEX uq_tournament_code (code),
                INDEX idx_tournament_guild_day (guild_id, played_on),
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

    private TournamentSchema() {
    }

    static LazySchema lazy(DataSource dataSource) {
        return new LazySchema(dataSource, "Tournament tables", TournamentSchema::create);
    }

    private static void create(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            for (String table : TABLES) {
                statement.execute(table);
            }
        }
        migrate(connection);
    }

    /** Tables created before tournament codes existed: adds code + played_on and fills them for existing rows. */
    private static void migrate(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            if (!hasColumn(connection, "code")) {
                statement.execute("ALTER TABLE tournament ADD COLUMN code VARCHAR(18) NULL, ADD COLUMN played_on DATE NULL");
                log.info("Added code and played_on to the tournament table");
            }
            backfillCodes(connection);
            if (!hasIndex(connection, "uq_tournament_code")) {
                statement.execute("ALTER TABLE tournament ADD UNIQUE INDEX uq_tournament_code (code),"
                        + " ADD INDEX idx_tournament_guild_day (guild_id, played_on)");
            }
        }
    }

    private static boolean hasColumn(Connection connection, String column) throws SQLException {
        return exists(connection, "SELECT COUNT(*) FROM information_schema.COLUMNS"
                + " WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tournament' AND COLUMN_NAME = ?", column);
    }

    private static boolean hasIndex(Connection connection, String index) throws SQLException {
        return exists(connection, "SELECT COUNT(*) FROM information_schema.STATISTICS"
                + " WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tournament' AND INDEX_NAME = ?", index);
    }

    private static boolean exists(Connection connection, String sql, String name) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement(sql)) {
            select.setString(1, name);
            try (ResultSet result = select.executeQuery()) {
                result.next();
                return result.getInt(1) > 0;
            }
        }
    }

    private static void backfillCodes(Connection connection) throws SQLException {
        Map<Long, Long> startedAt = new LinkedHashMap<>();
        try (Statement select = connection.createStatement();
             ResultSet result = select.executeQuery("SELECT id, started_at FROM tournament WHERE code IS NULL")) {
            while (result.next()) {
                startedAt.put(result.getLong(1), result.getLong(2));
            }
        }
        Random random = new SecureRandom();
        for (Map.Entry<Long, Long> row : startedAt.entrySet()) {
            LocalDate day = LocalDate.ofInstant(Instant.ofEpochMilli(row.getValue()), TournamentCode.ZONE);
            Jdbc.update(connection, "UPDATE tournament SET code = ?, played_on = ? WHERE id = ?",
                    TournamentCode.generate(random, day), day, row.getKey());
        }
        if (!startedAt.isEmpty()) {
            log.info("Gave {} existing tournament(s) a code", startedAt.size());
        }
    }
}
