package at.magi.ygodiscordbot.impl.tournament;

import at.magi.ygodiscordbot.entity.tournament.MatchRecord;
import at.magi.ygodiscordbot.entity.tournament.TournamentRecord;
import at.magi.ygodiscordbot.entity.tournament.TournamentStatus;
import org.testng.annotations.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

public class MatchRegistryTest {

    private static ActiveTournament tournament(long id) {
        return new ActiveTournament(new TournamentRecord(id, "code", LocalDate.of(2026, 10, 10), 1, 2, 3,
                TournamentStatus.RUNNING, null, Instant.EPOCH, null, 1, List.of(1L, 2L, 3L, 4L), Map.of(),
                List.of(new MatchRecord(1, 1, 2L, null),
                        new MatchRecord(1, 3, 4L, null))));
    }

    @Test
    public void idsAreInRangeAndUnique() {
        MatchRegistry registry = new MatchRegistry(new Random(1), 100, 109);
        Set<Integer> seen = new HashSet<>();
        for (int i = 0; i < 10; i++) {
            int id = registry.create(1, 1, 2 * i, 2 * i + 1).id();
            assertTrue(id >= 100 && id <= 109, "out of range " + id);
            assertTrue(seen.add(id), "reissued " + id);
        }
    }

    @Test
    public void removedMatchIdIsNeverIssuedAgain() {
        MatchRegistry registry = new MatchRegistry(new Random(1), 100, 104);
        Set<Integer> seen = new HashSet<>();
        for (int i = 0; i < 5; i++) {
            ActiveMatch match = registry.create(i, 1, 1, 2);
            assertTrue(seen.add(match.id()), "reissued " + match.id());
            registry.removeAll(i);
            assertNull(registry.get(match.id()));
        }
        assertEquals(seen.size(), 5);
    }

    @Test
    public void exhaustedIdsFailClearly() {
        MatchRegistry registry = new MatchRegistry(new Random(1), 100, 101);
        registry.create(1, 1, 1, 2);
        registry.create(1, 1, 3, 4);
        IllegalStateException e = expectThrows(IllegalStateException.class, () -> registry.create(1, 1, 5, 6));
        assertTrue(e.getMessage().contains("match IDs"), e.getMessage());
    }

    @Test
    public void getFindsTheMatchByItsId() {
        MatchRegistry registry = new MatchRegistry(new Random(1), 100, 199);
        ActiveMatch match = registry.create(7, 2, 1, 2);
        assertEquals(registry.get(match.id()), match);
        assertNull(registry.get(match.id() == 100 ? 101 : 100));
    }

    @Test
    public void openListsUnplayedMatchesOfOneTournamentById() {
        MatchRegistry registry = new MatchRegistry(new Random(1), 100, 199);
        ActiveTournament tournament = tournament(5);
        ActiveMatch first = registry.create(5, 1, 1, 2L);
        ActiveMatch second = registry.create(5, 1, 3, 4L);
        registry.create(6, 1, 9, 10); // other tournament
        List<ActiveMatch> expected = first.id() < second.id() ? List.of(first, second) : List.of(second, first);
        assertEquals(registry.open(tournament), expected);

        tournament.setWinner(1, 1, 1);
        assertEquals(registry.open(tournament), List.of(second));
    }
}
