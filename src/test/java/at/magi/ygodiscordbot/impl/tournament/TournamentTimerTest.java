package at.magi.ygodiscordbot.impl.tournament;

import org.testng.annotations.Test;

import java.sql.SQLException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.fail;

public class TournamentTimerTest {

    @Test
    public void checkRunsOnTheDatabaseExecutor() {
        AtomicInteger runs = new AtomicInteger();
        TournamentTimer.check(runs::incrementAndGet, Runnable::run);
        assertEquals(runs.get(), 1);
    }

    @Test
    public void fullQueueSkipsTheCheck() {
        TournamentTimer.check(() -> fail("must not run"), task -> {
            throw new RejectedExecutionException("full");
        });
    }

    @Test
    public void failingCheckDoesNotKillTheTimer() {
        TournamentTimer.check(() -> {
            throw new SQLException("down");
        }, Runnable::run);
    }
}
