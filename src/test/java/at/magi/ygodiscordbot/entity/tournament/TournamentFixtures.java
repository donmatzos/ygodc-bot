package at.magi.ygodiscordbot.entity.tournament;

import java.util.List;
import java.util.Set;
import java.util.stream.LongStream;

/** Shared builders for tournament tests: player IDs, match records and the small worked-example tournament. */
public final class TournamentFixtures {

    public static final long A = 1, B = 2, C = 3, D = 4, E = 5;

    private TournamentFixtures() {
    }

    public static MatchRecord won(int round, long winner, long loser) {
        return new MatchRecord(round, winner, loser, winner);
    }

    public static MatchRecord doubleLoss(int round, long player1, long player2) {
        return new MatchRecord(round, player1, player2, null, true);
    }

    public static MatchRecord bye(int round, long player) {
        return MatchRecord.of(round, Pairing.bye(player));
    }

    /** {@code count} consecutive player IDs starting at {@code first}. */
    public static List<Long> players(long first, int count) {
        return LongStream.range(first, first + count).boxed().toList();
    }

    /** Round 1: A beat C, B beat D. Round 2: A and B double loss, C beat D. A, B, C are 1-1, D 0-2. */
    public static Standings workedExample() {
        return workedExample(Set.of());
    }

    public static Standings workedExample(Set<Long> dropped) {
        return Standings.of(List.of(A, B, C, D), dropped, List.of(
                won(1, A, C), won(1, B, D), doubleLoss(2, A, B), won(2, C, D)));
    }
}
