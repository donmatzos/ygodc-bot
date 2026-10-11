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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Fake JDA objects (java.lang.reflect.Proxy) for tests of slash command code. Actions run their callbacks right away
 * when queued. What is sent counts once its action, or an action chained from it (flatMap, map, and, ...), is queued,
 * and is listed in the order the actions were made, e.g. "reply (ephemeral)", "dm:first", "edit:done",
 * "post:top 20 mentions=[]". A fake answers the methods named in its answers; any other method returns the fake
 * itself if that fits the return type (builder methods), else null, false or 0.
 */
public final class FakeInteractions {

    /** Answers one call of a fake; {@code args} is never null. */
    @FunctionalInterface
    public interface Answer {
        Object answer(Method method, Object[] args) throws Throwable;
    }

    /** Implemented by every fake action, for chaining; the names do not clash with JDA's. */
    private interface Chained {
        /** Also runs {@code upstream} when this action is queued. */
        void fakeOnQueue(Runnable upstream);

        /** Marks what this action and its upstream actions send as sent, as queueing it does. */
        void fakeCommit();

        Object fakeValue();
    }

    /** Something sent; it counts once its action is queued. */
    private static final class Entry {
        private String text;
        private boolean queued;

        private Entry(String text) {
            this.text = text;
        }
    }

    private final List<Entry> entries = new ArrayList<>();
    private final InteractionHook hook = fake(InteractionHook.class, Map.of(
            "editOriginal", sends("edit"), "sendMessage", sends("follow-up")));

    public synchronized List<String> sent() {
        return entries.stream().filter(entry -> entry.queued).map(entry -> entry.text).toList();
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
     * An action recorded as {@code record} once queued, succeeding with {@code value}. setContent,
     * setEphemeral(true) and setAllowedMentions add to its record (":text", " (ephemeral)", " mentions=[…]").
     */
    private <T> T sending(Class<T> type, String record, Object value) {
        Entry entry = new Entry(record);
        synchronized (this) {
            entries.add(entry);
        }
        return action(type, value, null, note -> {
            synchronized (this) {
                entry.text += note;
            }
        }, () -> {
            synchronized (this) {
                entry.queued = true;
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
        }, () -> {
        });
    }

    public static <T> T failing(Class<T> type, Throwable error) {
        return action(type, null, error, note -> {
        }, () -> {
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

    /**
     * A RestAction (or any subtype) that ends in {@code value}, or in {@code error} if that is not null; queueing it
     * runs {@code commit}, and so does queueing an action chained from it.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <T> T action(Class<T> type, Object value, Throwable error, Consumer<String> note,
                                Runnable commit) {
        Map<String, Answer> answers = new HashMap<>();
        Object[] self = new Object[1]; // the action itself, filled in below: builder-style calls return it
        List<Runnable> onQueue = new CopyOnWriteArrayList<>(List.of(commit));
        Runnable commitAll = () -> onQueue.forEach(Runnable::run);
        answers.put("fakeOnQueue", (m, a) -> onQueue.add((Runnable) a[0]));
        answers.put("fakeCommit", (m, a) -> {
            commitAll.run();
            return null;
        });
        answers.put("fakeValue", (m, a) -> value);
        answers.put("queue", (m, a) -> {
            commitAll.run();
            if (error == null && a.length > 0 && a[0] != null) {
                ((Consumer) a[0]).accept(value);
            } else if (error != null && a.length > 1 && a[1] != null) {
                ((Consumer) a[1]).accept(error);
            }
            return null;
        });
        // flatMap(condition, fn) has the function last; the condition is ignored
        answers.put("flatMap", (m, a) -> error == null
                ? chained(((Function) a[a.length - 1]).apply(value), commitAll) : self[0]);
        answers.put("map", (m, a) -> error == null
                ? chained(succeeding(RestAction.class, ((Function) a[0]).apply(value)), commitAll) : self[0]);
        answers.put("mapToResult", (m, a) -> chained(succeeding(RestAction.class,
                error == null ? Result.success(value) : Result.failure(error)), commitAll));
        answers.put("onErrorMap", (m, a) -> error == null ? self[0]
                : chained(succeeding(RestAction.class, ((Function) a[a.length - 1]).apply(error)), commitAll));
        // and(other, accumulator): queueing the result queues other too; a failing other is not modelled
        answers.put("and", (m, a) -> {
            if (error != null) {
                return self[0];
            }
            Chained other = (Chained) a[0];
            Object both = succeeding(RestAction.class, ((BiFunction) a[1]).apply(value, other.fakeValue()));
            return chained(both, () -> {
                commitAll.run();
                other.fakeCommit();
            });
        });
        answers.put("setContent", (m, a) -> noted(self[0], note, ":" + a[0]));
        answers.put("setEphemeral", (m, a) -> noted(self[0], note, Boolean.TRUE.equals(a[0]) ? " (ephemeral)" : ""));
        answers.put("setAllowedMentions", (m, a) -> noted(self[0], note, " mentions=" + a[0]));
        self[0] = fake(type, answers, Chained.class);
        return (T) self[0];
    }

    /** {@code next}, which also runs {@code upstream} when queued. */
    private static Object chained(Object next, Runnable upstream) {
        ((Chained) next).fakeOnQueue(upstream);
        return next;
    }

    private static Object noted(Object action, Consumer<String> note, String text) {
        note.accept(text);
        return action;
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
