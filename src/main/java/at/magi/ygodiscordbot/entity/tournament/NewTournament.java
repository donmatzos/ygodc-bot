package at.magi.ygodiscordbot.entity.tournament;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * What {@code /tournament start} stores; {@code players} in the order they were mentioned.
 *
 * @param code     public ID, see TournamentCode
 * @param playedOn the start day in TournamentCode.ZONE
 */
public record NewTournament(String code, LocalDate playedOn, long guildId, long channelId, long createdBy,
                            Instant startedAt, List<Long> players) {
}
