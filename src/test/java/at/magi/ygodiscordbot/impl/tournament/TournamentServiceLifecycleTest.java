package at.magi.ygodiscordbot.impl.tournament;

import at.magi.ygodiscordbot.entity.tournament.MatchRecord;
import at.magi.ygodiscordbot.entity.tournament.TournamentStatus;
import org.testng.annotations.Test;

import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

public class TournamentServiceLifecycleTest extends TournamentServiceTestBase {

    @Test
    public void dropInAnOpenMatchGivesTheOpponentTheWin() throws SQLException {
        long id = start(FOUR);
        ActiveMatch match = service.openMatches(id).get(0);
        String reply = service.drop(code(id), GUILD, match.player1());
        assertTrue(reply.contains("dropped out"), reply);
        assertTrue(reply.contains("<@" + match.player2() + "> wins"), reply);
        assertTrue(stored(id).matches().contains(
                new MatchRecord(1, match.player1(), match.player2(), match.player2())));
        assertEquals(stored(id).droppedInRound().get(match.player1()), Integer.valueOf(1));
        assertTrue(service.drop(code(id), GUILD, match.player1()).contains("already dropped"));
        assertTrue(service.drop(code(id), GUILD, 999).contains("not playing in tournament"));
    }

    @Test
    public void dropAndForfeitAreSavedTogether() throws SQLException {
        long id = start(FOUR);
        ActiveMatch match = service.openMatches(id).get(0);
        store.writesUntilFailure = 1; // the database goes down after one more write
        try {
            service.drop(code(id), GUILD, match.player1());
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
        int posts = announcer.posts.size();
        String reply = service.drop(code(id), GUILD, loser);
        assertTrue(reply.contains("made again"), reply);
        List<MatchRecord> round2 = stored(id).matches().stream().filter(m -> m.round() == 2).toList();
        assertEquals(round2.size(), 2); // one match + one bye among the 3 remaining players
        assertTrue(round2.stream().noneMatch(m -> m.involves(loser)));
        assertEquals(announcer.posts.size(), posts); // the new pairings are shown on continue
    }

    @Test
    public void earlyLeaderAfterDropPlaysOn() throws SQLException {
        long id = start(FOUR);
        List<ActiveMatch> round1 = service.openMatches(id);
        playRound(id);
        // One of the two 1-0 players drops: the other is the only player without a loss, but 4 players play at
        // least 2 rounds, so round 2 is paired again among the 3 remaining players
        service.drop(code(id), GUILD, round1.get(0).player1());
        assertEquals(stored(id).status(), TournamentStatus.RUNNING);
        assertTrue(service.continueRound(code(id), GUILD).contains("Round 2 of"));
    }

    @Test
    public void tiedLeadersAfterFourRoundsPlayOff() throws SQLException {
        // 10 players need 4 rounds; every match of those ends as a double loss, so all are tied on 4 losses
        List<Long> ten = new ArrayList<>();
        for (long player = 101; player <= 110; player++) {
            ten.add(player);
        }
        long id = start(ten);
        for (int round = 1; round <= 4; round++) {
            if (round > 1) {
                service.continueRound(code(id), GUILD);
            }
            for (ActiveMatch match : service.openMatches(id)) {
                service.doubleLoss(match.id(), GUILD, match.player1(), false);
            }
        }
        assertEquals(stored(id).status(), TournamentStatus.RUNNING);
        assertTrue(announcer.last().contains("Play-off:"), announcer.last());

        // Round 5: everyone is a leader, so everyone plays; the five winners stay tied on 4 losses
        service.continueRound(code(id), GUILD);
        assertTrue(announcer.last().contains("are still tied after 4 rounds"), announcer.last());
        assertEquals(service.openMatches(id).size(), 5);
        Set<Long> winners = new HashSet<>();
        service.openMatches(id).forEach(match -> winners.add(match.player1()));
        playRound(id);

        // From round 6 on only the leaders play, until one is left
        while (stored(id).status() == TournamentStatus.RUNNING) {
            service.continueRound(code(id), GUILD);
            Set<Long> leaders = new HashSet<>(winners);
            for (ActiveMatch match : service.openMatches(id)) {
                assertTrue(leaders.contains(match.player1()) && leaders.contains(match.player2()), match.toString());
                winners.remove(match.player2());
            }
            playRound(id);
        }
        assertEquals(stored(id).status(), TournamentStatus.FINISHED);
        assertEquals(winners, Set.of(stored(id).winner()));
    }

    @Test
    public void lastPlayerLeftWins() throws SQLException {
        long id = start(List.of(101L, 102L));
        service.drop(code(id), GUILD, 101L);
        assertEquals(stored(id).status(), TournamentStatus.FINISHED);
        assertEquals(stored(id).winner(), Long.valueOf(102));
    }

    @Test
    public void cancelAbandonsWithoutPoints() throws SQLException {
        long id = start(FOUR);
        assertTrue(service.cancel(code(id), GUILD).contains("cancelled"));
        assertEquals(stored(id).status(), TournamentStatus.ABANDONED);
        assertTrue(service.openMatches(id).isEmpty());
        assertTrue(awarded.isEmpty());
        assertTrue(announcer.last().contains("abandoned"), announcer.last());
        assertTrue(service.continueRound(code(id), GUILD).contains("already abandoned"));
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
        List<ActiveMatch> open = service.openMatches(id);
        assertEquals(open.size(), 1);
        ActiveMatch fresh = open.get(0);
        assertTrue(announcer.dmsTo(fresh.player1()).get(0).contains("The bot restarted"));
        assertTrue(announcer.dmsTo(fresh.player1()).get(0).contains("`" + fresh.id() + "`"));
        String reply = service.finishMatch(fresh.id(), GUILD, fresh.player2(), fresh.player2(), false);
        assertTrue(reply.contains("Round 1 of Tournament `" + code(id) + "` is complete"), reply);
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

    @Test
    public void dropMidRoundDmsInsteadOfPosting() throws SQLException {
        long id = start(FOUR);
        int posts = announcer.posts.size();
        ActiveMatch match = service.openMatches(id).get(0);
        service.drop(code(id), GUILD, match.player1());
        assertEquals(announcer.posts.size(), posts);
        assertTrue(announcer.dmsTo(match.player1()).get(0).contains("You dropped out"));
        assertTrue(announcer.dmsTo(match.player2()).get(0).contains("you win match `" + match.id() + "`"));
    }

    @Test
    public void dropBetweenRoundsRepairsQuietly() throws SQLException {
        long id = start(FOUR);
        playRound(id);
        int posts = announcer.posts.size();
        service.drop(code(id), GUILD, 101L);
        assertEquals(announcer.posts.size(), posts);
        service.continueRound(code(id), GUILD);
        assertTrue(!announcer.last().contains("<@101> vs") && !announcer.last().contains("vs <@101>"), announcer.last());
    }

    @Test
    public void failedDropBetweenRoundsChangesNothingAndCanBeRetried() throws SQLException {
        long id = start(FOUR);
        playRound(id);
        List<MatchRecord> before = stored(id).matches();
        store.writesUntilFailure = 0;
        expectThrows(SQLException.class, () -> service.drop(code(id), GUILD, 101L));
        assertEquals(stored(id).matches(), before); // prepared round 2 still there
        assertTrue(stored(id).droppedInRound().isEmpty());
        store.writesUntilFailure = -1;
        assertTrue(service.drop(code(id), GUILD, 101L).contains("made again"));
        assertEquals(stored(id).droppedInRound().get(101L), Integer.valueOf(1));
        assertTrue(stored(id).matches().stream().filter(m -> m.round() == 2).noneMatch(m -> m.involves(101L)));
    }

    @Test
    public void failedRepairAfterDropBetweenRoundsIsRepairedByContinue() throws SQLException {
        long id = start(FOUR);
        playRound(id);
        store.writesUntilFailure = 1; // the drop (with the deletion of round 2) succeeds, the new pairings do not
        String reply = service.drop(code(id), GUILD, 101L);
        assertTrue(reply.contains("dropped out"), reply);
        assertTrue(reply.contains("could not be prepared"), reply);
        assertEquals(stored(id).droppedInRound().get(101L), Integer.valueOf(1));
        assertTrue(stored(id).matches().stream().noneMatch(m -> m.round() == 2));
        store.writesUntilFailure = -1;
        service.continueRound(code(id), GUILD);
        List<MatchRecord> round2 = stored(id).matches().stream().filter(m -> m.round() == 2).toList();
        assertEquals(round2.size(), 2);
        assertTrue(round2.stream().noneMatch(m -> m.involves(101L)));
    }

    @Test
    public void dropThatEndsTheTournamentIsPosted() throws SQLException {
        long id = start(List.of(101L, 102L));
        service.drop(code(id), GUILD, 101L);   // forfeit closes round 1 → winner decided
        assertTrue(announcer.last().contains("wins after 1 round"), announcer.last());
    }

    @Test
    public void failedPostOfAnEndingDropIsNotReportedAsPrepareFailed() throws SQLException {
        long id = start(List.of(101L, 102L, 103L));
        playRound(id); // one match plus a bye
        service.drop(code(id), GUILD, 101L);
        announcer.postsFail = true;
        String reply = service.drop(code(id), GUILD, 102L);
        assertTrue(reply.contains("dropped out"), reply);
        assertTrue(!reply.contains("could not be prepared"), reply);
        assertEquals(stored(id).status(), TournamentStatus.FINISHED);
    }

    @Test
    public void dropsBetweenRoundsEndTheTournamentWithTheLastPlayer() throws SQLException {
        long id = start(List.of(101L, 102L, 103L));
        playRound(id); // one match plus a bye
        service.drop(code(id), GUILD, 101L);
        int posts = announcer.posts.size();
        String reply = service.drop(code(id), GUILD, 102L);
        assertTrue(reply.contains("dropped out"), reply);
        assertEquals(announcer.posts.size(), posts + 1);
        assertTrue(announcer.last().contains("wins after"), announcer.last());
        assertTrue(announcer.last().contains("P103"), announcer.last());
        assertEquals(stored(id).status(), TournamentStatus.FINISHED);
    }

    @Test
    public void restartDmsNewMatchIdsInsteadOfPosting() throws SQLException {
        long id = start(FOUR);
        int posts = announcer.posts.size();
        service = newService(7);
        service.recover();
        assertEquals(announcer.posts.size(), posts);
        for (ActiveMatch fresh : service.openMatches(id)) {
            assertTrue(announcer.dmsTo(fresh.player1()).get(0).contains("`" + fresh.id() + "`"));
            assertTrue(announcer.dmsTo(fresh.player2()).get(0).contains("`" + fresh.id() + "`"));
        }
    }

    @Test
    public void cancelOfATournamentTheStoreAlreadyEndedForgetsIt() throws SQLException {
        long id = start(FOUR);
        store.endBehindTheServicesBack(id, TournamentStatus.FINISHED);
        assertTrue(service.cancel(code(id), GUILD).contains("already finished"));
        assertTrue(service.openMatches(id).isEmpty());
        assertTrue(service.start(GUILD, CHANNEL, ADMIN, FOUR).contains("started")); // players are free again
    }

    @Test
    public void continueOfATournamentTheStoreAlreadyEndedForgetsIt() throws SQLException {
        long id = start(FOUR);
        playRound(id);
        store.endBehindTheServicesBack(id, TournamentStatus.ABANDONED);
        assertTrue(service.continueRound(code(id), GUILD).contains("already abandoned"));
        assertTrue(service.start(GUILD, CHANNEL, ADMIN, FOUR).contains("started"));
    }

    @Test
    public void timerForgetsATournamentTheStoreAlreadyEndedAndDoesNotFailAgain() throws SQLException {
        long id = start(FOUR);
        store.endBehindTheServicesBack(id, TournamentStatus.ABANDONED);
        clock.advance(Duration.ofHours(49));
        int posts = announcer.posts.size();
        service.abandonExpired();
        service.abandonExpired();
        assertEquals(announcer.posts.size(), posts);
        assertTrue(service.start(GUILD, CHANNEL, ADMIN, FOUR).contains("started"));
    }

    @Test
    public void aWriteFailureOfARunningTournamentKeepsItRunning() throws SQLException {
        long id = start(FOUR);
        store.failWrites = true;
        expectThrows(SQLException.class, () -> service.cancel(code(id), GUILD));
        store.failWrites = false;
        assertEquals(stored(id).status(), TournamentStatus.RUNNING);
        assertTrue(service.cancel(code(id), GUILD).contains("cancelled"));
    }
}
