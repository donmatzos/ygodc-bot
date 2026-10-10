package at.magi.ygodiscordbot.impl.tournament;

import java.util.ArrayList;
import java.util.List;

/** Keeps every post instead of sending it. */
final class RecordingAnnouncer implements TournamentAnnouncer {

    record Post(long channelId, String text, boolean ping) {
    }

    final List<Post> posts = new ArrayList<>();

    @Override
    public void post(long channelId, List<String> messages, boolean ping) {
        posts.add(new Post(channelId, String.join("\n", messages), ping));
    }

    String last() {
        return posts.get(posts.size() - 1).text();
    }
}
