package at.magi.ygodiscordbot.impl.tournament;

import at.magi.ygodiscordbot.entity.tournament.MatchRecord;
import at.magi.ygodiscordbot.entity.tournament.NewTournament;
import at.magi.ygodiscordbot.entity.tournament.Pairing;
import at.magi.ygodiscordbot.entity.tournament.TournamentRecord;
import at.magi.ygodiscordbot.entity.tournament.TournamentStatus;
import at.magi.ygodiscordbot.impl.config.DatabaseConfig;
import at.magi.ygodiscordbot.impl.database.DatabasePool;
import com.zaxxer.hikari.HikariDataSource;
import org.testng.SkipException;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

/**
 * Runs against a real MySQL/MariaDB database, only if TEST_DB_URL (plus TEST_DB_USER, TEST_DB_PASSWORD) is set.
 * The tournament tables in that database are dropped before each test.
 */
public class TournamentRepositoryTest {

    private static final Instant STARTED = Instant.parse("2026-10-10T12:00:00.123Z");
    private static final List<Long> PLAYERS = List.of(40L, 10L, 30L);

    private HikariDataSource dataSource;
    private TournamentRepository repository;

    @BeforeClass
    public void connect() {
        String url = System.getenv("TEST_DB_URL");
        if (url == null || url.isBlank()) {
            throw new SkipException("TEST_DB_URL not set");
        }
        dataSource = DatabasePool.open(
                new DatabaseConfig(url, System.getenv("TEST_DB_USER"), System.getenv("TEST_DB_PASSWORD")));
    }

    @AfterClass(alwaysRun = true)
    public void close() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @BeforeMethod
    public void freshTables() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS tournament_match");
            statement.execute("DROP TABLE IF EXISTS tournament_player");
            statement.execute("DROP TABLE IF EXISTS tournament");
        }
        repository = new TournamentRepository(dataSource);
    }

    private long create() throws SQLException {
        return repository.create(new NewTournament(1, 2, 3, STARTED, PLAYERS),
                List.of(new Pairing(40, 10L), Pairing.bye(30)));
    }

    @Test
    public void createdTournamentLoadsBackCompletely() throws SQLException {
        long id = create();
        TournamentRecord record = repository.load(id).orElseThrow();
        assertEquals(record.id(), id);
        assertEquals(record.guildId(), 1);
        assertEquals(record.channelId(), 2);
        assertEquals(record.createdBy(), 3);
        assertEquals(record.status(), TournamentStatus.RUNNING);
        assertNull(record.winner());
        assertEquals(record.startedAt(), STARTED);
        assertNull(record.finishedAt());
        assertEquals(record.currentRound(), 1);
        assertEquals(record.players(), PLAYERS); // entry order, not ID order
        assertEquals(record.droppedInRound(), Map.of());
        assertEquals(record.matches(), List.of(MatchRecord.of(1, Pairing.bye(30)),
                new MatchRecord(1, 40, 10L, null)));
    }

    @Test
    public void unknownTournamentIsEmpty() throws SQLException {
        assertEquals(repository.load(999), Optional.empty());
    }

    @Test
    public void resultsPairingsRoundsAndDrops() throws SQLException {
        long id = create();
        repository.recordWinner(id, 1, 40, 10);
        repository.savePairings(id, 2, List.of(new Pairing(10, 30L), Pairing.bye(40)));
        repository.deletePairings(id, 2);
        repository.savePairings(id, 2, List.of(new Pairing(30, 10L), Pairing.bye(40)));
        repository.startRound(id, 2);
        repository.drop(id, 40, 2);

        TournamentRecord record = repository.load(id).orElseThrow();
        assertEquals(record.currentRound(), 2);
        assertEquals(record.droppedInRound(), Map.of(40L, 2));
        assertTrue(record.matches().contains(new MatchRecord(1, 40, 10L, 10L)));
        assertTrue(record.matches().contains(new MatchRecord(2, 30, 10L, null)));
        assertTrue(record.matches().contains(MatchRecord.of(2, Pairing.bye(40))));
        assertEquals(record.matches().size(), 4);
    }

    @Test
    public void doubleLossAndBackToAWinner() throws SQLException {
        long id = create();
        repository.recordDoubleLoss(id, 1, 40);
        assertTrue(repository.load(id).orElseThrow().matches().contains(new MatchRecord(1, 40, 10L, null, true)));
        repository.recordWinner(id, 1, 40, 40);
        assertTrue(repository.load(id).orElseThrow().matches().contains(new MatchRecord(1, 40, 10L, 40L)));
        // A bye can't be a double loss
        expectThrows(SQLException.class, () -> repository.recordDoubleLoss(id, 1, 30));
    }

    @Test
    public void recordingAnUnknownMatchFails() throws SQLException {
        long id = create();
        expectThrows(SQLException.class, () -> repository.recordWinner(id, 1, 10, 10));
    }

    @Test
    public void finishedAndAbandonedAreNoLongerRunning() throws SQLException {
        long finished = create();
        long abandoned = create();
        long running = create();
        Instant end = STARTED.plusSeconds(3600);
        repository.finish(finished, 40, end);
        repository.abandon(abandoned, end);

        assertEquals(repository.loadRunning().stream().map(TournamentRecord::id).toList(), List.of(running));
        TournamentRecord record = repository.load(finished).orElseThrow();
        assertEquals(record.status(), TournamentStatus.FINISHED);
        assertEquals(record.winner(), Long.valueOf(40));
        assertEquals(record.finishedAt(), end);
        assertEquals(repository.load(abandoned).orElseThrow().status(), TournamentStatus.ABANDONED);
        // A tournament ends only once
        expectThrows(SQLException.class, () -> repository.abandon(finished, end));
    }
}
