package at.magi.ygodiscordbot.impl.banlist;

import at.magi.ygodiscordbot.entity.banlist.BanlistSnapshot;
import org.testng.annotations.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

public class BanlistRepositoryTest {

    @Test
    public void emptyUntilFirstRefresh() {
        BanlistRepository repository = TestLists.repository();

        assertTrue(repository.tcg().isEmpty());
        assertTrue(repository.ocg().isEmpty());
        assertTrue(repository.genesys().isEmpty());
    }

    @Test
    public void readersNeverSeeAMixOfOldAndNewLists() throws Exception {
        BanlistRepository repository = TestLists.repository();
        repository.replace(TestLists.snapshot(Instant.ofEpochSecond(0)));
        AtomicBoolean writing = new AtomicBoolean(true);

        ExecutorService readers = Executors.newFixedThreadPool(4);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                results.add(readers.submit(() -> {
                    int reads = 0;
                    while (writing.get()) {
                        BanlistSnapshot snapshot = repository.snapshot();
                        // Every snapshot is written with one timestamp for all lists.
                        assertEquals(snapshot.ocg().fetchedAt(), snapshot.tcg().fetchedAt());
                        assertEquals(snapshot.genesys().fetchedAt(), snapshot.tcg().fetchedAt());
                        reads++;
                    }
                    return reads;
                }));
            }

            for (int i = 1; i <= 20_000; i++) {
                repository.replace(TestLists.snapshot(Instant.ofEpochSecond(i)));
            }
            writing.set(false);

            for (Future<Integer> result : results) {
                assertTrue(result.get() > 0);
            }
        } finally {
            writing.set(false);
            readers.shutdownNow();
        }
    }
}
