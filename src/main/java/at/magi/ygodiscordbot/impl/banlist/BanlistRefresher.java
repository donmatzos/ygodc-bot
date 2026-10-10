package at.magi.ygodiscordbot.impl.banlist;

import at.magi.ygodiscordbot.entity.banlist.BanlistSnapshot;
import at.magi.ygodiscordbot.entity.banlist.GenesysPointlist;
import at.magi.ygodiscordbot.entity.banlist.OcgBanlist;
import at.magi.ygodiscordbot.entity.banlist.TcgBanlist;
import at.magi.ygodiscordbot.utils.http.ListFetcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Refreshes TCG, OCG and Genesys once a day on a single background thread, the only writer.
 *
 * <p>Each list is fetched independently: a list whose source fails keeps its previous version, and
 * the refresh is retried hourly until all sources succeed. Readers are never blocked or left without data.
 */
public final class BanlistRefresher implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(BanlistRefresher.class);

    public static final LocalTime DAILY_RUN = LocalTime.of(3, 0);
    public static final ZoneId ZONE = ZoneId.of("Europe/Vienna");
    /** A run firing slightly early (truncated delay, wall clock adjusted) still counts as the 03:00 run. */
    static final Duration EARLY_TOLERANCE = Duration.ofSeconds(5);
    static final Duration RETRY_DELAY = Duration.ofHours(1);
    /** Kept short so all shutdown steps together stay below the supervisor's 20 s grace period (see YgoDiscordBot). */
    static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(5);

    private final BanlistRepository repository;
    private final SnapshotFileStore store;
    private final ListFetcher<TcgBanlist> tcgFetcher;
    private final ListFetcher<OcgBanlist> ocgFetcher;
    private final ListFetcher<GenesysPointlist> genesysFetcher;
    private final Clock clock;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "banlist-refresh");
        thread.setDaemon(true);
        return thread;
    });

    public BanlistRefresher(BanlistRepository repository, SnapshotFileStore store,
                            ListFetcher<TcgBanlist> tcgFetcher, ListFetcher<OcgBanlist> ocgFetcher,
                            ListFetcher<GenesysPointlist> genesysFetcher, Clock clock) {
        this.repository = repository;
        this.store = store;
        this.tcgFetcher = tcgFetcher;
        this.ocgFetcher = ocgFetcher;
        this.genesysFetcher = genesysFetcher;
        this.clock = clock.withZone(ZONE);
    }

    /**
     * Loads the stored snapshot and schedules refreshes. Refreshes right away if a list is missing
     * or a daily run was missed while the bot was offline. Does not block.
     */
    public void start() {
        store.load().ifPresent(snapshot -> {
            repository.replace(snapshot);
            log.info("Loaded stored banlists (oldest fetched {})",
                    snapshot.oldestFetch().map(Instant::toString).orElse("never"));
        });

        ZonedDateTime now = ZonedDateTime.now(clock);
        boolean upToDate = repository.snapshot().oldestFetch()
                .filter(oldest -> !oldest.isBefore(previousDailyRun(now).toInstant()))
                .isPresent();
        if (upToDate) {
            schedule(Duration.between(now, nextDailyRun(now)));
        } else {
            scheduler.execute(this::runAndReschedule);
        }
    }

    /**
     * Fetches the lists that are missing or older than the last daily run, keeps the others, and swaps
     * in the new snapshot, so a retry does not download lists that already succeeded.
     * Returns whether every list is up to date afterwards.
     */
    public synchronized boolean refresh() {
        BanlistSnapshot old = repository.snapshot();
        Instant lastDailyRun = previousDailyRun(ZonedDateTime.now(clock).plus(EARLY_TOLERANCE)).toInstant();
        FetchResult<TcgBanlist> tcg = fetchIfStale("TCG", old.tcg(), TcgBanlist::fetchedAt, lastDailyRun, tcgFetcher);
        FetchResult<OcgBanlist> ocg = fetchIfStale("OCG", old.ocg(), OcgBanlist::fetchedAt, lastDailyRun, ocgFetcher);
        FetchResult<GenesysPointlist> genesys = fetchIfStale("Genesys", old.genesys(),
                GenesysPointlist::fetchedAt, lastDailyRun, genesysFetcher);

        BanlistSnapshot updated = new BanlistSnapshot(tcg.list(), ocg.list(), genesys.list());
        repository.replace(updated);

        if (tcg.fetched() || ocg.fetched() || genesys.fetched()) {
            try {
                store.save(updated);
            } catch (IOException e) {
                log.warn("Could not store banlists", e);
            }
        }
        return !tcg.failed() && !ocg.failed() && !genesys.failed();
    }

    private void runAndReschedule() {
        boolean complete = false;
        try {
            complete = refresh();
        } catch (RuntimeException e) {
            log.error("Banlist refresh failed", e);
        }
        ZonedDateTime now = ZonedDateTime.now(clock);
        Duration untilDaily = Duration.between(now, nextDailyRun(now));
        Duration delay = complete || untilDaily.compareTo(RETRY_DELAY) < 0 ? untilDaily : RETRY_DELAY;
        log.info("Banlist refresh {}; next run in {}", complete ? "complete" : "incomplete", delay);
        schedule(delay);
    }

    private void schedule(Duration delay) {
        if (!scheduler.isShutdown()) {
            scheduler.schedule(this::runAndReschedule, delay.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    /** The list to keep in the snapshot (null if never fetched), whether it was fetched just now, and whether the fetch failed. */
    private record FetchResult<T>(T list, boolean fetched, boolean failed) {
    }

    private <T> FetchResult<T> fetchIfStale(String name, T current, Function<T, Instant> fetchedAt,
                                            Instant lastDailyRun, ListFetcher<T> fetcher) {
        if (current != null && !fetchedAt.apply(current).isBefore(lastDailyRun)) {
            log.info("{} is up to date, skipped", name);
            return new FetchResult<>(current, false, false);
        }
        T list = fetch(name, fetcher);
        return list != null ? new FetchResult<>(list, true, false) : new FetchResult<>(current, false, true);
    }

    private <T> T fetch(String name, ListFetcher<T> fetcher) {
        if (Thread.currentThread().isInterrupted()) {
            // Shutting down: do not start new requests
            return null;
        }
        try {
            T list = fetcher.fetch();
            log.info("Fetched {} list", name);
            return list;
        } catch (IOException | RuntimeException e) {
            log.warn("Could not fetch {} list, keeping the previous one", name, e);
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    static ZonedDateTime nextDailyRun(ZonedDateTime now) {
        ZonedDateTime today = now.toLocalDate().atTime(DAILY_RUN).atZone(now.getZone());
        return today.isAfter(now) ? today : today.plusDays(1);
    }

    static ZonedDateTime previousDailyRun(ZonedDateTime now) {
        return nextDailyRun(now).minusDays(1);
    }

    /**
     * Cancels scheduled runs, interrupts a running fetch and waits for the refresh thread to finish,
     * so a snapshot write in progress completes before the JVM exits.
     */
    @Override
    public void close() {
        scheduler.shutdownNow();
        try {
            if (!scheduler.awaitTermination(CLOSE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                log.warn("Banlist refresh did not stop within {} s", CLOSE_TIMEOUT.toSeconds());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
