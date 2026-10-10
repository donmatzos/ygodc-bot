package at.magi.ygodiscordbot.impl.tournament;

import at.magi.ygodiscordbot.entity.tournament.MatchRecord;
import at.magi.ygodiscordbot.entity.tournament.TournamentStatus;
import org.testng.annotations.Test;

import java.sql.SQLException;
import java.time.Duration;
import java.util.List;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

public class TournamentServiceLifecycleTest extends TournamentServiceTestBase {

    @Test
    public void dropInAnOpenMatchGivesTheOpponentTheWin() throws SQLException {
        long id = start(FOUR);
        ActiveMatch match = service.openMatches(id).get(0);
        String reply = service.drop(id, GUILD, match.player1());
        assertTrue(reply.contains("dropped out"), reply);
        assertTrue(reply.contains("<@" + match.player2() + "> wins"), reply);
        assertTrue(stored(id).matches().contains(
                new MatchRecord(1, match.player1(), match.player2(), match.player2())));
        assertEquals(stored(id).droppedInRound().get(match.player1()), Integer.valueOf(1));
        assertTrue(service.drop(id, GUILD, match.player1()).contains("already dropped"));
        assertTrue(service.drop(id, GUILD, 999).contains("not playing in tournament"));
    }

    @Test
    public void dropAndForfeitAreSavedTogether() throws SQLException {
        long id = start(FOUR);
        ActiveMatch match = service.openMatches(id).get(0);
        store.writesUntilFailure = 1; // the database goes down after one more write
        try {
            service.drop(id, GUILD, match.player1());
        } catch (SQLException e) {
            // The organizer sees "not available" and can retry
        }
        store.writesUntilFailure = -1;
        boolean dropped = stored(id).droppedInRound().containsKey(match.player1());
        boolean forfeited = stored(id).matches().contains(
                new MatchRecord(1, match.player1(), match.player2(), match.player2()));
        assertEquals(forfeited, dropped, "drop and forfeit must be saved together");
        assertEquals(service.openMatches(id).size(), dropped ? 1 : 2);
    }

    @Test
    public void dropRepairsPendingRound() throws SQLException {
        long id = start(FOUR);
        List<ActiveMatch> round1 = service.openMatches(id);
        playRound(id); // player1 wins every match
        long loser = round1.get(0).player2();
        String reply = service.drop(id, GUILD, loser);
        assertTrue(reply.contains("made again"), reply);
        List<MatchRecord> round2 = stored(id).matches().stream().filter(m -> m.round() == 2).toList();
        assertEquals(round2.size(), 2); // one match + one bye among the 3 remaining players
        assertTrue(round2.stream().noneMatch(m -> m.involves(loser)));
        assertTrue(announcer.last().contains("Round 2 pairings"), announcer.last());
    }

    @Test
    public void earlyLeaderAfterDropPlaysOn() throws SQLException {
        long id = start(FOUR);
        List<ActiveMatch> round1 = service.openMatches(id);
        playRound(id);
        // One of the two 1-0 players drops: the other is the only player without a loss, but 4 players play at
        // least 2 rounds, so round 2 is paired again among the 3 remaining players
        service.drop(id, GUILD, round1.get(0).player1());
        assertEquals(stored(id).status(), TournamentStatus.RUNNING);
        assertTrue(announcer.last().contains("Round 2 pairings"), announcer.last());
    }

    @Test
    public void lastPlayerLeftWins() throws SQLException {
        long id = start(List.of(101L, 102L));
        service.drop(id, GUILD, 101L);
        assertEquals(stored(id).status(), TournamentStatus.FINISHED);
        assertEquals(stored(id).winner(), Long.valueOf(102));
    }

    @Test
    public void cancelAbandonsWithoutPoints() throws SQLException {
        long id = start(FOUR);
        assertTrue(service.cancel(id, GUILD).contains("cancelled"));
        assertEquals(stored(id).status(), TournamentStatus.ABANDONED);
        assertTrue(service.openMatches(id).isEmpty());
        assertTrue(awarded.isEmpty());
        assertTrue(announcer.last().contains("abandoned"), announcer.last());
        assertTrue(service.continueRound(id, GUILD).contains("already abandoned"));
        // Its players are free again
        assertTrue(service.start(GUILD, CHANNEL, ADMIN, FOUR).contains("started"));
    }

    @Test
    public void abandonedAfter48Hours() throws SQLException {
        long id = start(FOUR);
        clock.advance(Duration.ofHours(48).minusMinutes(1));
        service.abandonExpired();
        assertEquals(stored(id).status(), TournamentStatus.RUNNING);
        clock.advance(Duration.ofMinutes(1));
        service.abandonExpired();
        assertEquals(stored(id).status(), TournamentStatus.ABANDONED);
        assertTrue(announcer.last().contains("48 hours"), announcer.last());
    }

    @Test
    public void recoveryRecreatesOpenMatches() throws SQLException {
        long id = start(FOUR);
        ActiveMatch played = service.openMatches(id).get(0);
        service.finishMatch(played.id(), GUILD, played.player1(), played.player1(), false);

        service = newService(7); // bot restart
        service.recover();
        assertTrue(announcer.last().contains("The bot restarted"), announcer.last());
        List<ActiveMatch> open = service.openMatches(id);
        assertEquals(open.size(), 1);
        ActiveMatch fresh = open.get(0);
        assertTrue(announcer.last().contains("`" + fresh.id() + "`"), announcer.last());
        String reply = service.finishMatch(fresh.id(), GUILD, fresh.player2(), fresh.player2(), false);
        assertTrue(reply.contains("Round 1 is complete"), reply);
    }

    @Test
    public void recoveryFinishesARoundEndThatWasInterrupted() throws SQLException {
        long id = start(FOUR);
        // Results stored, but the bot died before pairing round 2
        for (MatchRecord match : stored(id).matches()) {
            store.recordWinner(id, 1, match.player1(), match.player1());
        }
        service = newService(7);
        service.recover();
        assertTrue(announcer.last().contains("Round 2 pairings"), announcer.last());
        assertEquals(stored(id).matches().stream().filter(m -> m.round() == 2).count(), 2);
    }

    @Test
    public void recoveryAbandonsExpiredTournaments() throws SQLException {
        long id = start(FOUR);
        clock.advance(Duration.ofHours(49));
        service = newService(7);
        service.recover();
        assertEquals(stored(id).status(), TournamentStatus.ABANDONED);
    }
}
