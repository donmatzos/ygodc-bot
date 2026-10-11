package at.magi.ygodiscordbot.testutil;

import gnu.trove.map.hash.TLongObjectHashMap;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.concrete.PrivateChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.SlashCommandInteraction;
import net.dv8tion.jda.api.requests.ErrorResponse;
import net.dv8tion.jda.api.requests.Response;
import net.dv8tion.jda.api.requests.RestAction;
import net.dv8tion.jda.api.utils.Result;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Fake JDA objects (java.lang.reflect.Proxy) for tests of slash command code. Actions run their callbacks right away,
 * and what is sent is recorded in the order the calls are made, e.g. "reply (ephemeral)", "dm:first", "edit:done", "post:top 20
 * mentions=[]". A fake answers the methods named in its answers; any other method returns the fake itself if that
 * fits the return type (builder methods), else null, false or 0.
 */
public final class FakeInteractions {

    /** Answers one call of a fake; {@code args} is never null. */
    @FunctionalInterface
    public interface Answer {
        Object answer(Method method, Object[] args) throws Throwable;
    }

    private final List<String> sent = new ArrayList<>();
    private final InteractionHook hook = fake(InteractionHook.class, Map.of(
            "editOriginal", sends("edit"), "sendMessage", sends("follow-up")));

    public synchronized List<String> sent() {
        return List.copyOf(sent);
    }

    /** Records editOriginal as "edit:text" and sendMessage as "follow-up:text". */
    public InteractionHook hook() {
        return hook;
    }

    /** Answers a call that sends its first argument by recording "label:text"; see {@link #sending}. */
    public Answer sends(String label) {
        return (method, args) -> sending(method.getReturnType(), label + ":" + args[0], null);
    }

    /**
     * An action recorded as {@code record}, succeeding with {@code value}. setContent, setEphemeral(true) and
     * setAllowedMentions add to its record (":text", " (ephemeral)", " mentions=[…]").
     */
    public <T> T sending(Class<T> type, String record, Object value) {
        int entry;
        synchronized (this) {
            entry = sent.size();
            sent.add(record);
        }
        return action(type, value, null, note -> {
            synchronized (this) {
                sent.set(entry, sent.get(entry) + note);
            }
        });
    }

    /**
     * A slash command event; its interaction answers {@code answers} and by default: no options, the hook, a
     * deferReply recorded as "reply" that yields the hook, user {@link #user} 1 "yugi", not in a server.
     * Its JDA finds any user by ID, named "Player " + ID.
     */
    public SlashCommandInteractionEvent event(Map<String, Answer> answers) {
        Map<String, Answer> all = new HashMap<>(Map.of(
                "getOptions", returns(List.of()),
                "getHook", returns(hook),
                "deferReply", (m, a) -> sending(m.getReturnType(), "reply", hook),
                "getUser", (m, a) -> user(1, "yugi")));
        all.putAll(answers);
        JDA jda = fake(JDA.class, Map.of("retrieveUserById", (m, a) -> succeeding(m.getReturnType(),
                user(Long.parseLong(a[0].toString()), "Player " + a[0]))));
        return new SlashCommandInteractionEvent(jda, 0, fake(SlashCommandInteraction.class, all));
    }

    public static User user(long id, String name) {
        return fake(User.class, Map.of("getIdLong", returns(id), "getId", returns(Long.toString(id)),
                "getName", returns(name), "getEffectiveName", returns(name)));
    }

    /** User 1 "yugi" whose DM channel (ID 42) records sends as "dm:text", or who does not accept DMs. */
    public User dmUser(boolean acceptsDms) {
        PrivateChannel dm = fake(PrivateChannel.class, Map.of("getId", returns("42"), "sendMessage", sends("dm")));
        return fake(User.class, Map.of("getId", returns("1"), "getName", returns("yugi"),
                "openPrivateChannel", (m, a) -> acceptsDms ? succeeding(m.getReturnType(), dm)
                        : failing(m.getReturnType(), dmClosed())));
    }

    /** A server, for "getGuild" (an event without one is in the bot DM). */
    public static Guild guild() {
        return fake(Guild.class, Map.of("getIdLong", returns(7L), "getId", returns("7")));
    }

    public static Answer returns(Object value) {
        return (method, args) -> value;
    }

    /** A string option as Discord sends it in a server; {@code resolved} maps mentioned IDs to their User or Member. */
    public static OptionMapping stringOption(String name, String value, Map<Long, Object> resolved) {
        DataObject data = DataObject.empty().put("name", name).put("type", 3).put("value", value);
        TLongObjectHashMap<Object> byId = new TLongObjectHashMap<>();
        resolved.forEach(byId::put);
        return new OptionMapping(data, byId, null, guild());
    }

    /** The error Discord answers when the user does not accept DMs from the bot. */
    public static ErrorResponseException dmClosed() {
        return ErrorResponseException.create(ErrorResponse.CANNOT_SEND_TO_USER,
                new Response(new IllegalStateException("closed"), Set.of()));
    }

    public static <T> T succeeding(Class<T> type, Object value) {
        return action(type, value, null, note -> {
        });
    }

    public static <T> T failing(Class<T> type, Throwable error) {
        return action(type, null, error, note -> {
        });
    }

    /** A fake implementing {@code type} and {@code more}. */
    @SuppressWarnings("unchecked")
    public static <T> T fake(Class<?> type, Map<String, Answer> answers, Class<?>... more) {
        Class<?>[] types = new Class<?>[more.length + 1];
        types[0] = type;
        System.arraycopy(more, 0, types, 1, more.length);
        return (T) Proxy.newProxyInstance(FakeInteractions.class.getClassLoader(), types,
                (proxy, method, args) -> {
                    Answer answer = answers.get(method.getName());
                    return answer != null
                            ? answer.answer(method, args == null ? new Object[0] : args)
                            : fallback(proxy, method, args);
                });
    }

    /** A RestAction (or any subtype) that ends in {@code value}, or in {@code error} if that is not null. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <T> T action(Class<T> type, Object value, Throwable error, Consumer<String> note) {
        Map<String, Answer> answers = new HashMap<>();
        Object[] self = new Object[1]; // the action itself, filled in below: builder-style calls return it
        answers.put("queue", (m, a) -> {
            if (error == null && a.length > 0 && a[0] != null) {
                ((Consumer) a[0]).accept(value);
            } else if (error != null && a.length > 1 && a[1] != null) {
                ((Consumer) a[1]).accept(error);
            }
            return null;
        });
        // flatMap(condition, fn) has the function last; the condition is ignored
        answers.put("flatMap", (m, a) -> error == null ? ((Function) a[a.length - 1]).apply(value) : self[0]);
        answers.put("map", (m, a) -> error == null ? succeeding(RestAction.class, ((Function) a[0]).apply(value))
                : self[0]);
        answers.put("mapToResult", (m, a) -> succeeding(RestAction.class,
                error == null ? Result.success(value) : Result.failure(error)));
        answers.put("onErrorMap", (m, a) -> error == null ? self[0]
                : succeeding(RestAction.class, ((Function) a[a.length - 1]).apply(error)));
        // and(other, accumulator): other is queued for its value; a failing other is not modelled
        answers.put("and", (m, a) -> error == null
                ? succeeding(RestAction.class, ((BiFunction) a[1]).apply(value, resultOf((RestAction<?>) a[0])))
                : self[0]);
        answers.put("setContent", (m, a) -> noted(self[0], note, ":" + a[0]));
        answers.put("setEphemeral", (m, a) -> noted(self[0], note, Boolean.TRUE.equals(a[0]) ? " (ephemeral)" : ""));
        answers.put("setAllowedMentions", (m, a) -> noted(self[0], note, " mentions=" + a[0]));
        self[0] = fake(type, answers);
        return (T) self[0];
    }

    private static Object noted(Object action, Consumer<String> note, String text) {
        note.accept(text);
        return action;
    }

    private static Object resultOf(RestAction<?> action) {
        Object[] result = new Object[1];
        action.queue(value -> result[0] = value);
        return result[0];
    }

    private static Object fallback(Object proxy, Method method, Object[] args) {
        Class<?> returns = method.getReturnType();
        return switch (method.getName()) {
            case "equals" -> proxy == args[0];
            case "hashCode" -> System.identityHashCode(proxy);
            case "toString" -> "fake " + proxy.getClass().getInterfaces()[0].getSimpleName();
            default -> returns.isInstance(proxy) ? proxy
                    : returns == void.class || !returns.isPrimitive() ? null
                    // the default of a primitive type (false, 0): a one-element array holds it
                    : Array.get(Array.newInstance(returns, 1), 0);
        };
    }
}
