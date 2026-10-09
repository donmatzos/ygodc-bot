package at.magi.ygodiscordbot.impl.command;

import at.magi.ygodiscordbot.utils.discord.MessageSender;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.requests.RestAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * Runs database work off the JDA event thread, only after Discord accepted the (ephemeral) deferred reply. While it
 * runs, the log context holds who triggered it, so every log line of the work (e.g. a points change) names them.
 */
public final class DatabaseReplies {

    private static final Logger log = LoggerFactory.getLogger(DatabaseReplies.class);

    /** MDC key; the logback pattern appends it to every line, e.g. " [/points add by yugi (123)]". */
    static final String ACTOR = "actor";

    /** What the user sees when the request queue is full or the database work failed. */
    public record Texts(String busy, String unavailable) {
    }

    /** Work whose result is the reply text. */
    @FunctionalInterface
    public interface SqlCall {
        String call() throws SQLException;
    }

    /** Work whose result is a reply split into several messages; the first replaces the deferred reply. */
    @FunctionalInterface
    public interface SqlListCall {
        List<String> call() throws SQLException;
    }

    /** Work that replies itself through the hook (e.g. after posting into a channel). */
    @FunctionalInterface
    public interface SqlWork {
        void run(InteractionHook hook) throws SQLException;
    }

    private DatabaseReplies() {
    }

    public static void replyEphemeral(SlashCommandInteractionEvent event, Executor executor, Texts texts,
                                      SqlCall call) {
        afterDefer(event.deferReply(true), executor, texts, what(event), call);
    }

    /** All messages are only visible to the user. */
    public static void replyAllEphemeral(SlashCommandInteractionEvent event, Executor executor, Texts texts,
                                         SqlListCall call) {
        afterDeferAll(event.deferReply(true), executor, texts, what(event), call);
    }

    public static void deferEphemeral(SlashCommandInteractionEvent event, Executor executor, Texts texts,
                                      SqlWork work) {
        afterDefer(event.deferReply(true), executor, texts, what(event), work);
    }

    private static String what(SlashCommandInteractionEvent event) {
        return "/" + event.getFullCommandName() + " by " + MessageSender.who(event);
    }

    static void afterDefer(RestAction<InteractionHook> defer, Executor executor, Texts texts, String what,
                           SqlCall call) {
        afterDefer(defer, executor, texts, what, hook -> hook.editOriginal(call.call()).queue());
    }

    static void afterDeferAll(RestAction<InteractionHook> defer, Executor executor, Texts texts, String what,
                              SqlListCall call) {
        afterDefer(defer, executor, texts, what, hook -> MessageSender.replyAll(hook, call.call(), true)
                .queue(null, error -> log.warn("Could not send the reply to {}", what, error)));
    }

    /**
     * Runs {@code work} only once Discord accepted the deferred reply. If the interaction already timed out, the user
     * sees "did not respond" and may retry, so a write (e.g. /points add) must not happen in the background.
     */
    static void afterDefer(RestAction<InteractionHook> defer, Executor executor, Texts texts, String what,
                           SqlWork work) {
        defer.queue(hook -> {
            try {
                executor.execute(() -> run(hook, texts, what, work));
            } catch (RejectedExecutionException e) {
                log.warn("Request queue full, rejected {}", what);
                hook.editOriginal(texts.busy()).queue();
            }
        }, failure -> log.warn("Could not acknowledge {}, nothing was changed", what, failure));
    }

    private static void run(InteractionHook hook, Texts texts, String what, SqlWork work) {
        MDC.put(ACTOR, " [" + what + "]");
        try {
            work.run(hook);
        } catch (SQLException | RuntimeException e) {
            // The actor in the log context says which command failed
            log.warn("Database work failed", e);
            hook.editOriginal(texts.unavailable()).queue();
        } finally {
            MDC.remove(ACTOR);
        }
    }
}
