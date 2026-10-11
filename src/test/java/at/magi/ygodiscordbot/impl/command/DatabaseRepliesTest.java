package at.magi.ygodiscordbot.impl.command;

import at.magi.ygodiscordbot.testutil.FakeInteractions;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.requests.RestAction;
import org.slf4j.MDC;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

public class DatabaseRepliesTest {

    private static final DatabaseReplies.Texts TEXTS = new DatabaseReplies.Texts("busy", "unavailable");

    private FakeInteractions fakes;

    @BeforeMethod
    public void newFakes() {
        fakes = new FakeInteractions();
    }

    /** A deferReply() action that Discord accepts (success) or rejects, e.g. because the interaction timed out. */
    private static RestAction<InteractionHook> defer(boolean accepted, InteractionHook hook) {
        return accepted ? FakeInteractions.succeeding(RestAction.class, hook)
                : FakeInteractions.failing(RestAction.class, new IllegalStateException("Unknown interaction"));
    }

    @Test
    public void failedDeferNeverRunsTheCall() {
        List<String> calls = new ArrayList<>();
        DatabaseReplies.afterDefer(defer(false, null), Runnable::run, TEXTS, "/points add", () -> {
            calls.add("ran");
            return "done";
        });
        assertTrue(calls.isEmpty(), "a write must not happen when the user was told the command failed");
    }

    @Test
    public void acceptedDeferRunsTheCallAndShowsItsResult() {
        DatabaseReplies.afterDefer(defer(true, fakes.hook()), Runnable::run, TEXTS, "/points add", () -> "done");
        assertEquals(fakes.sent(), List.of("edit:done"));
    }

    @Test
    public void databaseErrorShowsUnavailable() {
        DatabaseReplies.afterDefer(defer(true, fakes.hook()), Runnable::run, TEXTS, "/points add", () -> {
            throw new java.sql.SQLException("down");
        });
        assertEquals(fakes.sent(), List.of("edit:" + TEXTS.unavailable()));
    }

    @Test
    public void fullQueueShowsBusy() {
        DatabaseReplies.afterDefer(defer(true, fakes.hook()), task -> {
            throw new RejectedExecutionException();
        }, TEXTS, "/points add", () -> "done");
        assertEquals(fakes.sent(), List.of("edit:" + TEXTS.busy()));
    }

    @Test
    public void actorIsInTheLogContextOnlyDuringTheCall() {
        DatabaseReplies.afterDefer(defer(true, fakes.hook()), Runnable::run, TEXTS, "/points add by yugi (1)",
                () -> MDC.get(DatabaseReplies.ACTOR));
        assertEquals(fakes.sent(), List.of("edit: [/points add by yugi (1)]"));
        assertNull(MDC.get(DatabaseReplies.ACTOR));
    }

    @Test
    public void listCallSendsTheFirstMessageAsReplyAndTheRestAsFollowUps() {
        DatabaseReplies.afterDeferAll(defer(true, fakes.hook()), Runnable::run, TEXTS, "/deck list",
                () -> List.of("first", "second"));
        assertEquals(fakes.sent(), List.of("edit:first", "follow-up:second (ephemeral)"));
    }

    @Test
    public void listCallErrorShowsUnavailableAndFailedDeferRunsNothing() {
        DatabaseReplies.afterDeferAll(defer(true, fakes.hook()), Runnable::run, TEXTS, "/deck get", () -> {
            throw new java.sql.SQLException("down");
        });
        List<String> runs = new ArrayList<>();
        DatabaseReplies.afterDeferAll(defer(false, null), Runnable::run, TEXTS, "/deck save", () -> {
            runs.add("ran");
            return List.of("saved");
        });
        assertEquals(fakes.sent(), List.of("edit:" + TEXTS.unavailable()));
        assertTrue(runs.isEmpty());
    }

    @Test
    public void deferPassesTheEphemeralFlagAndRunsWorkOnlyAfterAcceptedDefer() {
        List<Boolean> flags = new ArrayList<>();
        for (boolean ephemeral : new boolean[]{true, false}) {
            DatabaseReplies.deferVia(flag -> {
                flags.add(flag);
                return defer(true, fakes.hook());
            }, ephemeral, Runnable::run, TEXTS, "/leaderboard page", hook -> hook.editOriginal("page").queue());
        }
        assertEquals(flags, List.of(true, false));
        assertEquals(fakes.sent(), List.of("edit:page", "edit:page"));
    }

    @Test
    public void deferWithFailedDeferRunsNothingInEitherMode() {
        List<String> runs = new ArrayList<>();
        for (boolean ephemeral : new boolean[]{true, false}) {
            DatabaseReplies.deferVia(flag -> defer(false, null), ephemeral, Runnable::run, TEXTS,
                    "/leaderboard page", hook -> runs.add("queried"));
        }
        assertTrue(runs.isEmpty(), "no query when the user was told the command failed");
    }

    @Test
    public void failedDeferNeverRunsHookWork() {
        List<String> runs = new ArrayList<>();
        DatabaseReplies.afterDefer(defer(false, null), Runnable::run, TEXTS, "/leaderboard-admin share",
                hook -> runs.add("posted"));
        assertTrue(runs.isEmpty(), "nothing may be posted when the user was told the command failed");
    }

    @Test
    public void hookWorkGetsTheHookAndErrorsShowUnavailable() {
        DatabaseReplies.afterDefer(defer(true, fakes.hook()), Runnable::run, TEXTS, "/leaderboard-admin share",
                hook -> hook.editOriginal("posted").queue());
        DatabaseReplies.afterDefer(defer(true, fakes.hook()), Runnable::run, TEXTS, "/leaderboard-admin share",
                hook -> {
                    throw new java.sql.SQLException("down");
                });
        assertEquals(fakes.sent(), List.of("edit:posted", "edit:" + TEXTS.unavailable()));
    }

    /**
     * Race smoke test: 100 commands arrive at once. Like the bot's db executor (one thread, queue of 50), calls
     * must never overlap, and every request gets exactly one reply: its result or "busy".
     */
    @Test
    public void simultaneousCommandsRunOneAtATimeAndAllGetAReply() throws Exception {
        ThreadPoolExecutor database = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(50));
        ExecutorService discord = Executors.newFixedThreadPool(8);
        AtomicInteger running = new AtomicInteger();
        AtomicInteger overlaps = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        try {
            for (int i = 0; i < 100; i++) {
                discord.execute(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    DatabaseReplies.afterDefer(defer(true, fakes.hook()), database, TEXTS, "/points add", () -> {
                        if (running.incrementAndGet() > 1) {
                            overlaps.incrementAndGet();
                        }
                        LockSupport.parkNanos(1_000_000);
                        running.decrementAndGet();
                        return "done";
                    });
                });
            }
            start.countDown();
            discord.shutdown();
            assertTrue(discord.awaitTermination(30, TimeUnit.SECONDS));
            database.shutdown();
            assertTrue(database.awaitTermination(30, TimeUnit.SECONDS));
        } finally {
            discord.shutdownNow();
            database.shutdownNow();
        }
        assertEquals(overlaps.get(), 0);
        List<String> edits = fakes.sent();
        assertEquals(edits.size(), 100);
        assertTrue(edits.stream().allMatch(e -> e.equals("edit:done") || e.equals("edit:" + TEXTS.busy())),
                edits.toString());
        assertTrue(edits.contains("edit:done"));
    }
}
