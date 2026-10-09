package at.magi.ygodiscordbot.entity.tournament;

import java.util.List;
import java.util.Optional;

/**
 * Checked after every closed round: the tournament is won once at least {@link #minimumRounds} rounds are played and
 * exactly one active (not dropped) player has the fewest losses. Without double losses and drops that is the only
 * undefeated player, which can't happen before ⌈log₂ n⌉ rounds anyway; the minimum stops a double loss or a drop from
 * ending a tournament early. A last remaining player always wins.
 */
public final class WinnerRule {

    private WinnerRule() {
    }

    public static Optional<Long> winner(Standings standings, int roundsPlayed) {
        List<Long> active = standings.active();
        if (active.size() == 1) {
            return Optional.of(active.get(0));
        }
        if (active.isEmpty() || roundsPlayed < minimumRounds(standings.playerCount())) {
            return Optional.empty();
        }
        int fewest = active.stream().mapToInt(player -> standings.entry(player).losses()).min().getAsInt();
        List<Long> leaders = active.stream().filter(player -> standings.entry(player).losses() == fewest).toList();
        return leaders.size() == 1 ? Optional.of(leaders.get(0)) : Optional.empty();
    }

    /** ⌈log₂ players⌉: the rounds a field of this size needs until one player is left undefeated. */
    public static int minimumRounds(int players) {
        return players <= 1 ? 0 : 32 - Integer.numberOfLeadingZeros(players - 1);
    }
}
