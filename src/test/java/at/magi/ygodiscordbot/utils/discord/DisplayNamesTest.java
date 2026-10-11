package at.magi.ygodiscordbot.utils.discord;

import at.magi.ygodiscordbot.entity.leaderboard.RankedPlayer;
import org.testng.annotations.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.testng.Assert.assertEquals;

public class DisplayNamesTest {

    /** A clock the test can move forward. */
    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-09T12:00:00Z");

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Test
    public void remembersNamesUntilTheyExpire() {
        MutableClock clock = new MutableClock();
        DisplayNames names = new DisplayNames(clock);
        names.remember(1, "Yugi");
        assertEquals(names.cached(List.of(1L, 2L)), Map.of(1L, "Yugi"));

        clock.now = clock.now.plus(DisplayNames.TTL).plusSeconds(1);
        assertEquals(names.cached(List.of(1L)), Map.of());
    }

    @Test
    public void evictsLeastRecentlyUsedBeyondCapacity() {
        DisplayNames names = new DisplayNames(new MutableClock());
        for (long id = 0; id < DisplayNames.CAPACITY; id++) {
            names.remember(id, "p" + id);
        }
        names.cached(List.of(0L)); // 0 is now the most recently used
        names.remember(DisplayNames.CAPACITY, "new");
        assertEquals(names.cached(List.of(0L, 1L, (long) DisplayNames.CAPACITY)),
                Map.of(0L, "p0", (long) DisplayNames.CAPACITY, "new"));
    }

    @Test
    public void allCachedNeedsNoDiscordRequest() {
        DisplayNames names = new DisplayNames(new MutableClock());
        names.remember(1, "Yugi");
        AtomicReference<Map<Long, String>> result = new AtomicReference<>();
        // A null JDA would throw if a lookup were attempted
        names.resolve(null, List.of(new RankedPlayer(1, 1, 5)), result::set,
                error -> { throw new AssertionError(error); });
        assertEquals(result.get(), Map.of(1L, "Yugi"));
    }

    @Test
    public void ttlIsAnHour() {
        assertEquals(DisplayNames.TTL, Duration.ofHours(1));
    }

    @Test
    public void nameOrUnknownFallsBackToThePlaceholder() {
        Map<Long, String> names = Map.of(1L, "Yugi");
        assertEquals(DisplayNames.nameOrUnknown(names, 1L), "Yugi");
        assertEquals(DisplayNames.nameOrUnknown(names, 99L), "Unknown user (99)");
    }
}
