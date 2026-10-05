package at.magi.ygodiscordbot.deck;

import at.magi.ygodiscordbot.config.DatabaseConfig;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Connection pool for the decklist database, sized for a small container and a free MySQL host.
 *
 * <ul>
 *     <li>At most 2 connections: free hosts allow few, and all queries run on one thread anyway.</li>
 *     <li>Connections are retired and kept alive well within typical server-side {@code wait_timeout}s.</li>
 *     <li>Opening the pool needs no database, so the bot starts even while the database is down;
 *     the table is created on first use (see {@link DecklistRepository}).</li>
 * </ul>
 */
public final class DeckDatabase {

    private static final Logger log = LoggerFactory.getLogger(DeckDatabase.class);

    /**
     * MySQL 8+ / MariaDB 10.2+ version of the schema. The collation makes names case-insensitive
     * ("Snake-Eye" = "snake-eye"); the YDKE string is plain ASCII.
     */
    static final String SCHEMA = """
            CREATE TABLE IF NOT EXISTS decklist (
                id          BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
                user_id     BIGINT       NOT NULL,
                name        VARCHAR(50)  CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
                ydke        VARCHAR(4000) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
                created_at  BIGINT       NOT NULL,
                updated_at  BIGINT       NOT NULL,
                CONSTRAINT uq_decklist_user_name UNIQUE (user_id, name),
                CONSTRAINT chk_decklist_name CHECK (CHAR_LENGTH(name) BETWEEN 1 AND 50),
                CONSTRAINT chk_decklist_ydke CHECK (ydke LIKE 'ydke://%')
            ) ENGINE = InnoDB
            """;

    private DeckDatabase() {
    }

    public static HikariDataSource open(DatabaseConfig config) {
        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName("decklist-db");
        hikari.setJdbcUrl(config.url());
        if (config.user() != null) {
            hikari.setUsername(config.user());
        }
        if (config.password() != null) {
            hikari.setPassword(config.password());
        }
        hikari.setMaximumPoolSize(2);
        hikari.setMinimumIdle(1);
        hikari.setMaxLifetime(900_000);
        hikari.setIdleTimeout(300_000);
        hikari.setKeepaliveTime(120_000);
        hikari.setConnectionTimeout(10_000);
        // Do not fail at startup if the database is unreachable
        hikari.setInitializationFailTimeout(-1);
        hikari.addDataSourceProperty("connectTimeout", "10000");
        hikari.addDataSourceProperty("socketTimeout", "30000");
        HikariDataSource dataSource = new HikariDataSource(hikari);
        log.info("Decklist database configured: {}", config.safeUrl());
        return dataSource;
    }
}
