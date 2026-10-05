package at.magi.ygodiscordbot.deck;

import at.magi.ygodiscordbot.config.DatabaseConfig;
import at.magi.ygodiscordbot.deck.DecklistRepository.SaveResult;
import com.zaxxer.hikari.HikariDataSource;
import org.testng.SkipException;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotEquals;
import static org.testng.Assert.expectThrows;
import static org.testng.Assert.assertTrue;

/**
 * Runs against a real MySQL/MariaDB database, only if TEST_DB_URL (plus TEST_DB_USER, TEST_DB_PASSWORD)
 * is set. The {@code decklist} table in that database is dropped before each test.
 */
public class DecklistRepositoryTest {

    private static final long ALICE = 111111111111111111L;
    private static final long BOB = 222222222222222222L;
    private static final String DECK = Ydke.encode(YdkeTest.DECK);
    private static final String OTHER_DECK = "ydke://o6lXBQ==!!!";

    private HikariDataSource dataSource;
    private DecklistRepository repository;

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
            statement.execute("DROP TABLE IF EXISTS decklist");
        }
        repository = new DecklistRepository(dataSource,
                Clock.fixed(Instant.parse("2026-10-05T12:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    public void createsTableOnFirstUse() throws SQLException {
        assertEquals(repository.names(ALICE), List.of());
        repository.ensureSchema();
    }

    @Test
    public void saveAndGet() throws SQLException {
        assertEquals(repository.create(ALICE, "Blue-Eyes", DECK), SaveResult.SAVED);
        Decklist deck = repository.find(ALICE, "Blue-Eyes").orElseThrow();
        assertEquals(deck.ydke(), DECK);
        assertEquals(deck.userId(), ALICE);
        assertEquals(deck.createdAt(), Instant.parse("2026-10-05T12:00:00Z").toEpochMilli());
        assertEquals(deck.updatedAt(), deck.createdAt());
    }

    @Test
    public void namesIgnoreCase() throws SQLException {
        repository.create(ALICE, "Snake-Eye", DECK);
        assertTrue(repository.find(ALICE, "snake-eye").isPresent());
        assertEquals(repository.create(ALICE, "SNAKE-EYE", DECK), SaveResult.NAME_TAKEN);
    }

    @Test
    public void decksArePerUser() throws SQLException {
        repository.create(ALICE, "Same", DECK);
        assertEquals(repository.create(BOB, "Same", OTHER_DECK), SaveResult.SAVED);
        assertEquals(repository.find(BOB, "Same").orElseThrow().ydke(), OTHER_DECK);
        assertTrue(repository.delete(BOB, "Missing").isEmpty());
        assertEquals(repository.delete(BOB, "Same").orElseThrow().ydke(), OTHER_DECK);
        assertTrue(repository.find(ALICE, "Same").isPresent());
    }

    @Test
    public void update() throws SQLException {
        assertTrue(repository.update(ALICE, "Missing", DECK).isEmpty());
        repository.create(ALICE, "Deck", DECK);
        Decklist updated = repository.update(ALICE, "deck", OTHER_DECK).orElseThrow();
        assertEquals(updated.name(), "Deck"); // stored spelling, not the one typed
        assertEquals(updated.ydke(), OTHER_DECK);
        assertEquals(repository.find(ALICE, "Deck").orElseThrow().ydke(), OTHER_DECK);
    }

    @Test
    public void delete() throws SQLException {
        repository.create(ALICE, "Deck", DECK);
        Decklist deleted = repository.delete(ALICE, "DECK").orElseThrow();
        assertEquals(deleted.name(), "Deck");
        assertEquals(deleted.ydke(), DECK);
        assertTrue(repository.find(ALICE, "Deck").isEmpty());
        assertTrue(repository.delete(ALICE, "Deck").isEmpty());
    }

    @Test
    public void listsNamesAlphabetically() throws SQLException {
        repository.create(ALICE, "Tenpai", DECK);
        repository.create(ALICE, "Branded", DECK);
        repository.create(BOB, "Labrynth", DECK);
        assertEquals(repository.names(ALICE), List.of("Branded", "Tenpai"));
    }

    @Test
    public void limitsDecksPerUser() throws SQLException {
        for (int i = 0; i < DecklistRepository.MAX_DECKS_PER_USER; i++) {
            assertEquals(repository.create(ALICE, "Deck " + i, DECK), SaveResult.SAVED);
        }
        assertEquals(repository.create(ALICE, "One more", DECK), SaveResult.LIMIT_REACHED);
        assertEquals(repository.create(BOB, "Deck", DECK), SaveResult.SAVED);
    }

    @Test
    public void updateWithUnchangedDeckStillCountsAsFound() throws SQLException {
        repository.create(ALICE, "Deck", DECK);
        assertTrue(repository.update(ALICE, "Deck", DECK).isPresent());
    }

    /** Only a duplicate name may be reported as NAME_TAKEN; other constraint errors must surface. */
    @Test
    public void databaseChecksRejectInvalidRows() throws SQLException {
        repository.ensureSchema();
        SQLException e = expectThrows(SQLException.class, () -> repository.create(ALICE, "Bad", "https://not-ydke"));
        assertNotEquals(e.getErrorCode(), DecklistRepository.DUPLICATE_KEY);
    }
}
