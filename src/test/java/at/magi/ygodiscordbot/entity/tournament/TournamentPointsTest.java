package at.magi.ygodiscordbot.entity.tournament;

import org.testng.annotations.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.testng.Assert.assertEquals;

public class TournamentPointsTest {

    private static final long A = 1, B = 2, C = 3, D = 4, E = 5;

    private static MatchRecord won(int round, long winner, long loser) {
        return new MatchRecord(round, winner, loser, winner);
    }

    @Test
    public void onePointPerRealWinPlusRoundsForTheWinner() {
        Standings standings = Standings.of(List.of(A, B, C, D), Set.of(), List.of(
                won(1, A, B), won(1, C, D), won(2, A, C), won(2, B, D)));
        // A: 2 wins + 2 rounds; B and C: 1 win; D: nothing, so not listed
        assertEquals(TournamentPoints.award(standings, 2, A), Map.of(A, 4L, B, 1L, C, 1L));
    }

    @Test
    public void byesEarnNothingButDroppedPlayersKeepTheirWins() {
        Standings standings = Standings.of(List.of(A, B, C, D, E), Set.of(D), List.of(
                won(1, A, B), won(1, D, C), MatchRecord.of(1, Pairing.bye(E))));
        Map<Long, Long> points = TournamentPoints.award(standings, 1, A);
        assertEquals(points, Map.of(A, 2L, D, 1L));
    }
}
