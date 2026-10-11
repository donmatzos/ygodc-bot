package at.magi.ygodiscordbot.utils.discord;

import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.requests.ErrorResponse;
import net.dv8tion.jda.api.requests.RestAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
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

    /** Like {@link #sendAll(MessageChannel, List)}, but only the given mention types notify anyone. */
    public static RestAction<?> sendAll(MessageChannel channel, List<String> messages,
                                        Collection<Message.MentionType> allowedMentions) {
        RestAction<?> chain = channel.sendMessage(messages.get(0)).setAllowedMentions(allowedMentions);
        for (String message : messages.subList(1, messages.size())) {
            chain = chain.flatMap(previous -> channel.sendMessage(message).setAllowedMentions(allowedMentions));
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
     * Delivers {@code messages} to the user. Used in a server, they go to the user's DMs, so server channels stay
     * clean, and the (already deferred, ephemeral) reply becomes a link to the DM, or the reason it failed. Used in
     * the bot DM, they are posted right there: as the replacement of the deferred reply, or as the direct reply if
     * the command was not deferred (saves a request).
     *
     * @param what      e.g. "the TCG list", used in the reply and in logs
     * @param retryHint command to use inside the bot DM instead, e.g. "`/banlist`"
     * @param onSent    runs once everything was sent
     */
    public static void deliver(SlashCommandInteractionEvent event, List<String> messages, String what,
                               String retryHint, Runnable onSent) {
        if (event.isFromGuild()) {
            sendToDirectMessages(event, messages, what, retryHint, onSent);
            return;
        }
        RestAction<?> sending = event.isAcknowledged()
                ? replyAll(event.getHook(), messages, false)
                : followUps(event.reply(messages.get(0)), event.getHook(), messages, false);
        sending.queue(last -> onSent.run(),
                error -> log.warn("Could not send {} to {} in their DM with the bot", what, who(event), error));
    }

    private static void sendToDirectMessages(SlashCommandInteractionEvent event, List<String> messages, String what,
                                             String retryHint, Runnable onSent) {
        event.getUser().openPrivateChannel()
                .flatMap(channel -> sendAll(channel, messages).map(last -> channel))
                .queue(channel -> {
                            onSent.run();
                            event.getHook()
                                    .editOriginal(sentReply(channel.getId(), what))
                                    .queue();
                        },
                        error -> event.getHook().editOriginal(dmFailure(event, what, error, retryHint)).queue());
    }

    static String sentReply(String channelId, String what) {
        return "📬 Sent " + what + " to your DMs: https://discord.com/channels/@me/" + channelId;
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

    /** Whether sending failed because the user does not accept DMs from the bot. */
    public static boolean isDmClosed(Throwable error) {
        return error instanceof ErrorResponseException e && e.getErrorResponse() == ErrorResponse.CANNOT_SEND_TO_USER;
    }

    /** User name and ID, so log lines can be matched to a Discord account. */
    public static String who(SlashCommandInteractionEvent event) {
        return event.getUser().getName() + " (" + event.getUser().getId() + ")";
    }
}
