package at.magi.ygodiscordbot.impl.tournament;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Keeps every post and DM instead of sending it. */
final class RecordingAnnouncer implements TournamentAnnouncer {

    record Post(long channelId, String text, boolean ping) {
    }

    record Dm(long userId, String text) {
    }

    final List<Post> posts = new ArrayList<>();
    final List<Dm> dms = new ArrayList<>();
    /** When true, every DM fails like a user with closed DMs (the real announcer only logs that). */
    boolean dmsClosed;

    /** Names as the tables show them: 101 → "P101". */
    static Map<Long, String> names(Set<Long> ids) {
        Map<Long, String> names = new HashMap<>();
        ids.forEach(id -> names.put(id, "P" + id));
        return names;
    }

    @Override
    public void post(long channelId, NamedText text, boolean ping) {
        posts.add(new Post(channelId, String.join("\n", text.render().apply(names(text.users()))), ping));
    }

    @Override
    public void dm(long userId, List<String> messages) {
        if (!dmsClosed) {
            dms.add(new Dm(userId, String.join("\n", messages)));
        }
    }

    String last() {
        return posts.get(posts.size() - 1).text();
    }

    List<String> dmsTo(long userId) {
        return dms.stream().filter(dm -> dm.userId() == userId).map(Dm::text).toList();
    }
}
