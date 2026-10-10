package at.magi.ygodiscordbot.impl.command;

import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.requests.RestAction;
import org.slf4j.MDC;
import org.testng.annotations.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
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
import java.util.function.Consumer;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

public class DatabaseRepliesTest {

    private static final DatabaseReplies.Texts TEXTS = new DatabaseReplies.Texts("busy", "unavailable");

    /** A deferReply() action that Discord accepts (success) or rejects, e.g. because the interaction timed out. */
    @SuppressWarnings("unchecked")
    private static RestAction<InteractionHook> defer(boolean accepted, InteractionHook hook) {
        return (RestAction<InteractionHook>) Proxy.newProxyInstance(RestAction.class.getClassLoader(),
                new Class<?>[]{RestAction.class}, (proxy, method, args) -> {
                    if (method.getName().equals("queue") && args != null && args.length == 2) {
                        if (accepted) {
                            ((Consumer<Object>) args[0]).accept(hook);
                        } else {
                            ((Consumer<Object>) args[1]).accept(new IllegalStateException("Unknown interaction"));
                        }
                        return null;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    /** A hook that records every editOriginal text; the returned action's queue() does nothing. */
    private static InteractionHook recordingHook(List<String> edits) {
        return (InteractionHook) Proxy.newProxyInstance(InteractionHook.class.getClassLoader(),
                new Class<?>[]{InteractionHook.class}, (proxy, method, args) -> {
                    if (method.getName().equals("editOriginal") && args.length == 1 && args[0] instanceof String text) {
                        edits.add(text);
                        return Proxy.newProxyInstance(RestAction.class.getClassLoader(),
                                new Class<?>[]{method.getReturnType()}, (p, m, a) -> null);
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    /**
     * A hook that records edits and follow-ups in send order ("edit:a", "follow-up:b"). Its actions run flatMap
     * right away, so a chain of messages is recorded as it would be sent.
     */
    private static InteractionHook sendingHook(List<String> sent) {
        return (InteractionHook) Proxy.newProxyInstance(InteractionHook.class.getClassLoader(),
                new Class<?>[]{InteractionHook.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "editOriginal" -> {
                        sent.add("edit:" + args[0]);
                        yield chainable(method.getReturnType());
                    }
                    case "sendMessage" -> {
                        sent.add("follow-up:" + args[0]);
                        yield chainable(method.getReturnType());
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    @SuppressWarnings("unchecked")
    private static Object chainable(Class<?> type) {
        return Proxy.newProxyInstance(RestAction.class.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) ->
                switch (method.getName()) {
                    case "flatMap" -> ((java.util.function.Function<Object, Object>) args[args.length - 1]).apply(null);
                    case "setEphemeral" -> proxy;
                    case "queue" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
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
        List<String> edits = new ArrayList<>();
        DatabaseReplies.afterDefer(defer(true, recordingHook(edits)), Runnable::run, TEXTS, "/points add", () -> "done");
        assertEquals(edits, List.of("done"));
    }

    @Test
    public void databaseErrorShowsUnavailable() {
        List<String> edits = new ArrayList<>();
        DatabaseReplies.afterDefer(defer(true, recordingHook(edits)), Runnable::run, TEXTS, "/points add", () -> {
            throw new java.sql.SQLException("down");
        });
        assertEquals(edits, List.of(TEXTS.unavailable()));
    }

    @Test
    public void fullQueueShowsBusy() {
        List<String> edits = new ArrayList<>();
        DatabaseReplies.afterDefer(defer(true, recordingHook(edits)), task -> {
            throw new RejectedExecutionException();
        }, TEXTS, "/points add", () -> "done");
        assertEquals(edits, List.of(TEXTS.busy()));
    }

    @Test
    public void actorIsInTheLogContextOnlyDuringTheCall() {
        List<String> edits = new ArrayList<>();
        DatabaseReplies.afterDefer(defer(true, recordingHook(edits)), Runnable::run, TEXTS, "/points add by yugi (1)",
                () -> MDC.get(DatabaseReplies.ACTOR));
        assertEquals(edits, List.of(" [/points add by yugi (1)]"));
        assertNull(MDC.get(DatabaseReplies.ACTOR));
    }

    @Test
    public void listCallSendsTheFirstMessageAsReplyAndTheRestAsFollowUps() {
        List<String> sent = new ArrayList<>();
        DatabaseReplies.afterDeferAll(defer(true, sendingHook(sent)), Runnable::run, TEXTS, "/deck list",
                () -> List.of("first", "second"));
        assertEquals(sent, List.of("edit:first", "follow-up:second"));
    }

    @Test
    public void listCallErrorShowsUnavailableAndFailedDeferRunsNothing() {
        List<String> sent = new ArrayList<>();
        DatabaseReplies.afterDeferAll(defer(true, sendingHook(sent)), Runnable::run, TEXTS, "/deck get", () -> {
            throw new java.sql.SQLException("down");
        });
        DatabaseReplies.afterDeferAll(defer(false, null), Runnable::run, TEXTS, "/deck save", () -> {
            sent.add("ran");
            return List.of("saved");
        });
        assertEquals(sent, List.of("edit:" + TEXTS.unavailable()));
    }

    @Test
    public void deferPassesTheEphemeralFlagAndRunsWorkOnlyAfterAcceptedDefer() {
        List<Boolean> flags = new ArrayList<>();
        List<String> edits = new ArrayList<>();
        for (boolean ephemeral : new boolean[]{true, false}) {
            DatabaseReplies.deferVia(flag -> {
                flags.add(flag);
                return defer(true, recordingHook(edits));
            }, ephemeral, Runnable::run, TEXTS, "/leaderboard page", hook -> hook.editOriginal("page").queue());
        }
        assertEquals(flags, List.of(true, false));
        assertEquals(edits, List.of("page", "page"));
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
        List<String> edits = new ArrayList<>();
        DatabaseReplies.afterDefer(defer(true, recordingHook(edits)), Runnable::run, TEXTS, "/leaderboard-admin share",
                hook -> hook.editOriginal("posted").queue());
        DatabaseReplies.afterDefer(defer(true, recordingHook(edits)), Runnable::run, TEXTS, "/leaderboard-admin share",
                hook -> {
                    throw new java.sql.SQLException("down");
                });
        assertEquals(edits, List.of("posted", TEXTS.unavailable()));
    }

    /**
     * Race smoke test: 100 commands arrive at once. Like the bot's db executor (one thread, queue of 50), calls
     * must never overlap, and every request gets exactly one reply: its result or "busy".
     */
    @Test
    public void simultaneousCommandsRunOneAtATimeAndAllGetAReply() throws Exception {
        ThreadPoolExecutor database = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(50));
        ExecutorService discord = Executors.newFixedThreadPool(8);
        List<String> edits = Collections.synchronizedList(new ArrayList<>());
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
                    DatabaseReplies.afterDefer(defer(true, recordingHook(edits)), database, TEXTS, "/points add", () -> {
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
        assertEquals(edits.size(), 100);
        assertTrue(edits.stream().allMatch(e -> e.equals("done") || e.equals(TEXTS.busy())), edits.toString());
        assertTrue(edits.contains("done"));
    }
}
