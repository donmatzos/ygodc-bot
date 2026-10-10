package at.magi.ygodiscordbot.impl.tournament;

import at.magi.ygodiscordbot.entity.tournament.MatchRecord;
import at.magi.ygodiscordbot.entity.tournament.NewTournament;
import at.magi.ygodiscordbot.entity.tournament.Pairing;
import at.magi.ygodiscordbot.entity.tournament.TournamentCode;
import at.magi.ygodiscordbot.entity.tournament.TournamentListPage;
import at.magi.ygodiscordbot.entity.tournament.TournamentRecord;
import at.magi.ygodiscordbot.entity.tournament.TournamentStatus;
import at.magi.ygodiscordbot.entity.tournament.TournamentSummary;
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
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
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

    /** The CREATE TABLE of the tournament table before codes existed, verbatim. */
    private static final String OLD_TOURNAMENT_TABLE = """
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
            """;

    private int created;
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
        created = 0;
    }

    private static NewTournament tournament(String code, LocalDate day, long guild) {
        return new NewTournament(code, day, guild, 2, 3, STARTED, PLAYERS);
    }

    private static List<Pairing> round1() {
        return List.of(new Pairing(40, 10L), Pairing.bye(30));
    }

    /** Each call gets its own code, as codes are unique. */
    private long create() throws SQLException {
        String code = "abcdefgh%c-26-10-10".formatted((char) ('a' + created++));
        return repository.create(tournament(code, LocalDate.of(2026, 10, 10), 1), round1());
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
        repository.drop(id, 40, 2, null);

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
    public void dropWithForfeitSavesBoth() throws SQLException {
        long id = create();
        repository.drop(id, 40, 1, new MatchRecord(1, 40, 10L, 10L));
        TournamentRecord record = repository.load(id).orElseThrow();
        assertEquals(record.droppedInRound(), Map.of(40L, 1));
        assertTrue(record.matches().contains(new MatchRecord(1, 40, 10L, 10L)));
    }

    @Test
    public void failedForfeitAlsoUndoesTheDrop() throws SQLException {
        long id = create();
        // No round-2 match exists: the forfeit fails, so the drop must be rolled back too
        expectThrows(SQLException.class, () -> repository.drop(id, 40, 1, new MatchRecord(2, 40, 10L, 10L)));
        assertEquals(repository.load(id).orElseThrow().droppedInRound(), Map.of());
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

    @Test
    public void storesAndLoadsCodeAndDay() throws SQLException {
        long id = repository.create(tournament("abcdefghj-26-10-10", LocalDate.of(2026, 10, 10), 1), round1());
        TournamentRecord record = repository.loadByCode("abcdefghj-26-10-10").orElseThrow();
        assertEquals(record.id(), id);
        assertEquals(record.playedOn(), LocalDate.of(2026, 10, 10));
        assertEquals(repository.loadByCode("zzzzzzzzz-26-10-10"), Optional.empty());
    }

    @Test
    public void duplicateCodeIsRefused() throws SQLException {
        repository.create(tournament("abcdefghj-26-10-10", LocalDate.of(2026, 10, 10), 1), round1());
        expectThrows(SQLIntegrityConstraintViolationException.class, () -> repository.create(
                tournament("abcdefghj-26-10-10", LocalDate.of(2026, 10, 10), 1), round1()));
    }

    @Test
    public void listsMostRecentFirstPerServerAndDay() throws SQLException {
        repository.create(tournament("aaaaaaaaa-26-10-08", LocalDate.of(2026, 10, 8), 1), round1());
        long finished = repository.create(tournament("bbbbbbbbb-26-10-10", LocalDate.of(2026, 10, 10), 1), round1());
        repository.create(tournament("ccccccccc-26-10-10", LocalDate.of(2026, 10, 10), 1), round1());
        repository.create(tournament("ddddddddd-26-10-10", LocalDate.of(2026, 10, 10), 2), round1()); // other server
        repository.finish(finished, 40L, STARTED);

        TournamentListPage all = repository.list(1, null, 1);
        assertEquals(all.total(), 3);
        assertEquals(all.rows().stream().map(TournamentSummary::code).toList(),
                List.of("ccccccccc-26-10-10", "bbbbbbbbb-26-10-10", "aaaaaaaaa-26-10-08"));
        assertEquals(all.rows().get(1), new TournamentSummary("bbbbbbbbb-26-10-10", LocalDate.of(2026, 10, 10),
                TournamentStatus.FINISHED, 40L));

        TournamentListPage day = repository.list(1, LocalDate.of(2026, 10, 8), 1);
        assertEquals(day.rows().stream().map(TournamentSummary::code).toList(), List.of("aaaaaaaaa-26-10-08"));
        assertEquals(repository.list(1, null, 2).rows(), List.of());
    }

    @Test
    public void migratesTableWithoutCode() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS tournament_match, tournament_player, tournament");
            statement.execute(OLD_TOURNAMENT_TABLE);
            // 2026-10-09T23:30Z is already the 10th in Vienna
            statement.execute("INSERT INTO tournament (guild_id, channel_id, created_by, status, started_at, current_round)"
                    + " VALUES (1, 2, 3, 'RUNNING', " + Instant.parse("2026-10-09T23:30:00Z").toEpochMilli() + ", 1)");
        }
        TournamentRepository fresh = new TournamentRepository(dataSource);
        fresh.ensureSchema();
        TournamentRecord record = fresh.loadRunning().get(0);
        assertEquals(record.playedOn(), LocalDate.of(2026, 10, 10));
        assertEquals(TournamentCode.parse(record.code()), Optional.of(record.code()));
        assertTrue(record.code().endsWith("-26-10-10"), record.code());
        new TournamentRepository(dataSource).ensureSchema(); // a second start changes nothing
        assertEquals(fresh.loadByCode(record.code()).orElseThrow().id(), record.id());
    }
}
