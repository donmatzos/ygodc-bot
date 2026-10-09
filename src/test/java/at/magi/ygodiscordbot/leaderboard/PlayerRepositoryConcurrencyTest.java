package at.magi.ygodiscordbot.leaderboard;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.testng.SkipException;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

/**
 * Race smoke tests: many threads with their own connections hit the same rows at the same moment. The bot itself
 * runs all writes on one thread, so these guard against a second bot instance or a future multi-threaded executor.
 * Runs only with TEST_DB_URL (plus TEST_DB_USER, TEST_DB_PASSWORD); the {@code players} table there is dropped.
 */
public class PlayerRepositoryConcurrencyTest {

    private static final int THREADS = 8;

    private HikariDataSource dataSource;
    private PlayerRepository repository;
    private ExecutorService pool;

    @BeforeClass
    public void connect() {
        String url = System.getenv("TEST_DB_URL");
        if (url == null || url.isBlank()) {
            throw new SkipException("TEST_DB_URL not set");
        }
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(System.getenv("TEST_DB_USER"));
        config.setPassword(System.getenv("TEST_DB_PASSWORD"));
        // One connection per thread, so the transactions really overlap
        config.setMaximumPoolSize(THREADS);
        dataSource = new HikariDataSource(config);
        pool = Executors.newFixedThreadPool(THREADS);
    }

    @AfterClass(alwaysRun = true)
    public void close() {
        if (pool != null) {
            pool.shutdownNow();
        }
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
        repository.ensureSchema();
    }

    /** Runs {@code task} on all threads at once (released together by a latch) and returns every result. */
    private <T> List<T> race(int times, Callable<T> task) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        for (int i = 0; i < times; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return task.call();
            }));
        }
        start.countDown();
        List<T> results = new ArrayList<>();
        for (Future<T> future : futures) {
            // get() rethrows a failed call (deadlock, duplicate key, ...) and fails the test
            results.add(future.get(30, TimeUnit.SECONDS));
        }
        return results;
    }

    @Test
    public void concurrentAddsLoseNoUpdate() throws Exception {
        repository.create(1);
        race(THREADS * 10, () -> repository.changePoints(1, 1));
        assertEquals(repository.find(1).orElseThrow().points(), THREADS * 10L);
    }

    @Test
    public void concurrentFirstAddsCreateThePlayerOnce() throws Exception {
        List<PointChange> changes = race(THREADS, () -> repository.changePoints(1, 1));
        assertEquals(changes.stream().filter(PointChange::created).count(), 1L);
        assertEquals(repository.find(1).orElseThrow().points(), (long) THREADS);
    }

    @Test
    public void concurrentCreateHasOneWinner() throws Exception {
        List<Boolean> created = race(THREADS, () -> repository.create(1));
        assertEquals(created.stream().filter(Boolean::booleanValue).count(), 1L);
        assertEquals(repository.find(1).orElseThrow().points(), 0L);
    }

    @Test
    public void concurrentAddsNearTheMaximumStopAtIt() throws Exception {
        repository.create(1);
        repository.setPoints(1, Points.MAX - 50);
        race(THREADS, () -> repository.changePoints(1, 99));
        assertEquals(repository.find(1).orElseThrow().points(), Points.MAX);
    }

    @Test
    public void concurrentRemovesStopAtZero() throws Exception {
        repository.create(1);
        repository.setPoints(1, 150);
        race(THREADS, () -> repository.changePoints(1, -99));
        assertEquals(repository.find(1).orElseThrow().points(), 0L);
    }

    /** Every kind of call on a few shared players at once: nothing fails and every total stays in range. */
    @Test
    public void mixedOperationsStayConsistent() throws Exception {
        race(THREADS * 25, () -> {
            ThreadLocalRandom random = ThreadLocalRandom.current();
            long id = random.nextLong(1, 4);
            switch (random.nextInt(7)) {
                case 0 -> repository.create(id);
                case 1 -> repository.changePoints(id, random.nextLong(1, 100));
                case 2 -> repository.changePoints(id, -random.nextLong(1, 100));
                case 3 -> repository.setPoints(id, random.nextLong(0, Points.MAX + 1));
                case 4 -> repository.find(id);
                case 5 -> repository.page(1);
                default -> repository.resetAllPoints();
            }
            return null;
        });
        for (RankedPlayer player : repository.page(1).rows()) {
            assertTrue(player.points() >= 0 && player.points() <= Points.MAX, player.toString());
        }
    }
}
