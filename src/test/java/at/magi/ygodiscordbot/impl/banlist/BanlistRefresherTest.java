package at.magi.ygodiscordbot.impl.banlist;

import at.magi.ygodiscordbot.entity.banlist.BanlistSnapshot;
import at.magi.ygodiscordbot.entity.banlist.GenesysPointlist;
import at.magi.ygodiscordbot.entity.banlist.OcgBanlist;
import at.magi.ygodiscordbot.entity.banlist.TcgBanlist;
import at.magi.ygodiscordbot.utils.http.ListFetcher;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.Stream;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

public class BanlistRefresherTest {

    /** 10:00 in Vienna, so the last daily run was today at 03:00 (01:00 UTC). */
    private static final Instant NOW = Instant.parse("2026-10-05T08:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, BanlistRefresher.ZONE);

    private Path dir;
    private SnapshotFileStore store;
    private BanlistRepository repository;

    @BeforeMethod
    public void setUp() throws IOException {
        dir = Files.createTempDirectory("refresher-test");
        store = new SnapshotFileStore(dir.resolve("banlists.json"));
        repository = TestLists.repository();
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
    public void refreshSwapsInAndStoresAllLists() {
        try (BanlistRefresher refresher = refresher(
                () -> TestLists.tcg(NOW), () -> TestLists.ocg(NOW), () -> TestLists.genesys(NOW))) {

            assertTrue(refresher.refresh());
        }
        assertEquals(repository.snapshot(), TestLists.snapshot(NOW));
        assertEquals(store.load().orElseThrow(), TestLists.snapshot(NOW));
    }

    @Test
    public void failedSourceKeepsPreviousList() {
        Instant yesterday = NOW.minusSeconds(86_400);
        repository.replace(TestLists.snapshot(yesterday));

        try (BanlistRefresher refresher = refresher(
                () -> {
                    throw new IOException("YGOProDeck is down");
                },
                () -> TestLists.ocg(NOW), () -> TestLists.genesys(NOW))) {

            assertFalse(refresher.refresh());
        }
        BanlistSnapshot snapshot = repository.snapshot();
        assertEquals(snapshot.tcg(), TestLists.tcg(yesterday));
        assertEquals(snapshot.ocg(), TestLists.ocg(NOW));
        assertEquals(snapshot.genesys(), TestLists.genesys(NOW));
    }

    @Test
    public void startRefreshesWhenStoredSnapshotMissedADailyRun() throws Exception {
        store.save(TestLists.snapshot(Instant.parse("2026-10-04T23:00:00Z"))); // 01:00 Vienna, before 03:00
        CountDownLatch fetched = new CountDownLatch(1);

        try (BanlistRefresher refresher = refresher(() -> {
            fetched.countDown();
            return TestLists.tcg(NOW);
        }, () -> TestLists.ocg(NOW), () -> TestLists.genesys(NOW))) {
            refresher.start();

            assertTrue(fetched.await(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void startUsesStoredSnapshotWhenUpToDate() throws Exception {
        BanlistSnapshot stored = TestLists.snapshot(Instant.parse("2026-10-05T01:05:00Z")); // 03:05 Vienna
        store.save(stored);
        CountDownLatch fetched = new CountDownLatch(1);

        try (BanlistRefresher refresher = refresher(() -> {
            fetched.countDown();
            return TestLists.tcg(NOW);
        }, () -> TestLists.ocg(NOW), () -> TestLists.genesys(NOW))) {
            refresher.start();

            assertEquals(repository.snapshot(), stored);
            assertFalse(fetched.await(500, TimeUnit.MILLISECONDS));
        }
    }

    /** Regression: close() returned while the refresh thread was still writing the snapshot file. */
    @Test
    public void closeWaitsForRunningRefreshToFinishWriting() throws Exception {
        CountDownLatch fetching = new CountDownLatch(1);
        BanlistRefresher refresher = refresher(() -> {
            fetching.countDown();
            sleepIgnoringInterrupts(Duration.ofMillis(300)); // a fetch that does not react to interrupts
            return TestLists.tcg(NOW);
        }, () -> TestLists.ocg(NOW), () -> TestLists.genesys(NOW));
        refresher.start(); // no stored snapshot, so it refreshes right away
        assertTrue(fetching.await(5, TimeUnit.SECONDS));

        refresher.close();

        assertEquals(store.load().orElseThrow().tcg(), TestLists.tcg(NOW));
        try (Stream<Path> files = Files.list(dir)) {
            assertEquals(files.map(path -> path.getFileName().toString()).toList(), List.of("banlists.json"));
        }
    }

    @Test
    public void closeInterruptsBlockedFetchAndSkipsTheRest() throws Exception {
        CountDownLatch fetching = new CountDownLatch(1);
        AtomicBoolean laterFetchCalled = new AtomicBoolean();
        BanlistRefresher refresher = refresher(() -> {
            fetching.countDown();
            Thread.sleep(60_000); // like an HTTP request, reacts to interrupts
            return TestLists.tcg(NOW);
        }, () -> {
            laterFetchCalled.set(true);
            return TestLists.ocg(NOW);
        }, () -> {
            laterFetchCalled.set(true);
            return TestLists.genesys(NOW);
        });
        refresher.start();
        assertTrue(fetching.await(5, TimeUnit.SECONDS));

        Instant closing = Instant.now();
        refresher.close();

        assertTrue(Duration.between(closing, Instant.now()).toSeconds() < 5, "close() took too long");
        assertFalse(laterFetchCalled.get(), "fetches must not start after close()");
        assertTrue(store.load().isEmpty());
    }

    private static final Instant FRESH = Instant.parse("2026-10-05T01:05:00Z"); // 03:05 Vienna, after the daily run
    private static final Instant STALE = Instant.parse("2026-10-04T23:00:00Z"); // 01:00 Vienna, before the daily run

    private static final Instant AT_DAILY_RUN = Instant.parse("2026-10-05T01:00:00Z"); // exactly 03:00 Vienna

    @DataProvider
    public Object[][] storedFetchTimes() {
        // tcg, ocg, genesys fetchedAt in the repository (null = missing), then expected fetch calls tcg, ocg, genesys
        return new Object[][]{
                {null, null, null, 1, 1, 1},
                {STALE, STALE, STALE, 1, 1, 1},
                {FRESH, FRESH, STALE, 0, 0, 1},
                {FRESH, STALE, FRESH, 0, 1, 0},
                {STALE, FRESH, FRESH, 1, 0, 0},
                {FRESH, FRESH, null, 0, 0, 1},
                {FRESH, FRESH, FRESH, 0, 0, 0},
                {AT_DAILY_RUN, AT_DAILY_RUN, AT_DAILY_RUN, 0, 0, 0},
        };
    }

    @Test(dataProvider = "storedFetchTimes")
    public void refreshFetchesOnlyListsOlderThanTheLastDailyRun(Instant tcgAt, Instant ocgAt, Instant genesysAt,
                                                                int tcgCalls, int ocgCalls, int genesysCalls) {
        repository.replace(new BanlistSnapshot(
                tcgAt == null ? null : TestLists.tcg(tcgAt),
                ocgAt == null ? null : TestLists.ocg(ocgAt),
                genesysAt == null ? null : TestLists.genesys(genesysAt)));
        AtomicInteger tcg = new AtomicInteger();
        AtomicInteger ocg = new AtomicInteger();
        AtomicInteger genesys = new AtomicInteger();

        try (BanlistRefresher refresher = refresher(
                () -> {
                    tcg.incrementAndGet();
                    return TestLists.tcg(NOW);
                }, () -> {
                    ocg.incrementAndGet();
                    return TestLists.ocg(NOW);
                }, () -> {
                    genesys.incrementAndGet();
                    return TestLists.genesys(NOW);
                })) {
            assertTrue(refresher.refresh());
        }
        assertEquals(new int[]{tcg.get(), ocg.get(), genesys.get()}, new int[]{tcgCalls, ocgCalls, genesysCalls});
        BanlistSnapshot snapshot = repository.snapshot();
        assertEquals(snapshot.tcg().fetchedAt(), tcgCalls == 1 ? NOW : tcgAt);
        assertEquals(snapshot.ocg().fetchedAt(), ocgCalls == 1 ? NOW : ocgAt);
        assertEquals(snapshot.genesys().fetchedAt(), genesysCalls == 1 ? NOW : genesysAt);
    }

    /** Regression: a daily run firing a moment before 03:00 judged yesterday's lists as fresh and skipped them. */
    @Test
    public void runFiringJustBefore03FetchesListsFromTheDayBefore() {
        Instant early = Instant.parse("2026-10-05T00:59:59.900Z"); // 02:59:59.9 Vienna
        repository.replace(TestLists.snapshot(Instant.parse("2026-10-04T01:05:00Z"))); // 03:05 the day before
        AtomicInteger calls = new AtomicInteger();

        try (BanlistRefresher refresher = new BanlistRefresher(repository, store,
                () -> {
                    calls.incrementAndGet();
                    return TestLists.tcg(early);
                }, () -> {
                    calls.incrementAndGet();
                    return TestLists.ocg(early);
                }, () -> {
                    calls.incrementAndGet();
                    return TestLists.genesys(early);
                }, Clock.fixed(early, BanlistRefresher.ZONE))) {
            assertTrue(refresher.refresh());
        }
        assertEquals(calls.get(), 3);
    }

    @Test
    public void retryFetchesOnlyTheFailedListUntilTheNextDailyRun() {
        AtomicReference<Instant> time = new AtomicReference<>(NOW);
        Clock movingClock = new Clock() {
            @Override
            public ZoneId getZone() {
                return BanlistRefresher.ZONE;
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return time.get();
            }
        };
        AtomicInteger tcg = new AtomicInteger();
        AtomicInteger ocg = new AtomicInteger();
        AtomicInteger genesys = new AtomicInteger();
        AtomicBoolean genesysDown = new AtomicBoolean(true);

        try (BanlistRefresher refresher = new BanlistRefresher(repository, store,
                () -> {
                    tcg.incrementAndGet();
                    return TestLists.tcg(time.get());
                }, () -> {
                    ocg.incrementAndGet();
                    return TestLists.ocg(time.get());
                }, () -> {
                    genesys.incrementAndGet();
                    if (genesysDown.get()) {
                        throw new IOException("Konami is down");
                    }
                    return TestLists.genesys(time.get());
                }, movingClock)) {
            assertFalse(refresher.refresh());
            assertEquals(new int[]{tcg.get(), ocg.get(), genesys.get()}, new int[]{1, 1, 1});

            time.set(NOW.plus(Duration.ofHours(1)));
            assertFalse(refresher.refresh());
            assertEquals(new int[]{tcg.get(), ocg.get(), genesys.get()}, new int[]{1, 1, 2});

            genesysDown.set(false);
            time.set(NOW.plus(Duration.ofHours(2)));
            assertTrue(refresher.refresh());
            assertEquals(new int[]{tcg.get(), ocg.get(), genesys.get()}, new int[]{1, 1, 3});

            time.set(Instant.parse("2026-10-06T01:00:00Z")); // next 03:00 Vienna
            assertTrue(refresher.refresh());
            assertEquals(new int[]{tcg.get(), ocg.get(), genesys.get()}, new int[]{2, 2, 4});
        }
    }

    @Test
    public void nextDailyRunIsTodayBeforeThreeAndTomorrowAfter() {
        ZonedDateTime beforeThree = ZonedDateTime.of(2026, 10, 5, 2, 59, 0, 0, BanlistRefresher.ZONE);
        ZonedDateTime atThree = beforeThree.withHour(3).withMinute(0);

        assertEquals(BanlistRefresher.nextDailyRun(beforeThree), atThree);
        assertEquals(BanlistRefresher.nextDailyRun(atThree), atThree.plusDays(1));
        assertEquals(BanlistRefresher.previousDailyRun(atThree.withHour(15)), atThree);
    }

    private static void sleepIgnoringInterrupts(Duration duration) {
        long end = System.nanoTime() + duration.toNanos();
        while (System.nanoTime() < end) {
            LockSupport.parkNanos(end - System.nanoTime());
        }
    }

    private BanlistRefresher refresher(ListFetcher<TcgBanlist> tcg, ListFetcher<OcgBanlist> ocg,
                                       ListFetcher<GenesysPointlist> genesys) {
        return new BanlistRefresher(repository, store, tcg, ocg, genesys, CLOCK);
    }
}
