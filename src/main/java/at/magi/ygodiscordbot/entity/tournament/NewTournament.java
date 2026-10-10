package at.magi.ygodiscordbot.entity.tournament;

import java.time.Instant;
import java.util.List;

/** What {@code /tournament start} stores; {@code players} in the order they were mentioned. */
public record NewTournament(long guildId, long channelId, long createdBy, Instant startedAt, List<Long> players) {
}
