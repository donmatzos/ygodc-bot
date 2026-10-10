package at.magi.ygodiscordbot.impl.tournament;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Messages that show display names (code blocks can't render mentions): sent once the names of {@code users} are
 * looked up. {@code render} runs on a JDA thread, so it may only read immutable snapshots.
 */
public record NamedText(Set<Long> users, Function<Map<Long, String>, List<String>> render) {

    public NamedText {
        users = Set.copyOf(users);
    }

    static NamedText plain(List<String> messages) {
        List<String> copy = List.copyOf(messages);
        return new NamedText(Set.of(), names -> copy);
    }

    static NamedText plain(String message) {
        return plain(List.of(message));
    }

    /** This text's messages, then {@code next}'s, sent as one post so they can't arrive out of order. */
    NamedText then(NamedText next) {
        Set<Long> both = new HashSet<>(users);
        both.addAll(next.users);
        return new NamedText(both, names -> {
            List<String> messages = new ArrayList<>(render.apply(names));
            messages.addAll(next.render.apply(names));
            return messages;
        });
    }
}
