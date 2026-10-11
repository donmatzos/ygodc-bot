package at.magi.ygodiscordbot.utils.discord;

import at.magi.ygodiscordbot.testutil.MutableClock;
import org.testng.annotations.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.testng.Assert.assertEquals;

public class DisplayNamesTest {

    private static final Instant START = Instant.parse("2026-10-09T12:00:00Z");

    @Test
    public void remembersNamesUntilTheyExpire() {
        MutableClock clock = new MutableClock(START);
        DisplayNames names = new DisplayNames(clock);
        names.remember(1, "Yugi");
        assertEquals(names.cached(List.of(1L, 2L)), Map.of(1L, "Yugi"));

        clock.advance(DisplayNames.TTL.plusSeconds(1));
        assertEquals(names.cached(List.of(1L)), Map.of());
    }

    @Test
    public void evictsLeastRecentlyUsedBeyondCapacity() {
        DisplayNames names = new DisplayNames(new MutableClock(START));
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
        DisplayNames names = new DisplayNames(new MutableClock(START));
        names.remember(1, "Yugi");
        AtomicReference<Map<Long, String>> result = new AtomicReference<>();
        // A null JDA would throw if a lookup were attempted
        names.resolveIds(null, List.of(1L), result::set,
                error -> { throw new AssertionError(error); });
        assertEquals(result.get(), Map.of(1L, "Yugi"));
    }

    @Test
    public void nameOrUnknownFallsBackToThePlaceholder() {
        Map<Long, String> names = Map.of(1L, "Yugi");
        assertEquals(DisplayNames.nameOrUnknown(names, 1L), "Yugi");
        assertEquals(DisplayNames.nameOrUnknown(names, 99L), "Unknown user (99)");
    }
}
