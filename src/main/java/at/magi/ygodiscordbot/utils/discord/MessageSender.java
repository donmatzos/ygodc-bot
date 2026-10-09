package at.magi.ygodiscordbot.utils.discord;

import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.requests.ErrorResponse;
import net.dv8tion.jda.api.requests.RestAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Sends bot output that was split into several messages (see {@code DcMessageUtils}) in order: into a channel,
 * as an interaction reply with follow-ups, or into the user's DMs.
 */
public final class MessageSender {

    private static final Logger log = LoggerFactory.getLogger(MessageSender.class);

    private MessageSender() {
    }

    public static RestAction<?> sendAll(MessageChannel channel, List<String> messages) {
        RestAction<?> chain = channel.sendMessage(messages.get(0));
        for (String message : messages.subList(1, messages.size())) {
            chain = chain.flatMap(previous -> channel.sendMessage(message));
        }
        return chain;
    }

    /** Replaces the deferred reply with the first message; the rest follow in order. */
    public static RestAction<?> replyAll(InteractionHook hook, List<String> messages, boolean ephemeral) {
        return followUps(hook.editOriginal(messages.get(0)), hook, messages, ephemeral);
    }

    /**
     * Sends the remaining messages as follow-ups after {@code first}, which sends {@code messages.get(0)} (e.g. a
     * direct, not deferred reply, which saves a request).
     */
    public static RestAction<?> followUps(RestAction<?> first, InteractionHook hook, List<String> messages, boolean ephemeral) {
        RestAction<?> chain = first;
        for (String message : messages.subList(1, messages.size())) {
            chain = chain.flatMap(previous -> hook.sendMessage(message).setEphemeral(ephemeral));
        }
        return chain;
    }

    /**
     * Sends {@code messages} to the user's DMs, so server channels stay clean, and replaces the (already deferred,
     * ephemeral) reply with a link to the DM, or with the reason it failed.
     *
     * @param what      e.g. "the TCG list", used in the reply and in logs
     * @param retryHint command to use inside the bot DM instead, e.g. "`/banlist`"
     */
    public static void sendToDirectMessages(SlashCommandInteractionEvent event, List<String> messages, String what,
                                            String retryHint, Runnable onSent) {
        event.getUser().openPrivateChannel()
                .flatMap(channel -> sendAll(channel, messages).map(last -> channel))
                .queue(channel -> {
                            onSent.run();
                            event.getHook()
                                    .editOriginal("📬 Sent " + what + " to your DMs: "
                                            + "https://discord.com/channels/@me/" + channel.getId())
                                    .queue();
                        },
                        error -> event.getHook().editOriginal(dmFailure(event, what, error, retryHint)).queue());
    }

    private static String dmFailure(SlashCommandInteractionEvent event, String what, Throwable error, String retryHint) {
        if (isDmClosed(error)) {
            log.info("Could not send {} to {}: they do not accept DMs", what, who(event));
        } else {
            log.warn("Could not send {} to {} via DM", what, who(event), error);
        }
        return dmFailureMessage(error, what, retryHint);
    }

    static String dmFailureMessage(Throwable error, String what, String retryHint) {
        if (isDmClosed(error)) {
            return "I can't send you direct messages. Allow DMs from this server's members "
                    + "(server name → Privacy Settings), or open a DM with me and use " + retryHint + " there.";
        }
        return "Something went wrong while sending " + what + ". Please try again later.";
    }

    private static boolean isDmClosed(Throwable error) {
        return error instanceof ErrorResponseException e && e.getErrorResponse() == ErrorResponse.CANNOT_SEND_TO_USER;
    }

    /** User name and ID, so log lines can be matched to a Discord account. */
    public static String who(SlashCommandInteractionEvent event) {
        return event.getUser().getName() + " (" + event.getUser().getId() + ")";
    }
}
