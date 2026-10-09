package at.magi.ygodiscordbot.impl.database;

import at.magi.ygodiscordbot.impl.config.DatabaseConfig;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Connection pool for the bot database (decklists and leaderboard), sized for a small container and a free
 * MySQL host.
 *
 * <ul>
 *     <li>At most 2 connections: free hosts allow few, and all queries run on one thread anyway.</li>
 *     <li>Connections are retired and kept alive well within typical server-side {@code wait_timeout}s.</li>
 *     <li>Opening the pool needs no database, so the bot starts even while the database is down;
 *     each repository creates its table on first use.</li>
 * </ul>
 */
public final class DatabasePool {

    private static final Logger log = LoggerFactory.getLogger(DatabasePool.class);

    private DatabasePool() {
    }

    public static HikariDataSource open(DatabaseConfig config) {
        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName("bot-db");
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
        log.info("Database configured: {}", config.safeUrl());
        return dataSource;
    }
}
