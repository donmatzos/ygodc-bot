package at.magi.ygodiscordbot.impl.card;

import at.magi.ygodiscordbot.entity.card.CardCatalog;
import at.magi.ygodiscordbot.entity.card.CardNames;
import at.magi.ygodiscordbot.utils.concurrent.RefreshLoop;
import at.magi.ygodiscordbot.utils.http.Fetching;
import at.magi.ygodiscordbot.utils.http.ListFetcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Keeps the card names current while calling YGOProDeck as rarely as possible.
 *
 * <p>Every {@value #CHECK_INTERVAL_DAYS} days it asks for the database version (a tiny request) and downloads
 * the full list only if the version changed. Runs on one background thread, the only writer. A failed check
 * keeps the current names and is retried hourly, at most {@value #MAX_RETRIES} times before waiting for the
 * next regular check (unless no list was ever loaded: then it keeps retrying hourly). The last list is
 * stored, so restarts do not download again.
 */
public final class CardRefresher implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(CardRefresher.class);

    static final int CHECK_INTERVAL_DAYS = 3;
    static final Duration CHECK_INTERVAL = Duration.ofDays(CHECK_INTERVAL_DAYS);
    static final Duration RETRY_DELAY = Duration.ofHours(1);
    static final int MAX_RETRIES = 3;

    private final CardRepository repository;
    private final CardFileStore store;
    private final ListFetcher<String> versionFetcher;
    private final ListFetcher<CardNames> cardsFetcher;
    private final Clock clock;
    private final RefreshLoop loop = new RefreshLoop("card-refresh", "Card list check");
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
        String version = Fetching.orNull(log, "card database version", versionFetcher);
        if (version == null) {
            return false;
        }
        Instant now = clock.instant();
        CardCatalog updated;
        if (current.isPresent() && current.get().version().equals(version)) {
            updated = current.get().checkedAgain(now);
            log.info("Card list is up to date (version {})", version);
        } else {
            CardNames names = Fetching.orNull(log, "card list", cardsFetcher);
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
        failures = ok ? 0 : failures + 1;
        Duration delay = nextDelay(ok, failures, repository.catalog().isPresent());
        if (delay.equals(CHECK_INTERVAL)) {
            failures = 0;
        }
        log.info("Next card list check in {}", delay);
        schedule(delay);
    }

    /**
     * Delay until the next check; {@code failures} already counts the failed check just made.
     * Without any catalog the names are missing, so it keeps retrying hourly instead of waiting for the
     * next regular check.
     */
    static Duration nextDelay(boolean ok, int failures, boolean haveCatalog) {
        if (ok) {
            return CHECK_INTERVAL;
        }
        return failures <= MAX_RETRIES || !haveCatalog ? RETRY_DELAY : CHECK_INTERVAL;
    }

    private void schedule(Duration delay) {
        loop.schedule(delay, this::runAndReschedule);
    }

    /**
     * Cancels scheduled checks, interrupts a running download and waits for the thread to finish,
     * so a file write in progress completes before the JVM exits.
     */
    @Override
    public void close() {
        loop.close();
    }
}
