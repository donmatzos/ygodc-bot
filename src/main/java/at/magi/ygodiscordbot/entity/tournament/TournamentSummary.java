package at.magi.ygodiscordbot.entity.tournament;

import java.time.LocalDate;

/** One row of {@code /tournament list}; {@code winner} is null unless FINISHED. */
public record TournamentSummary(String code, LocalDate playedOn, TournamentStatus status, Long winner) {
}
