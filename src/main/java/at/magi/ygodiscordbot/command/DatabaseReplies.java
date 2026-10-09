package at.magi.ygodiscordbot.command;

import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.requests.RestAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/** Runs one database call off the JDA event thread and shows its result as the caller's ephemeral reply. */
final class DatabaseReplies {

    private static final Logger log = LoggerFactory.getLogger(DatabaseReplies.class);

    @FunctionalInterface
    interface SqlCall {
        String call() throws SQLException;
    }

    private DatabaseReplies() {
    }

    static void replyEphemeral(SlashCommandInteractionEvent event, Executor executor, SqlCall call) {
        afterDefer(event.deferReply(true), executor,
                "/" + event.getFullCommandName() + " by " + event.getUser().getId(), call);
    }

    /**
     * Runs {@code call} only once Discord accepted the deferred reply. If the interaction already timed out, the user
     * sees "did not respond" and may retry, so a write (e.g. /points add) must not happen in the background.
     */
    static void afterDefer(RestAction<InteractionHook> defer, Executor executor, String what, SqlCall call) {
        defer.queue(hook -> {
            try {
                executor.execute(() -> hook.editOriginal(result(what, call)).queue());
            } catch (RejectedExecutionException e) {
                log.warn("Request queue full, rejected {}", what);
                hook.editOriginal(LeaderboardCommand.BUSY).queue();
            }
        }, failure -> log.warn("Could not acknowledge {}, nothing was changed", what, failure));
    }

    private static String result(String what, SqlCall call) {
        try {
            return call.call();
        } catch (SQLException | RuntimeException e) {
            log.warn("{} failed", what, e);
            return LeaderboardCommand.UNAVAILABLE;
        }
    }
}
