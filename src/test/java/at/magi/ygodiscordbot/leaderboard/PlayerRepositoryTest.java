package at.magi.ygodiscordbot.leaderboard;

import at.magi.ygodiscordbot.config.DatabaseConfig;
import at.magi.ygodiscordbot.deck.DeckDatabase;
import com.zaxxer.hikari.HikariDataSource;
import org.testng.SkipException;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

/**
 * Runs against a real MySQL/MariaDB database, only if TEST_DB_URL (plus TEST_DB_USER, TEST_DB_PASSWORD)
 * is set. The {@code players} table in that database is dropped before each test.
 */
public class PlayerRepositoryTest {

    private HikariDataSource dataSource;
    private PlayerRepository repository;

    @BeforeClass
    public void connect() {
        String url = System.getenv("TEST_DB_URL");
        if (url == null || url.isBlank()) {
            throw new SkipException("TEST_DB_URL not set");
        }
        dataSource = DeckDatabase.open(
                new DatabaseConfig(url, System.getenv("TEST_DB_USER"), System.getenv("TEST_DB_PASSWORD")));
    }

    @AfterClass(alwaysRun = true)
    public void close() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @BeforeMethod
    public void freshTable() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS players");
        }
        repository = new PlayerRepository(dataSource);
    }

    /** Point input comes in a later plan, so tests write rows directly. */
    private void insert(long id, long points) throws SQLException {
        repository.ensureSchema();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement insert = connection.prepareStatement("INSERT INTO players (id, points) VALUES (?, ?)")) {
            insert.setLong(1, id);
            insert.setLong(2, points);
            insert.executeUpdate();
        }
    }

    @Test
    public void emptyBoardCreatesTable() throws SQLException {
        LeaderboardPage page = repository.page(1);
        assertEquals(page.totalPlayers(), 0);
        assertEquals(page.pageCount(), 0);
        assertEquals(page.rows(), List.of());
    }

    @Test
    public void pointsDefaultToZero() throws SQLException {
        repository.ensureSchema();
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO players (id) VALUES (7)");
        }
        assertEquals(repository.page(1).rows(), List.of(new RankedPlayer(1, 7, 0)));
    }

    @Test
    public void sortedByPointsWithCompetitionRanks() throws SQLException {
        insert(1, 5);
        insert(2, 12);
        insert(3, 9);
        insert(4, 9);
        assertEquals(repository.page(1).rows(), List.of(
                new RankedPlayer(1, 2, 12),
                new RankedPlayer(2, 3, 9),
                new RankedPlayer(2, 4, 9),
                new RankedPlayer(4, 1, 5)));
    }

    @Test
    public void secondPageHasNextTwentyAndGlobalRanks() throws SQLException {
        for (long id = 1; id <= 45; id++) {
            insert(id, 100 - id); // id 1 has the most points
        }
        LeaderboardPage page = repository.page(2);
        assertEquals(page.totalPlayers(), 45);
        assertEquals(page.pageCount(), 3);
        assertEquals(page.rows().size(), 20);
        assertEquals(page.rows().get(0), new RankedPlayer(21, 21, 79));
        assertEquals(page.rows().get(19), new RankedPlayer(40, 40, 60));
        assertEquals(repository.page(3).rows().size(), 5);
    }

    @Test
    public void tiesShareRankAcrossPages() throws SQLException {
        for (long id = 1; id <= 19; id++) {
            insert(id, 100 - id);
        }
        insert(20, 50);
        insert(21, 50);
        assertEquals(repository.page(1).rows().get(19), new RankedPlayer(20, 20, 50));
        assertEquals(repository.page(2).rows(), List.of(new RankedPlayer(20, 21, 50)));
    }

    @Test
    public void pagePastEndHasNoRows() throws SQLException {
        insert(1, 3);
        LeaderboardPage page = repository.page(5);
        assertEquals(page.page(), 5);
        assertEquals(page.pageCount(), 1);
        assertTrue(page.rows().isEmpty());
    }
}
