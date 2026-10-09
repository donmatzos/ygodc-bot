package at.magi.ygodiscordbot.impl.card;

import at.magi.ygodiscordbot.entity.card.CardCatalog;
import at.magi.ygodiscordbot.entity.card.CardNames;
import at.magi.ygodiscordbot.utils.http.ListFetcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Keeps the card names current while calling YGOProDeck as rarely as possible.
 *
 * <p>Every {@value #CHECK_INTERVAL_DAYS} days it asks for the database version (a tiny request) and downloads
 * the full list only if the version changed. Runs on one background thread, the only writer. A failed check
 * keeps the current names and is retried hourly, at most {@value #MAX_RETRIES} times before waiting for the
 * next regular check. The last list is stored, so restarts do not download again.
 */
public final class CardRefresher implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(CardRefresher.class);

    static final int CHECK_INTERVAL_DAYS = 3;
    static final Duration CHECK_INTERVAL = Duration.ofDays(CHECK_INTERVAL_DAYS);
    static final Duration RETRY_DELAY = Duration.ofHours(1);
    static final int MAX_RETRIES = 3;
    /** Below the supervisor's 20 s grace period before it kills the bot. */
    static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(10);

    private final CardRepository repository;
    private final CardFileStore store;
    private final ListFetcher<String> versionFetcher;
    private final ListFetcher<CardNames> cardsFetcher;
    private final Clock clock;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "card-refresh");
        thread.setDaemon(true);
        return thread;
    });
    /** Consecutive failed checks; only used on the scheduler thread. */
    private int failures;

    public CardRefresher(CardRepository repository, CardFileStore store, ListFetcher<String> versionFetcher,
                         ListFetcher<CardNames> cardsFetcher, Clock clock) {
        this.repository = repository;
        this.store = store;
        this.versionFetcher = versionFetcher;
        this.cardsFetcher = cardsFetcher;
        this.clock = clock;
    }

    /** Loads the stored list and schedules the next check: right away if it is due or nothing is stored. */
    public void start() {
        store.load().ifPresent(catalog -> {
            repository.replace(catalog);
            log.info("Loaded {} stored card names (version {}, checked {})",
                    catalog.names().size(), catalog.version(), catalog.checkedAt());
        });
        Duration delay = repository.catalog()
                .map(catalog -> Duration.between(clock.instant(), catalog.checkedAt().plus(CHECK_INTERVAL)))
                .filter(untilDue -> !untilDue.isNegative())
                .orElse(Duration.ZERO);
        schedule(delay);
    }

    /** Checks the version and downloads the list if it changed. Returns whether the check succeeded. */
    public synchronized boolean refresh() {
        Optional<CardCatalog> current = repository.catalog();
        String version = fetch("card database version", versionFetcher);
        if (version == null) {
            return false;
        }
        Instant now = clock.instant();
        CardCatalog updated;
        if (current.isPresent() && current.get().version().equals(version)) {
            updated = current.get().checkedAgain(now);
            log.info("Card list is up to date (version {})", version);
        } else {
            CardNames names = fetch("card list", cardsFetcher);
            if (names == null) {
                return false;
            }
            updated = new CardCatalog(version, now, names);
            log.info("Downloaded {} card names (version {})", names.size(), version);
        }
        repository.replace(updated);
        try {
            store.save(updated);
        } catch (IOException e) {
            log.warn("Could not store card names", e);
        }
        return true;
    }

    private void runAndReschedule() {
        boolean ok = false;
        try {
            ok = refresh();
        } catch (RuntimeException e) {
            log.error("Card list check failed", e);
        }
        Duration delay;
        if (ok) {
            failures = 0;
            delay = CHECK_INTERVAL;
        } else if (++failures <= MAX_RETRIES) {
            delay = RETRY_DELAY;
        } else {
            failures = 0;
            delay = CHECK_INTERVAL;
        }
        log.info("Next card list check in {}", delay);
        schedule(delay);
    }

    private void schedule(Duration delay) {
        if (!scheduler.isShutdown()) {
            scheduler.schedule(this::runAndReschedule, delay.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    private <T> T fetch(String what, ListFetcher<T> fetcher) {
        if (Thread.currentThread().isInterrupted()) {
            // Shutting down: do not start new requests
            return null;
        }
        try {
            return fetcher.fetch();
        } catch (IOException | RuntimeException e) {
            log.warn("Could not fetch {}, keeping the current card names", what, e);
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /**
     * Cancels scheduled checks, interrupts a running download and waits for the thread to finish,
     * so a file write in progress completes before the JVM exits.
     */
    @Override
    public void close() {
        scheduler.shutdownNow();
        try {
            if (!scheduler.awaitTermination(CLOSE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                log.warn("Card list check did not stop within {} s", CLOSE_TIMEOUT.toSeconds());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
