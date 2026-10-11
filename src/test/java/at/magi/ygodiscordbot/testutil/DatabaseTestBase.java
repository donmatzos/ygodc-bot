package at.magi.ygodiscordbot.testutil;

import at.magi.ygodiscordbot.impl.config.DatabaseConfig;
import at.magi.ygodiscordbot.impl.database.DatabasePool;
import com.zaxxer.hikari.HikariDataSource;
import org.testng.SkipException;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Base for tests that run against a real MySQL/MariaDB database. The class is skipped unless TEST_DB_URL
 * (plus TEST_DB_USER, TEST_DB_PASSWORD) is set.
 */
public abstract class DatabaseTestBase {

    protected HikariDataSource dataSource;

    @BeforeClass
    public void connect() {
        String url = System.getenv("TEST_DB_URL");
        if (url == null || url.isBlank()) {
            throw new SkipException("TEST_DB_URL not set");
        }
        dataSource = openPool(
                new DatabaseConfig(url, System.getenv("TEST_DB_USER"), System.getenv("TEST_DB_PASSWORD")));
    }

    @AfterClass(alwaysRun = true)
    public void closePool() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    /** The production pool; override for a different size. */
    protected HikariDataSource openPool(DatabaseConfig config) {
        return DatabasePool.open(config);
    }

    /** Drops the given tables (in the given order, so list child tables first). */
    protected void dropTables(String... tables) throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            for (String table : tables) {
                statement.execute("DROP TABLE IF EXISTS " + table);
            }
        }
    }
}
