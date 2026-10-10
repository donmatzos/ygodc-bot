package at.magi.ygodiscordbot.impl.tournament;

import at.magi.ygodiscordbot.entity.tournament.MatchRecord;
import at.magi.ygodiscordbot.entity.tournament.Standings;
import at.magi.ygodiscordbot.entity.tournament.TournamentRecord;
import at.magi.ygodiscordbot.entity.tournament.TournamentStatus;
import org.testng.annotations.Test;

import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.stream.LongStream;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

public class TournamentServiceTest extends TournamentServiceTestBase {

    @Test
    public void startStoresCodeAndViennaDay() throws SQLException {
        clock.set(Instant.parse("2026-10-09T23:30:00Z")); // already the 10th in Vienna
        long id = start(FOUR);
        assertEquals(stored(id).playedOn(), LocalDate.of(2026, 10, 10));
        assertTrue(code(id).matches("[a-z2-9]{9}-26-10-10"), code(id));
    }

    @Test
    public void startRetriesWhenTheCodeExists() throws SQLException {
        long first = start(FOUR);
        // same seed again generates the same first code; the retry must pick another
        service = newService(42);
        store.failWrites = false;
        long second = start(List.of(201L, 202L, 203L, 204L));
        assertTrue(second != first);
        assertTrue(!code(first).equals(code(second)));
    }

    @Test
    public void startPostsRoundOneWithMatchIdsAndPings() throws SQLException {
        String reply = service.start(GUILD, CHANNEL, ADMIN, FOUR);
        long id = store.lastId();
        assertTrue(reply.contains("Tournament #" + id + " started with 4 players"), reply);
        assertEquals(stored(id).status(), TournamentStatus.RUNNING);
        List<ActiveMatch> open = service.openMatches(id);
        assertEquals(open.size(), 2);
        RecordingAnnouncer.Post post = announcer.posts.get(0);
        assertEquals(post.channelId(), CHANNEL);
        assertTrue(post.ping());
        for (ActiveMatch match : open) {
            assertTrue(match.id() >= TournamentService.MIN_MATCH_ID && match.id() <= TournamentService.MAX_MATCH_ID);
            assertTrue(post.text().contains("`" + match.id() + "`"), post.text());
        }
    }

    @Test
    public void startRefusesPlayersOfARunningTournament() throws SQLException {
        start(FOUR);
        String reply = service.start(GUILD, CHANNEL, ADMIN, List.of(104L, 105L));
        assertTrue(reply.contains("Already playing") && reply.contains("<@104>"), reply);
        assertEquals(store.lastId(), 1);
    }

    @Test
    public void startRefusesTooFewOrTooManyPlayers() throws SQLException {
        assertTrue(service.start(GUILD, CHANNEL, ADMIN, List.of(101L)).contains("2–32"));
        List<Long> many = LongStream.rangeClosed(1, 33).boxed().toList();
        assertTrue(service.start(GUILD, CHANNEL, ADMIN, many).contains("2–32"));
        assertEquals(store.lastId(), 0);
    }

    @Test
    public void onlyPlayersOfTheMatchMayReportIt() throws SQLException {
        long id = start(FOUR);
        ActiveMatch match = service.openMatches(id).get(0);
        assertTrue(service.finishMatch(match.id(), GUILD, 999, match.player1(), false)
                .contains("You are not playing in match"));
        assertTrue(service.finishMatch(match.id(), GUILD, match.player1(), 999, false)
                .contains("<@999> is not playing in match"));
        assertTrue(service.finishMatch(match.id(), GUILD + 1, match.player1(), match.player1(), false)
                .contains("no open match"));
        assertTrue(service.finishMatch(match.id(), GUILD, match.player2(), match.player1(), false).contains("wins"));
        assertTrue(service.finishMatch(match.id(), GUILD, match.player2(), match.player2(), false)
                .contains("already finished"));
        // An organizer may correct it while the round is open
        assertTrue(service.finishMatch(match.id(), GUILD, ADMIN, match.player2(), true).contains("is now the winner"));
        assertTrue(stored(id).matches().contains(new MatchRecord(1, match.player1(), match.player2(), match.player2())));
    }

    @Test
    public void fourPlayersFinishAfterTwoRoundsWithPoints() throws SQLException {
        long id = start(FOUR);
        String reply = playRound(id);
        assertTrue(reply.contains("Round 1 is complete"), reply);
        assertTrue(announcer.posts.get(announcer.posts.size() - 2).text().contains("Round 1 results"));
        assertTrue(announcer.last().contains("Round 2 pairings"), announcer.last());
        assertTrue(service.openMatches(id).isEmpty());

        assertTrue(service.continueRound(id, GUILD).contains("Round 2 of tournament #" + id + " started"));
        assertEquals(service.openMatches(id).size(), 2);
        playRound(id);

        TournamentRecord record = stored(id);
        assertEquals(record.status(), TournamentStatus.FINISHED);
        Standings standings = Standings.of(record.players(), Set.of(), record.matches());
        Standings.Entry champion = standings.ranked().get(0);
        assertEquals(record.winner(), Long.valueOf(champion.player()));
        assertEquals(champion.wins(), 2);
        assertEquals(champion.losses(), 0);
        assertEquals(awarded.get(champion.player()), Long.valueOf(4)); // 2 wins + 2 rounds
        assertEquals(awarded.values().stream().mapToLong(Long::longValue).sum(), 6); // + two 1-1 players
        assertTrue(announcer.last().contains("wins after 2 rounds"), announcer.last());
        assertTrue(service.openMatches(id).isEmpty());
        assertTrue(service.continueRound(id, GUILD).contains("already finished"));
    }

    @Test
    public void doubleLossCountsForBothAndDoesNotEndEarly() throws SQLException {
        long id = start(FOUR);
        List<ActiveMatch> open = service.openMatches(id);
        ActiveMatch timedOut = open.get(0);
        assertTrue(service.doubleLoss(timedOut.id(), GUILD, 999, false).contains("You are not playing in match"));
        String reply = service.doubleLoss(timedOut.id(), GUILD, timedOut.player2(), false);
        assertTrue(reply.contains("both lose"), reply);
        assertTrue(service.doubleLoss(timedOut.id(), GUILD, timedOut.player1(), false).contains("already finished"));
        ActiveMatch other = open.get(1);
        service.finishMatch(other.id(), GUILD, other.player1(), other.player1(), false);
        // other.player1() is the only player without a loss, but 4 players play at least 2 rounds
        assertEquals(stored(id).status(), TournamentStatus.RUNNING);
        assertTrue(announcer.posts.get(announcer.posts.size() - 2).text().contains("both lose (time limit)"));
        assertTrue(announcer.last().contains("Round 2 pairings"), announcer.last());
        Standings standings = Standings.of(stored(id).players(), Set.of(), stored(id).matches());
        assertEquals(standings.entry(timedOut.player1()).losses(), 1);
        assertEquals(standings.entry(timedOut.player2()).losses(), 1);
    }

    @Test
    public void organizerSwitchesBetweenWinnerAndDoubleLoss() throws SQLException {
        long id = start(FOUR);
        ActiveMatch match = service.openMatches(id).get(0);
        service.finishMatch(match.id(), GUILD, match.player1(), match.player1(), false);
        assertTrue(service.doubleLoss(match.id(), GUILD, ADMIN, true).contains("now a double loss"));
        assertTrue(stored(id).matches().contains(new MatchRecord(1, match.player1(), match.player2(), null, true)));
        assertTrue(service.finishMatch(match.id(), GUILD, ADMIN, match.player2(), true).contains("is now the winner"));
        assertTrue(stored(id).matches().contains(new MatchRecord(1, match.player1(), match.player2(), match.player2())));
    }

    @Test
    public void threePlayersUseByes() throws SQLException {
        long id = start(List.of(101L, 102L, 103L));
        assertTrue(announcer.last().contains("gets a free win"), announcer.last());
        assertEquals(service.openMatches(id).size(), 1);
        playRound(id);
        service.continueRound(id, GUILD);
        playRound(id);
        TournamentRecord record = stored(id);
        assertEquals(record.status(), TournamentStatus.FINISHED);
        Standings standings = Standings.of(record.players(), Set.of(), record.matches());
        assertEquals(standings.entry(record.winner()).losses(), 0);
    }

    @Test
    public void continueRefusedWhileMatchesAreOpen() throws SQLException {
        long id = start(FOUR);
        assertTrue(service.continueRound(id, GUILD).contains("still has 2 open matches"));
    }

    @Test
    public void otherServersSeeNoTournament() throws SQLException {
        long id = start(FOUR);
        assertTrue(service.continueRound(id, GUILD + 1).contains("no tournament #" + id));
        assertTrue(service.standings(id, GUILD + 1).get(0).contains("no tournament #" + id));
    }

    @Test
    public void standingsShowOpenMatches() throws SQLException {
        long id = start(FOUR);
        String standings = String.join("\n", service.standings(id, GUILD));
        assertTrue(standings.contains("Round 1"), standings);
        for (ActiveMatch match : service.openMatches(id)) {
            assertTrue(standings.contains("`" + match.id() + "`"), standings);
        }
    }

    @Test
    public void closedRoundMatchIdsAreGone() throws SQLException {
        long id = start(FOUR);
        ActiveMatch first = service.openMatches(id).get(0);
        playRound(id);
        int posts = announcer.posts.size();
        assertTrue(service.finishMatch(first.id(), GUILD, ADMIN, first.player2(), true).contains("no open match"));
        assertEquals(announcer.posts.size(), posts);
    }

    @Test
    public void failedWriteChangesNothing() throws SQLException {
        long id = start(FOUR);
        ActiveMatch match = service.openMatches(id).get(0);
        store.failWrites = true;
        expectThrows(SQLException.class,
                () -> service.finishMatch(match.id(), GUILD, match.player1(), match.player1(), false));
        store.failWrites = false;
        assertEquals(service.openMatches(id).size(), 2);
        assertTrue(service.finishMatch(match.id(), GUILD, match.player1(), match.player1(), false).contains("wins"));
    }

    @Test
    public void unsavedPointsDoNotStopTheFinish() throws SQLException {
        service = new TournamentService(store, (player, points) -> {
            throw new SQLException("down");
        }, announcer, clock, new Random(1));
        long id = start(List.of(101L, 102L));
        playRound(id);
        assertEquals(stored(id).status(), TournamentStatus.FINISHED);
        assertTrue(announcer.last().contains("not saved"), announcer.last());
        assertTrue(service.openMatches(id).isEmpty());
    }
}
