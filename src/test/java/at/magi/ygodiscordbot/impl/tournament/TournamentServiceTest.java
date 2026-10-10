package at.magi.ygodiscordbot.impl.tournament;

import at.magi.ygodiscordbot.entity.tournament.MatchRecord;
import at.magi.ygodiscordbot.entity.tournament.Standings;
import at.magi.ygodiscordbot.entity.tournament.TournamentListPage;
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
        store.collisions = 1;
        long id = start(FOUR);
        assertEquals(store.rejectedCodes.size(), 1);
        assertEquals(stored(id).status(), TournamentStatus.RUNNING);
        assertTrue(!code(id).equals(store.rejectedCodes.get(0)));
    }

    @Test
    public void startGivesUpWhenEveryCodeExists() {
        store.collisions = TournamentService.CODE_ATTEMPTS;
        expectThrows(java.sql.SQLIntegrityConstraintViolationException.class, () -> service.start(GUILD, CHANNEL, ADMIN, FOUR));
        assertEquals(store.rejectedCodes.size(), TournamentService.CODE_ATTEMPTS);
        assertEquals(store.loadRunning(), List.of());
    }

    @Test
    public void startPostsRoundOneWithMatchIdsAndPings() throws SQLException {
        String reply = service.start(GUILD, CHANNEL, ADMIN, FOUR);
        long id = store.lastId();
        assertTrue(reply.contains("Tournament `" + code(id) + "` started with 4 players"), reply);
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
        assertTrue(reply.contains("Round 1 of Tournament `" + code(id) + "` is complete"), reply);
        assertTrue(announcer.last().contains("Round 1 results"), announcer.last());
        assertTrue(announcer.last().contains("Round 2 pairings"), announcer.last());
        assertTrue(service.openMatches(id).isEmpty());

        assertTrue(service.continueRound(code(id), GUILD).contains("Round 2 of tournament `" + code(id) + "` started"));
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
        assertTrue(service.continueRound(code(id), GUILD).contains("already finished"));
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
        assertTrue(announcer.last().contains("both lose (time limit)"), announcer.last());
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
        service.continueRound(code(id), GUILD);
        playRound(id);
        TournamentRecord record = stored(id);
        assertEquals(record.status(), TournamentStatus.FINISHED);
        Standings standings = Standings.of(record.players(), Set.of(), record.matches());
        assertEquals(standings.entry(record.winner()).losses(), 0);
    }

    @Test
    public void continueRefusedWhileMatchesAreOpen() throws SQLException {
        long id = start(FOUR);
        assertTrue(service.continueRound(code(id), GUILD).contains("still has 2 open matches"));
    }

    @Test
    public void otherServersSeeNoTournament() throws SQLException {
        long id = start(FOUR);
        assertTrue(service.continueRound(code(id), GUILD + 1).contains("no tournament `" + code(id) + "`"));
        assertTrue(rendered(service.standings(code(id), GUILD + 1)).contains("no tournament `" + code(id) + "`"));
    }

    @Test
    public void standingsShowOpenMatches() throws SQLException {
        long id = start(FOUR);
        String standings = rendered(service.standings(code(id), GUILD));
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

    @Test
    public void startPostIncludesCodeAndPings() throws SQLException {
        long id = start(FOUR);
        RecordingAnnouncer.Post post = announcer.posts.get(0);
        assertTrue(post.ping());
        assertTrue(post.text().contains("Tournament `" + code(id) + "` · 2026-10-10 · Round 1"), post.text());
    }

    @Test
    public void matchResultGoesToBothPlayersByDmNotToTheChannel() throws SQLException {
        long id = start(FOUR);
        int posts = announcer.posts.size();
        ActiveMatch match = service.openMatches(id).get(0);
        String reply = service.finishMatch(match.id(), GUILD, match.player1(), match.player1(), false);
        assertTrue(reply.contains(code(id)), reply);
        assertEquals(announcer.posts.size(), posts);
        assertEquals(announcer.dmsTo(match.player1()).size(), 1);
        assertEquals(announcer.dmsTo(match.player2()).size(), 1);
        assertTrue(announcer.dmsTo(match.player2()).get(0).contains(code(id)));
    }

    @Test
    public void adminCorrectionIsDmedToo() throws SQLException {
        long id = start(FOUR);
        List<ActiveMatch> open = service.openMatches(id);
        ActiveMatch match = open.get(0);
        service.finishMatch(match.id(), GUILD, match.player1(), match.player1(), false);
        service.finishMatch(match.id(), GUILD, ADMIN, match.player2(), true);
        assertEquals(announcer.dmsTo(match.player1()).size(), 2);
        assertTrue(announcer.dmsTo(match.player1()).get(1).contains("corrected"));
    }

    @Test
    public void fullRoundIsOnePostWithResultsStandingsAndPairings() throws SQLException {
        long id = start(FOUR);
        int posts = announcer.posts.size();
        playRound(id);
        assertEquals(announcer.posts.size(), posts + 1);
        String post = announcer.last();
        assertTrue(post.contains("Round 1 results"), post);
        assertTrue(post.contains("**Standings**"), post);
        assertTrue(post.contains("Round 2 pairings"), post);
        assertTrue(post.indexOf("Round 1 results") < post.indexOf("Round 2 pairings"), post);
    }

    @Test
    public void continueRepostsMatchupsAndStandings() throws SQLException {
        long id = start(FOUR);
        playRound(id);
        service.continueRound(code(id), GUILD);
        RecordingAnnouncer.Post post = announcer.posts.get(announcer.posts.size() - 1);
        assertTrue(post.ping());
        assertTrue(post.text().contains("Tournament `" + code(id) + "` · Round 2"), post.text());
        assertTrue(post.text().contains("**Standings**"), post.text());
        for (ActiveMatch match : service.openMatches(id)) {
            assertTrue(post.text().contains("`" + match.id() + "`"), post.text());
        }
    }

    @Test
    public void lastRoundIsOnePostEndingWithTheWinner() throws SQLException {
        long id = start(List.of(101L, 102L));
        int posts = announcer.posts.size();
        playRound(id);
        assertEquals(announcer.posts.size(), posts + 1);
        RecordingAnnouncer.Post post = announcer.posts.get(announcer.posts.size() - 1);
        assertTrue(post.ping());
        assertTrue(post.text().contains("Round 1 results"), post.text());
        assertTrue(post.text().contains("wins after 1 round"), post.text());
        assertEquals(post.text().split("\\*\\*Standings\\*\\*", -1).length - 1, 1, post.text()); // table once
    }

    @Test
    public void closedDmsDoNotStopTheTournament() throws SQLException {
        announcer.dmsClosed = true;
        long id = start(FOUR);
        playRound(id);
        assertTrue(announcer.last().contains("Round 2 pairings"), announcer.last());
        assertTrue(announcer.dms.isEmpty());
    }

    @Test
    public void closedDmsDoNotBreakDropOrResume() throws SQLException {
        long id = start(FOUR);
        announcer.dmsClosed = true;
        ActiveMatch match = service.openMatches(id).get(0);
        assertTrue(service.drop(code(id), GUILD, match.player1()).contains("dropped out"));
        service = newService(7);
        service.recover(); // resume DMs the new match IDs
        assertEquals(service.openMatches(id).size(), 1);
    }

    @Test
    public void unknownCodeInAnotherServer() throws SQLException {
        long id = start(FOUR);
        assertTrue(service.continueRound(code(id), GUILD + 1).contains("no tournament `" + code(id) + "`"));
    }

    @Test
    public void listPageReturnsStoredPage() throws SQLException {
        long id = start(FOUR);
        TournamentListPage page = service.listPage(GUILD, null, 1);
        assertEquals(page.rows().get(0).code(), code(id));
        assertEquals(service.listPage(GUILD + 1, null, 1).total(), 0);
    }
}
