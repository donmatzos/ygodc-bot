package at.magi.ygodiscordbot.entity.tournament;

import java.util.List;
import java.util.Optional;

/**
 * Checked after every closed round: the tournament is won once exactly one active (not dropped) player has the fewest
 * losses. Without double losses and drops that is the only undefeated player. After a double loss or a drop it also
 * needs {@link #minimumRounds} rounds, so those can't end a tournament early (e.g. a round-1 double loss leaving one
 * undefeated player among four). Leaders still tied after {@link #minimumRounds} rounds are decided by the
 * tie-breakers of {@link Standings} while fewer than {@link #PLAY_OFF_ROUNDS} rounds are played; from then on they
 * play it out in a {@link #playOff} round, since a long tournament should end with a won match, not a calculation.
 * A last remaining player always wins.
 */
public final class WinnerRule {

    /** From this many played rounds on, tied leaders play another round instead of being decided by tie-breakers. */
    public static final int PLAY_OFF_ROUNDS = 4;

    private WinnerRule() {
    }

    public static Optional<Long> winner(Standings standings, int roundsPlayed) {
        List<Long> active = standings.active();
        if (active.size() == 1) {
            return Optional.of(active.get(0));
        }
        if (active.isEmpty()) {
            return Optional.empty();
        }
        List<Long> leaders = leaders(standings);
        if (roundsPlayed < minimumRounds(standings.playerCount())) {
            return leaders.size() == 1 && !standings.hasDoubleLossOrDrop()
                    ? Optional.of(leaders.get(0)) : Optional.empty();
        }
        if (leaders.size() == 1) {
            return Optional.of(leaders.get(0));
        }
        return roundsPlayed >= PLAY_OFF_ROUNDS ? Optional.empty() : Optional.of(leaders.get(0));
    }

    /**
     * The tied leaders, best first, who play the next round on their own (rematches allowed); empty when the next
     * round is a normal Swiss round. Only called when {@link #winner} found nobody.
     */
    public static List<Long> playOff(Standings standings, int roundsPlayed) {
        if (roundsPlayed < Math.max(PLAY_OFF_ROUNDS, minimumRounds(standings.playerCount()))) {
            return List.of();
        }
        List<Long> leaders = leaders(standings);
        return leaders.size() > 1 ? leaders : List.of();
    }

    /** Active players with the fewest losses, best first. */
    private static List<Long> leaders(Standings standings) {
        List<Long> ranked = standings.activeRanked();
        int fewest = standings.entry(ranked.get(0)).losses();
        return ranked.stream().filter(player -> standings.entry(player).losses() == fewest).toList();
    }

    /** ⌈log₂ players⌉: the rounds a field of this size needs until one player is left undefeated. */
    public static int minimumRounds(int players) {
        return players <= 1 ? 0 : 32 - Integer.numberOfLeadingZeros(players - 1);
    }
}
