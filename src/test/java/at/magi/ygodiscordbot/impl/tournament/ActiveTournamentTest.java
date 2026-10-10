package at.magi.ygodiscordbot.impl.tournament;

import at.magi.ygodiscordbot.entity.tournament.MatchRecord;
import at.magi.ygodiscordbot.entity.tournament.Pairing;
import at.magi.ygodiscordbot.entity.tournament.TournamentRecord;
import at.magi.ygodiscordbot.entity.tournament.TournamentStatus;
import org.testng.annotations.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

public class ActiveTournamentTest {

    private static ActiveTournament threePlayers() {
        return new ActiveTournament(new TournamentRecord(7, "abcdefghj-26-10-10", LocalDate.of(2026, 10, 10), 1, 2, 3, TournamentStatus.RUNNING, null,
                Instant.EPOCH, null, 1, List.of(10L, 20L, 30L), Map.of(),
                List.of(new MatchRecord(1, 10, 20L, null), MatchRecord.of(1, Pairing.bye(30)))));
    }

    @Test
    public void roundClosesWhenEveryMatchHasAWinner() {
        ActiveTournament tournament = threePlayers();
        assertFalse(tournament.roundClosed());
        tournament.setWinner(1, 10, 20);
        assertTrue(tournament.roundClosed());
        assertEquals(tournament.record(1, 10).winner(), Long.valueOf(20));
        assertEquals(tournament.byes(1), List.of(30L));
    }

    @Test
    public void doubleLossClosesTheMatch() {
        ActiveTournament tournament = threePlayers();
        tournament.setDoubleLoss(1, 10);
        assertTrue(tournament.roundClosed());
        assertTrue(tournament.record(1, 10).doubleLoss());
        tournament.setWinner(1, 10, 10);
        assertFalse(tournament.record(1, 10).doubleLoss());
    }

    @Test
    public void pendingPairingsAreTheNextRound() {
        ActiveTournament tournament = threePlayers();
        tournament.setWinner(1, 10, 10);
        tournament.addPairings(2, List.of(new Pairing(10, 30L), Pairing.bye(20)));
        assertEquals(tournament.pending().size(), 2);
        tournament.removeRound(2);
        assertTrue(tournament.pending().isEmpty());
        tournament.addPairings(2, List.of(new Pairing(10, 30L), Pairing.bye(20)));
        tournament.startRound(2);
        assertEquals(tournament.currentRound(), 2);
        assertTrue(tournament.pending().isEmpty());
        assertFalse(tournament.roundClosed());
    }

    @Test
    public void droppedPlayersAreNoLongerActive() {
        ActiveTournament tournament = threePlayers();
        tournament.drop(20, 1);
        assertTrue(tournament.hasPlayer(20));
        assertTrue(tournament.isDropped(20));
        assertFalse(tournament.isActivePlayer(20));
        assertEquals(tournament.standings().active(), List.of(10L, 30L));
    }
}
