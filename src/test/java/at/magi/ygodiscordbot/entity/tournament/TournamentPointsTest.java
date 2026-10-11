package at.magi.ygodiscordbot.entity.tournament;

import org.testng.annotations.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static at.magi.ygodiscordbot.entity.tournament.TournamentFixtures.A;
import static at.magi.ygodiscordbot.entity.tournament.TournamentFixtures.B;
import static at.magi.ygodiscordbot.entity.tournament.TournamentFixtures.C;
import static at.magi.ygodiscordbot.entity.tournament.TournamentFixtures.D;
import static at.magi.ygodiscordbot.entity.tournament.TournamentFixtures.E;
import static at.magi.ygodiscordbot.entity.tournament.TournamentFixtures.bye;
import static at.magi.ygodiscordbot.entity.tournament.TournamentFixtures.won;
import static org.testng.Assert.assertEquals;

public class TournamentPointsTest {

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
                won(1, A, B), won(1, D, C), bye(1, E)));
        Map<Long, Long> points = TournamentPoints.award(standings, 1, A);
        assertEquals(points, Map.of(A, 2L, D, 1L));
    }
}
