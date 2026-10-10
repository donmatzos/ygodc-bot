package at.magi.ygodiscordbot.impl.tournament;

import java.util.List;

/** Posts into a tournament channel and DMs players. Never throws: a failed send is logged and the tournament goes on. */
public interface TournamentAnnouncer {

    /** @param ping whether the user mentions notify the players (tournament start, continue and the winner only) */
    void post(long channelId, NamedText text, boolean ping);

    /** A direct message to one player; closed DMs are only logged. */
    void dm(long userId, List<String> messages);
}
