package at.magi.ygodiscordbot.impl.runtime;

import java.time.Duration;
import java.util.Optional;

/**
 * Decides whether and when to restart the bot after it exited.
 *
 * <p>Crashes are restarted forever, since an unattended bot should recover from outages on its own.
 * Repeated crashes shortly after each start back off exponentially (5 s, 10 s, 20 s, ... up to 5 min),
 * so a persistent problem does not cause a restart loop. After a run of {@link #STABLE_UPTIME} or more,
 * the delay starts over. Not thread-safe; used by the supervisor thread only.
 */
final class RestartPolicy {

    static final Duration INITIAL_DELAY = Duration.ofSeconds(5);
    static final Duration MAX_DELAY = Duration.ofMinutes(5);
    static final Duration STABLE_UPTIME = Duration.ofMinutes(10);

    private int consecutiveCrashes;

    /** Returns the delay before restarting, or empty if the bot should stay stopped. */
    Optional<Duration> afterExit(int exitCode, Duration uptime) {
        if (exitCode == 0 || exitCode == Supervisor.EXIT_CONFIG_ERROR) {
            return Optional.empty();
        }
        consecutiveCrashes = uptime.compareTo(STABLE_UPTIME) >= 0 ? 1 : consecutiveCrashes + 1;
        Duration delay = INITIAL_DELAY.multipliedBy(1L << Math.min(consecutiveCrashes - 1, 16));
        return Optional.of(delay.compareTo(MAX_DELAY) > 0 ? MAX_DELAY : delay);
    }
}
