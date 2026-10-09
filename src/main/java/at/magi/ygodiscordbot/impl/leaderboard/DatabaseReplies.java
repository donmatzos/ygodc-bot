package at.magi.ygodiscordbot.impl.leaderboard;

import at.magi.ygodiscordbot.utils.discord.MessageSender;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.requests.RestAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.sql.SQLException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * Runs database work off the JDA event thread, only after Discord accepted the (ephemeral) deferred reply. While it
 * runs, the log context holds who triggered it, so every log line of the work (e.g. a points change) names them.
 */
final class DatabaseReplies {

    private static final Logger log = LoggerFactory.getLogger(DatabaseReplies.class);

    /** MDC key; the logback pattern appends it to every line, e.g. " [/points add by yugi (123)]". */
    static final String ACTOR = "actor";

    /** Work whose result is the reply text. */
    @FunctionalInterface
    interface SqlCall {
        String call() throws SQLException;
    }

    /** Work that replies itself through the hook (e.g. after posting into a channel). */
    @FunctionalInterface
    interface SqlWork {
        void run(InteractionHook hook) throws SQLException;
    }

    private DatabaseReplies() {
    }

    static void replyEphemeral(SlashCommandInteractionEvent event, Executor executor, SqlCall call) {
        afterDefer(event.deferReply(true), executor, what(event), call);
    }

    static void deferEphemeral(SlashCommandInteractionEvent event, Executor executor, SqlWork work) {
        afterDefer(event.deferReply(true), executor, what(event), work);
    }

    private static String what(SlashCommandInteractionEvent event) {
        return "/" + event.getFullCommandName() + " by " + MessageSender.who(event);
    }

    static void afterDefer(RestAction<InteractionHook> defer, Executor executor, String what, SqlCall call) {
        afterDefer(defer, executor, what, hook -> hook.editOriginal(call.call()).queue());
    }

    /**
     * Runs {@code work} only once Discord accepted the deferred reply. If the interaction already timed out, the user
     * sees "did not respond" and may retry, so a write (e.g. /points add) must not happen in the background.
     */
    static void afterDefer(RestAction<InteractionHook> defer, Executor executor, String what, SqlWork work) {
        defer.queue(hook -> {
            try {
                executor.execute(() -> run(hook, what, work));
            } catch (RejectedExecutionException e) {
                log.warn("Request queue full, rejected {}", what);
                hook.editOriginal(LeaderboardCommand.BUSY).queue();
            }
        }, failure -> log.warn("Could not acknowledge {}, nothing was changed", what, failure));
    }

    private static void run(InteractionHook hook, String what, SqlWork work) {
        MDC.put(ACTOR, " [" + what + "]");
        try {
            work.run(hook);
        } catch (SQLException | RuntimeException e) {
            // The actor in the log context says which command failed
            log.warn("Database work failed", e);
            hook.editOriginal(LeaderboardCommand.UNAVAILABLE).queue();
        } finally {
            MDC.remove(ACTOR);
        }
    }
}
