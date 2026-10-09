package at.magi.ygodiscordbot.entity.tournament;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * A stored tournament with everything needed to rebuild it after a restart.
 *
 * @param currentRound   last round started with start/continue; rows of {@code currentRound + 1} are posted but
 *                       not started yet
 * @param droppedInRound player → round in which they dropped
 */
public record TournamentRecord(long id, long guildId, long channelId, long createdBy, TournamentStatus status,
                               Long winner, Instant startedAt, Instant finishedAt, int currentRound,
                               List<Long> players, Map<Long, Integer> droppedInRound, List<MatchRecord> matches) {
}
