package at.magi.ygodiscordbot.leaderboard;

import at.magi.ygodiscordbot.config.DatabaseConfig;
import at.magi.ygodiscordbot.deck.DeckDatabase;
import com.zaxxer.hikari.HikariDataSource;
import org.testng.SkipException;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import javax.sql.DataSource;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

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

    /** Tests write rows directly where the repository has no method for it. */
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

    @Test
    public void createAddsPlayerWithZeroPointsOnce() throws SQLException {
        assertTrue(repository.create(7));
        assertFalse(repository.create(7));
        assertEquals(repository.find(7), Optional.of(new RankedPlayer(1, 7, 0)));
    }

    @Test
    public void findReturnsCompetitionRank() throws SQLException {
        insert(1, 50);
        insert(2, 80);
        insert(3, 80);
        assertEquals(repository.find(1), Optional.of(new RankedPlayer(3, 1, 50)));
        assertEquals(repository.find(3), Optional.of(new RankedPlayer(1, 3, 80)));
        assertEquals(repository.find(99), Optional.empty());
    }

    @Test
    public void setPointsOnlyUpdatesExistingPlayers() throws SQLException {
        insert(1, 120);
        assertEquals(repository.setPoints(1, 50), new PointChange(120, 50, false));
        assertNull(repository.setPoints(2, 50));
        assertEquals(repository.find(2), Optional.empty());
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void setPointsRejectsNegative() throws SQLException {
        repository.setPoints(1, -1);
    }

    @Test
    public void changePointsClampsAndCreates() throws SQLException {
        assertEquals(repository.changePoints(1, 3), new PointChange(0, 3, true));
        assertEquals(repository.changePoints(1, -5), new PointChange(3, 0, false));
        assertNull(repository.changePoints(2, -5));
        insert(3, Points.MAX - 1);
        assertEquals(repository.changePoints(3, 99), new PointChange(Points.MAX - 1, Points.MAX, false));
        assertEquals(repository.find(3).orElseThrow().points(), Points.MAX);
    }

    @Test
    public void resetAllPointsKeepsPlayers() throws SQLException {
        insert(1, 10);
        insert(2, 20);
        assertEquals(repository.resetAllPoints(), 2);
        assertEquals(repository.page(1).rows(), List.of(new RankedPlayer(1, 1, 0), new RankedPlayer(1, 2, 0)));
    }

    /**
     * The real database, but every connection's commit() fails, and rollback() too if {@code rollbackFails}.
     * {@code calls} records commit / rollback / setAutoCommit in order.
     */
    private DataSource failingCommits(List<String> calls, boolean rollbackFails) {
        return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[]{DataSource.class},
                (proxy, method, args) -> {
                    Object result = invoke(dataSource, method, args);
                    if (!method.getName().equals("getConnection")) {
                        return result;
                    }
                    Connection real = (Connection) result;
                    return Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                            (p, m, a) -> {
                                switch (m.getName()) {
                                    case "commit" -> {
                                        calls.add("commit");
                                        throw new SQLException("commit failed");
                                    }
                                    case "rollback" -> {
                                        calls.add("rollback");
                                        if (rollbackFails) {
                                            throw new SQLException("rollback failed");
                                        }
                                    }
                                    case "setAutoCommit" -> calls.add("setAutoCommit(" + a[0] + ")");
                                    default -> {
                                    }
                                }
                                return invoke(real, m, a);
                            });
                });
    }

    private static Object invoke(Object target, java.lang.reflect.Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    @Test
    public void failedCommitRollsBackAndKeepsThePoints() throws SQLException {
        insert(1, 10);
        List<String> calls = new ArrayList<>();
        PlayerRepository failing = new PlayerRepository(failingCommits(calls, false));
        SQLException error = expectThrows(SQLException.class, () -> failing.changePoints(1, 5));
        assertEquals(error.getMessage(), "commit failed");
        assertEquals(calls, List.of("setAutoCommit(false)", "commit", "rollback", "setAutoCommit(true)"));
        assertEquals(repository.find(1).orElseThrow().points(), 10L);
    }

    @Test
    public void failedRollbackKeepsTheOriginalError() throws SQLException {
        insert(1, 10);
        PlayerRepository failing = new PlayerRepository(failingCommits(new ArrayList<>(), true));
        SQLException error = expectThrows(SQLException.class, () -> failing.setPoints(1, 50));
        assertEquals(error.getMessage(), "commit failed");
        assertEquals(error.getSuppressed().length, 1);
        assertEquals(error.getSuppressed()[0].getMessage(), "rollback failed");
        assertEquals(repository.find(1).orElseThrow().points(), 10L);
    }
}
