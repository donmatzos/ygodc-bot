package at.magi.ygodiscordbot.impl.tournament;

import at.magi.ygodiscordbot.utils.concurrent.DaemonThreads;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Every {@link #INTERVAL} queues the 48-hour check on the {@code db} executor, so it runs in order with the commands
 * and needs no locking.
 */
public final class TournamentTimer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(TournamentTimer.class);

    static final Duration INTERVAL = Duration.ofMinutes(5);

    @FunctionalInterface
    public interface Check {
        void run() throws SQLException;
    }

    private final Check check;
    private final Executor dbExecutor;
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(DaemonThreads.named("tournament-timer"));

    public TournamentTimer(Check check, Executor dbExecutor) {
        this.check = check;
        this.dbExecutor = dbExecutor;
    }

    public void start() {
        long minutes = INTERVAL.toMinutes();
        scheduler.scheduleWithFixedDelay(() -> check(check, dbExecutor), minutes, minutes, TimeUnit.MINUTES);
    }

    /** Never throws: an exception would cancel the scheduled task for good. */
    static void check(Check check, Executor dbExecutor) {
        try {
            dbExecutor.execute(() -> {
                try {
                    check.run();
                } catch (SQLException | RuntimeException e) {
                    log.warn("Tournament timeout check failed, retried in {} minutes", INTERVAL.toMinutes(), e);
                }
            });
        } catch (RejectedExecutionException e) {
            log.warn("Request queue full, tournament timeout check skipped");
        }
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }
}
