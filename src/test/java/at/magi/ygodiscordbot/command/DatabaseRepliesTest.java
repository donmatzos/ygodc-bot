package at.magi.ygodiscordbot.command;

import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.requests.RestAction;
import org.testng.annotations.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

public class DatabaseRepliesTest {

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

    @Test
    public void failedDeferNeverRunsTheCall() {
        List<String> calls = new ArrayList<>();
        DatabaseReplies.afterDefer(defer(false, null), Runnable::run, "/points add", () -> {
            calls.add("ran");
            return "done";
        });
        assertTrue(calls.isEmpty(), "a write must not happen when the user was told the command failed");
    }

    @Test
    public void acceptedDeferRunsTheCallAndShowsItsResult() {
        List<String> edits = new ArrayList<>();
        DatabaseReplies.afterDefer(defer(true, recordingHook(edits)), Runnable::run, "/points add", () -> "done");
        assertEquals(edits, List.of("done"));
    }

    @Test
    public void databaseErrorShowsUnavailable() {
        List<String> edits = new ArrayList<>();
        DatabaseReplies.afterDefer(defer(true, recordingHook(edits)), Runnable::run, "/points add", () -> {
            throw new java.sql.SQLException("down");
        });
        assertEquals(edits, List.of(LeaderboardCommand.UNAVAILABLE));
    }

    @Test
    public void fullQueueShowsBusy() {
        List<String> edits = new ArrayList<>();
        DatabaseReplies.afterDefer(defer(true, recordingHook(edits)), task -> {
            throw new RejectedExecutionException();
        }, "/points add", () -> "done");
        assertEquals(edits, List.of(LeaderboardCommand.BUSY));
    }
}
