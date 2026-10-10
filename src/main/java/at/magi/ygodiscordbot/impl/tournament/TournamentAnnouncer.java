package at.magi.ygodiscordbot.impl.tournament;

import java.util.List;

/** Posts into a tournament channel. Never throws: a failed post is logged and the tournament goes on. */
public interface TournamentAnnouncer {

    /** @param ping whether the user mentions notify the players (round starts and the winner only) */
    void post(long channelId, List<String> messages, boolean ping);
}
