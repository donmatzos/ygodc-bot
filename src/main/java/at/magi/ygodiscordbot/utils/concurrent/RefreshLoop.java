package at.magi.ygodiscordbot.utils.concurrent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * A named daemon thread that runs refresh tasks one at a time, the only writer of what it refreshes.
 *
 * <p>Nothing is scheduled once the loop is closed; {@link #close()} interrupts a running task and waits
 * a bounded time for it, so a file write in progress can complete before the JVM exits.
 */
public final class RefreshLoop implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RefreshLoop.class);

    /** Kept short so all shutdown steps together stay below the supervisor's 20 s grace period (see YgoDiscordBot). */
    public static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(5);

    private final String stopWarning;
    private final Duration closeTimeout;
    private final ScheduledExecutorService scheduler;

    /** @param stopWarning what is logged as "{stopWarning} did not stop within N s" if close() times out */
    public RefreshLoop(String threadName, String stopWarning) {
        this(threadName, stopWarning, CLOSE_TIMEOUT);
    }

    public RefreshLoop(String threadName, String stopWarning, Duration closeTimeout) {
        this.stopWarning = stopWarning;
        this.closeTimeout = closeTimeout;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(DaemonThreads.named(threadName));
    }

    /** Runs the task once after the delay, unless the loop is closed by then. */
    public void schedule(Duration delay, Runnable task) {
        if (!scheduler.isShutdown()) {
            scheduler.schedule(task, delay.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    /** Cancels scheduled tasks, interrupts a running one and waits up to the close timeout for it. */
    @Override
    public void close() {
        scheduler.shutdownNow();
        try {
            if (!scheduler.awaitTermination(closeTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                log.warn("{} did not stop within {} s", stopWarning, closeTimeout.toSeconds());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
