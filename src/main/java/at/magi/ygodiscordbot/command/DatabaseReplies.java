package at.magi.ygodiscordbot.command;

import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
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
        event.deferReply(true).queue();
        try {
            executor.execute(() -> {
                String reply;
                try {
                    reply = call.call();
                } catch (SQLException | RuntimeException e) {
                    log.warn("/{} failed for {}", event.getFullCommandName(), event.getUser().getId(), e);
                    reply = LeaderboardCommand.UNAVAILABLE;
                }
                event.getHook().editOriginal(reply).queue();
            });
        } catch (RejectedExecutionException e) {
            log.warn("Request queue full, rejected /{} by {}", event.getFullCommandName(), event.getUser().getId());
            event.getHook().editOriginal(LeaderboardCommand.BUSY).queue();
        }
    }
}
