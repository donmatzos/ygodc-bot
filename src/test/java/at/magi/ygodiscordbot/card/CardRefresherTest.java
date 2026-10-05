package at.magi.ygodiscordbot.card;

import at.magi.ygodiscordbot.source.ListFetcher;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;

public class CardRefresherTest {

    private static final Instant NOW = Instant.parse("2026-10-05T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final CardNames NEW_NAMES = CardNames.of(Map.of(1, "New Card"));

    private Path dir;
    private CardFileStore store;
    private CardRepository repository;
    private final AtomicInteger downloads = new AtomicInteger();

    @BeforeMethod
    public void setUp() throws IOException {
        dir = Files.createTempDirectory("card-refresher-test");
        store = new CardFileStore(dir.resolve("cards.json"));
        repository = new CardRepository();
        downloads.set(0);
    }

    @AfterMethod(alwaysRun = true)
    public void deleteTempDir() throws IOException {
        try (Stream<Path> paths = Files.walk(dir)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    @Test
    public void unchangedVersionSkipsTheDownload() {
        CardCatalog stored = TestCards.catalog("147.22", NOW.minus(Duration.ofDays(3)));
        repository.replace(stored);
        try (CardRefresher refresher = refresher(() -> "147.22")) {
            assertTrue(refresher.refresh());
        }
        assertEquals(downloads.get(), 0);
        CardCatalog current = repository.catalog().orElseThrow();
        assertSame(current.names(), stored.names());
        assertEquals(current.checkedAt(), NOW);
        assertEquals(store.load().orElseThrow().checkedAt(), NOW);
    }

    @Test
    public void changedVersionDownloadsAndStores() {
        repository.replace(TestCards.catalog("147.22", NOW.minus(Duration.ofDays(3))));
        try (CardRefresher refresher = refresher(() -> "147.23")) {
            assertTrue(refresher.refresh());
        }
        assertEquals(downloads.get(), 1);
        assertSame(repository.names(), NEW_NAMES);
        assertEquals(store.load().orElseThrow().version(), "147.23");
    }

    @Test
    public void failedVersionCheckKeepsCurrentNames() {
        CardCatalog stored = TestCards.catalog("147.22", NOW.minus(Duration.ofDays(3)));
        repository.replace(stored);
        try (CardRefresher refresher = refresher(() -> {
            throw new IOException("YGOProDeck is down");
        })) {
            assertFalse(refresher.refresh());
        }
        assertSame(repository.catalog().orElseThrow(), stored);
        assertTrue(store.load().isEmpty());
    }

    @Test
    public void failedDownloadKeepsCurrentNames() {
        CardCatalog stored = TestCards.catalog("147.22", NOW.minus(Duration.ofDays(3)));
        repository.replace(stored);
        try (CardRefresher refresher = new CardRefresher(repository, store, () -> "147.23", () -> {
            throw new IOException("only 12 cards");
        }, CLOCK)) {
            assertFalse(refresher.refresh());
        }
        assertSame(repository.catalog().orElseThrow(), stored);
    }

    @Test
    public void startDownloadsRightAwayWithoutStoredList() throws Exception {
        CountDownLatch checked = new CountDownLatch(1);
        try (CardRefresher refresher = refresher(() -> {
            checked.countDown();
            return "147.22";
        })) {
            refresher.start();
            assertTrue(checked.await(5, TimeUnit.SECONDS));
        }
        // close() waited for the refresh to finish
        assertEquals(downloads.get(), 1);
        assertEquals(store.load().orElseThrow().version(), "147.22");
    }

    @Test
    public void startChecksWhenStoredListIsDue() throws Exception {
        store.save(TestCards.catalog("147.22", NOW.minus(CardRefresher.CHECK_INTERVAL).minusSeconds(1)));
        CountDownLatch checked = new CountDownLatch(1);
        try (CardRefresher refresher = refresher(() -> {
            checked.countDown();
            return "147.22";
        })) {
            refresher.start();
            assertTrue(checked.await(5, TimeUnit.SECONDS));
        }
        assertEquals(downloads.get(), 0);
    }

    @Test
    public void startUsesStoredListWithoutCallingTheApiWhenNotDue() throws Exception {
        store.save(TestCards.catalog("147.22", NOW.minus(Duration.ofDays(2))));
        CountDownLatch checked = new CountDownLatch(1);
        try (CardRefresher refresher = refresher(() -> {
            checked.countDown();
            return "147.22";
        })) {
            refresher.start();
            assertEquals(repository.names().name(TestCards.BLUE_EYES).orElseThrow(), "Blue-Eyes White Dragon");
            assertFalse(checked.await(500, TimeUnit.MILLISECONDS));
        }
    }

    @Test
    public void closeInterruptsDownloadAndKeepsCurrentNames() throws Exception {
        CountDownLatch downloading = new CountDownLatch(1);
        CardRefresher refresher = new CardRefresher(repository, store, () -> "147.22", () -> {
            downloading.countDown();
            Thread.sleep(60_000); // like a slow download, reacts to interrupts
            return NEW_NAMES;
        }, CLOCK);
        refresher.start();
        assertTrue(downloading.await(5, TimeUnit.SECONDS));

        Instant closing = Instant.now();
        refresher.close();

        assertTrue(Duration.between(closing, Instant.now()).toSeconds() < 5, "close() took too long");
        assertTrue(repository.catalog().isEmpty());
        assertTrue(store.load().isEmpty());
    }

    private CardRefresher refresher(ListFetcher<String> version) {
        return new CardRefresher(repository, store, version, () -> {
            downloads.incrementAndGet();
            return NEW_NAMES;
        }, CLOCK);
    }
}
