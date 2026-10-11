package at.magi.ygodiscordbot.entity.tournament;

import java.util.List;

/**
 * A paired round.
 *
 * @param pairings the pairings; a bye, if any, comes last
 * @param playOff  the tied leaders if this is a {@link WinnerRule#playOff} round, else empty
 */
public record Round(List<Pairing> pairings, List<Long> playOff) {
}
