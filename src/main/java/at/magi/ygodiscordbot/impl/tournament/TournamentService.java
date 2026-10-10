package at.magi.ygodiscordbot.impl.tournament;

import at.magi.ygodiscordbot.entity.tournament.MatchRecord;
import at.magi.ygodiscordbot.entity.tournament.NewTournament;
import at.magi.ygodiscordbot.entity.tournament.Pairing;
import at.magi.ygodiscordbot.entity.tournament.Standings;
import at.magi.ygodiscordbot.entity.tournament.SwissPairer;
import at.magi.ygodiscordbot.entity.tournament.TournamentCode;
import at.magi.ygodiscordbot.entity.tournament.TournamentListPage;
import at.magi.ygodiscordbot.entity.tournament.TournamentPoints;
import at.magi.ygodiscordbot.entity.tournament.TournamentRecord;
import at.magi.ygodiscordbot.entity.tournament.TournamentStatus;
import at.magi.ygodiscordbot.entity.tournament.WinnerRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

/**
 * All tournament state changes. Every method runs on the single {@code db} thread (commands via DatabaseReplies, the
 * timer and startup recovery through the same executor), so the in-memory state needs no locks and two reports can't
 * close a round twice. Each change is written to the database first, then applied in memory, then announced: a failed
 * write changes nothing.
 */
public final class TournamentService {

    private static final Logger log = LoggerFactory.getLogger(TournamentService.class);

    static final int CODE_ATTEMPTS = 3;
    static final int MIN_PLAYERS = 2;
    static final int MAX_PLAYERS = 32;
    static final Duration TIMEOUT = Duration.ofHours(48);
    static final int MIN_MATCH_ID = 10_000;
    static final int MAX_MATCH_ID = 99_999;

    /** Adds leaderboard points (the leaderboard's {@code changePoints}). */
    @FunctionalInterface
    public interface PointsAwarder {
        void add(long player, long points) throws SQLException;
    }

    private final TournamentStore store;
    private final PointsAwarder points;
    private final TournamentAnnouncer announcer;
    private final Clock clock;
    private final Random random;
    private final Map<Long, ActiveTournament> tournaments = new HashMap<>();
    /** Matches of the current rounds (finished ones too, so an organizer can correct them until the round closes). */
    private final Map<Integer, ActiveMatch> matches = new HashMap<>();
    private boolean loaded;

    public TournamentService(TournamentStore store, PointsAwarder points, TournamentAnnouncer announcer, Clock clock,
                             Random random) {
        this.store = store;
        this.points = points;
        this.announcer = announcer;
        this.clock = clock;
        this.random = random;
    }

    /**
     * Loads the running tournaments once: at startup, or on the first command after the database came back. Then
     * resumes each: abandons it if it is older than 48 h, re-creates the open matches with new IDs (and DMs them to
     * their players), or finishes a round end that was interrupted.
     */
    public void recover() throws SQLException {
        if (loaded) {
            return;
        }
        List<TournamentRecord> running = store.loadRunning();
        running.forEach(record -> tournaments.put(record.id(), new ActiveTournament(record)));
        loaded = true;
        log.info("Loaded {} running tournament(s)", running.size());
        for (ActiveTournament tournament : List.copyOf(tournaments.values())) {
            try {
                resume(tournament);
            } catch (SQLException | RuntimeException e) {
                // The timer and /tournament continue retry what is missing
                log.warn("Could not resume tournament {}", tournament.id, e);
            }
        }
    }

    private void resume(ActiveTournament tournament) throws SQLException {
        if (expired(tournament)) {
            announcer.post(tournament.channelId, abandon(tournament, TournamentMessages.TIMEOUT_REASON), false);
        } else if (!tournament.roundClosed()) {
            int round = tournament.currentRound();
            createMatches(tournament, round);
            for (ActiveMatch match : openMatches(tournament.id)) {
                List<String> dm = TournamentMessages.newMatchIdDm(tournament.code, round, match);
                announcer.dm(match.player1(), dm);
                announcer.dm(match.player2(), dm);
            }
        } else {
            // The round was complete before the restart, so this is still a round-end post
            Announcement prepared = prepareNextRound(tournament);
            if (prepared != null) {
                announcer.post(tournament.channelId, prepared.text(), prepared.ping());
            }
        }
    }

    public String start(long guildId, long channelId, long admin, List<Long> players) throws SQLException {
        recover();
        if (players.size() < MIN_PLAYERS || players.size() > MAX_PLAYERS) {
            return TournamentMessages.playerCount(players.size());
        }
        List<Long> busy = players.stream().filter(this::inRunningTournament).toList();
        if (!busy.isEmpty()) {
            return TournamentMessages.alreadyPlaying(busy);
        }
        Instant now = clock.instant();
        List<Pairing> round1 = SwissPairer.pair(Standings.of(players, Set.of(), List.of()), 1, random);
        LocalDate day = LocalDate.ofInstant(now, TournamentCode.ZONE);
        String code = null;
        long id = 0;
        for (int attempt = 1; code == null; attempt++) {
            String candidate = TournamentCode.generate(random, day);
            try {
                id = store.create(new NewTournament(candidate, day, guildId, channelId, admin, now, players), round1);
                code = candidate;
            } catch (SQLIntegrityConstraintViolationException e) {
                if (attempt == CODE_ATTEMPTS) {
                    throw e;
                }
                log.warn("Tournament code {} already exists, trying another", candidate);
            }
        }
        ActiveTournament tournament = new ActiveTournament(new TournamentRecord(id, code, day, guildId, channelId, admin,
                TournamentStatus.RUNNING, null, now, null, 1, players, Map.of(),
                round1.stream().map(pairing -> MatchRecord.of(1, pairing)).toList()));
        tournaments.put(id, tournament);
        List<ActiveMatch> created = createMatches(tournament, 1);
        log.info("Tournament {} started with {} players in channel {}", id, players.size(), channelId);
        announcer.post(channelId, TournamentMessages.announced(code, day, created, tournament.byes(1)), true);
        return TournamentMessages.started(code, players.size());
    }

    /** {@code /match finish} ({@code admin = false}) and {@code /match-admin finish} ({@code admin = true}). */
    public String finishMatch(int matchId, long guildId, long caller, long winner, boolean admin) throws SQLException {
        return report(matchId, guildId, caller, winner, admin);
    }

    /** {@code /match doubleloss} and {@code /match-admin doubleloss}: time ran out, both players lose. */
    public String doubleLoss(int matchId, long guildId, long caller, boolean admin) throws SQLException {
        return report(matchId, guildId, caller, null, admin);
    }

    /** Records a winner, or a double loss when {@code winner} is null, then closes the round if it was the last. */
    private String report(int matchId, long guildId, long caller, Long winner, boolean admin) throws SQLException {
        recover();
        ActiveMatch match = matches.get(matchId);
        ActiveTournament tournament = match == null ? null : tournaments.get(match.tournamentId());
        if (tournament == null || tournament.guildId != guildId) {
            return TournamentMessages.matchNotFound(matchId);
        }
        if (!admin && !match.involves(caller)) {
            return TournamentMessages.notYourMatch(tournament.code, matchId);
        }
        if (winner != null && !match.involves(winner)) {
            return TournamentMessages.winnerNotInMatch(tournament.code, matchId, winner);
        }
        MatchRecord record = tournament.record(match.round(), match.player1());
        boolean sameResult = winner == null ? record.doubleLoss() : winner.equals(record.winner());
        if (record.isPlayed() && (!admin || sameResult)) {
            return TournamentMessages.alreadyFinished(tournament.code, matchId, record);
        }
        boolean corrected = record.isPlayed();
        String reply;
        if (winner == null) {
            store.recordDoubleLoss(tournament.id, match.round(), match.player1());
            tournament.setDoubleLoss(match.round(), match.player1());
            log.info("Tournament {}: match {} is a double loss{}", tournament.id, matchId,
                    corrected ? " (corrected)" : "");
            reply = TournamentMessages.doubleLossRecorded(tournament.code, matchId, match.player1(), match.player2(),
                    corrected);
        } else {
            store.recordWinner(tournament.id, match.round(), match.player1(), winner);
            tournament.setWinner(match.round(), match.player1(), winner);
            log.info("Tournament {}: match {} won by {}{}", tournament.id, matchId, winner,
                    corrected ? " (corrected)" : "");
            reply = TournamentMessages.matchFinished(tournament.code, matchId, winner, corrected);
        }
        MatchRecord saved = tournament.record(match.round(), match.player1());
        List<String> dm = TournamentMessages.matchResultDm(tournament.code, match.round(), matchId, saved, corrected);
        announcer.dm(match.player1(), dm);
        announcer.dm(match.player2(), dm);
        String roundEnd = closeRoundIfDone(tournament);
        return roundEnd == null ? reply : reply + "\n" + roundEnd;
    }

    public String continueRound(String code, long guildId) throws SQLException {
        recover();
        ActiveTournament tournament = running(code, guildId);
        if (tournament == null) {
            return notRunning(code, guildId);
        }
        if (!tournament.roundClosed()) {
            return TournamentMessages.roundStillOpen(code, tournament.currentRound(),
                    openMatches(tournament.id).size());
        }
        // Retries a round end that failed before (e.g. the database was down); may finish the tournament. New
        // pairings are not posted on their own: the round start below shows them.
        Announcement prepared = prepareNextRound(tournament);
        if (!tournaments.containsKey(tournament.id)) {
            announcer.post(tournament.channelId, prepared.text(), prepared.ping());
            return TournamentMessages.endedInsteadOfContinue(code);
        }
        int next = tournament.currentRound() + 1;
        store.startRound(tournament.id, next);
        tournament.startRound(next);
        List<ActiveMatch> created = createMatches(tournament, next);
        log.info("Tournament {}: round {} started", tournament.id, next);
        announcer.post(tournament.channelId, TournamentMessages.roundStart(code, next, created,
                tournament.byes(next), tournament.standings()), true);
        return TournamentMessages.roundStarted(code, next);
    }

    public NamedText standings(String code, long guildId) throws SQLException {
        recover();
        ActiveTournament tournament = running(code, guildId);
        if (tournament != null) {
            return TournamentMessages.standings(code, TournamentStatus.RUNNING, tournament.currentRound(),
                    tournament.standings(), openMatches(tournament.id), null);
        }
        Optional<TournamentRecord> stored = store.loadByCode(code).filter(record -> record.guildId() == guildId);
        if (stored.isEmpty()) {
            return NamedText.plain(TournamentMessages.tournamentNotFound(code));
        }
        TournamentRecord record = stored.get();
        Standings standings = Standings.of(record.players(), record.droppedInRound().keySet(), record.matches());
        return TournamentMessages.standings(code, record.status(), record.currentRound(), standings, List.of(),
                record.winner());
    }

    /** One page of this server's tournaments (no in-memory state involved). */
    public TournamentListPage listPage(long guildId, LocalDate day, int page) throws SQLException {
        return store.list(guildId, day, page);
    }

    public String cancel(String code, long guildId) throws SQLException {
        recover();
        ActiveTournament tournament = running(code, guildId);
        if (tournament == null) {
            return notRunning(code, guildId);
        }
        announcer.post(tournament.channelId, abandon(tournament, TournamentMessages.CANCELLED_REASON), false);
        return TournamentMessages.cancelled(code);
    }

    /**
     * Removes a player from the remaining rounds. Their open match is won by the opponent; prepared next pairings are
     * made again without them (which may also decide the winner). The players are told by DM; the channel only gets a
     * post when the drop ends the round or the tournament.
     */
    public String drop(String code, long guildId, long player) throws SQLException {
        recover();
        ActiveTournament tournament = running(code, guildId);
        if (tournament == null) {
            return notRunning(code, guildId);
        }
        if (!tournament.hasPlayer(player)) {
            return TournamentMessages.notAPlayer(code, player);
        }
        if (tournament.isDropped(player)) {
            return TournamentMessages.alreadyDropped(code, player);
        }
        int round = tournament.currentRound();
        ActiveMatch match = openMatches(tournament.id).stream()
                .filter(open -> open.involves(player))
                .findFirst()
                .orElse(null);
        // The drop and the opponent's win are one write, so a failure can't leave the player dropped but playing
        MatchRecord forfeit = match == null ? null
                : tournament.record(match.round(), match.player1()).withWinner(match.opponentOf(player));
        store.drop(tournament.id, player, round, forfeit);
        tournament.drop(player, round);
        log.info("Tournament {}: player {} dropped in round {}", tournament.id, player, round);
        StringBuilder reply = new StringBuilder(TournamentMessages.dropped(code, player));

        if (match != null) {
            long opponent = match.opponentOf(player);
            tournament.setWinner(match.round(), match.player1(), opponent);
            reply.append('\n').append(TournamentMessages.matchFinished(code, match.id(), opponent, false));
            announcer.dm(player, TournamentMessages.droppedDm(code));
            announcer.dm(opponent, TournamentMessages.forfeitWinDm(code, player, match.id()));
            String roundEnd = closeRoundIfDone(tournament); // posts the round end if this was the last match
            if (roundEnd != null) {
                reply.append('\n').append(roundEnd);
            }
            return reply.toString();
        }

        announcer.dm(player, TournamentMessages.droppedDm(code));
        if (!tournament.pending().isEmpty()) {
            int next = round + 1;
            store.deletePairings(tournament.id, next);
            tournament.removeRound(next);
            Announcement prepared = prepareNextRound(tournament);
            if (!tournaments.containsKey(tournament.id)) {
                // The drop ended the tournament: that is posted; new pairings are not (continue shows them)
                announcer.post(tournament.channelId, prepared.text(), prepared.ping());
            } else {
                reply.append('\n').append(TournamentMessages.repaired(next));
            }
        }
        return reply.toString();
    }

    /** Abandons every tournament older than {@link #TIMEOUT}. Called by the timer every few minutes. */
    public void abandonExpired() throws SQLException {
        recover();
        for (ActiveTournament tournament : List.copyOf(tournaments.values())) {
            if (expired(tournament)) {
                announcer.post(tournament.channelId, abandon(tournament, TournamentMessages.TIMEOUT_REASON), false);
            }
        }
    }

    private boolean expired(ActiveTournament tournament) {
        return !clock.instant().isBefore(tournament.startedAt.plus(TIMEOUT));
    }

    /** Unplayed matches of the tournament's current round, by match ID. */
    List<ActiveMatch> openMatches(long tournamentId) {
        ActiveTournament tournament = tournaments.get(tournamentId);
        if (tournament == null) {
            return List.of();
        }
        return matches.values().stream()
                .filter(match -> match.tournamentId() == tournamentId)
                .filter(match -> !tournament.record(match.round(), match.player1()).isPlayed())
                .sorted(Comparator.comparingInt(ActiveMatch::id))
                .toList();
    }

    // --- Round end ---

    /** What a round end announces after the results, and whether it notifies the players. */
    private record Announcement(NamedText text, boolean ping) {
    }

    /**
     * After the last result of the current round: finishes the tournament or pairs the next round, and posts the
     * results with what follows them as one post.
     */
    private String closeRoundIfDone(ActiveTournament tournament) {
        if (!tournament.roundClosed()) {
            return null;
        }
        int round = tournament.currentRound();
        // Snapshots: the post is rendered later, on a JDA thread. Unplayed next pairings don't change standings.
        List<MatchRecord> played = tournament.round(round);
        Standings standings = tournament.standings();
        matches.values().removeIf(match -> match.tournamentId() == tournament.id);
        log.info("Tournament {}: round {} complete", tournament.id, round);
        try {
            Announcement next = prepareNextRound(tournament);
            boolean finished = !tournaments.containsKey(tournament.id);
            // A finished tournament's winner post carries the final table, so the results skip theirs
            NamedText results = TournamentMessages.roundResults(tournament.code, round, played,
                    finished ? null : standings);
            announcer.post(tournament.channelId, next == null ? results : results.then(next.text()),
                    next != null && next.ping());
        } catch (SQLException | RuntimeException e) {
            // The result is saved; /tournament continue (or the next startup) prepares the round again
            announcer.post(tournament.channelId,
                    TournamentMessages.roundResults(tournament.code, round, played, standings), false);
            log.warn("Tournament {}: could not prepare the round after round {}", tournament.id, round, e);
            return TournamentMessages.roundComplete(tournament.code, round) + "\n"
                    + TournamentMessages.prepareFailed(tournament.code);
        }
        return TournamentMessages.roundComplete(tournament.code, round);
    }

    /**
     * For a closed round without prepared next pairings: ends the tournament or pairs the next round. Posts nothing;
     * returns the announcement, or null if there was nothing to do.
     */
    private Announcement prepareNextRound(ActiveTournament tournament) throws SQLException {
        if (!tournament.roundClosed() || !tournament.pending().isEmpty()) {
            return null;
        }
        Standings standings = tournament.standings();
        if (standings.active().isEmpty()) {
            return new Announcement(abandon(tournament, TournamentMessages.NO_PLAYERS_REASON), false);
        }
        Optional<Long> winner = WinnerRule.winner(standings, tournament.currentRound());
        if (winner.isPresent()) {
            return new Announcement(finish(tournament, winner.get(), standings), true);
        }
        int next = tournament.currentRound() + 1;
        List<Pairing> pairings = SwissPairer.pair(standings, next, random);
        store.savePairings(tournament.id, next, pairings);
        tournament.addPairings(next, pairings);
        log.info("Tournament {}: round {} paired", tournament.id, next);
        return new Announcement(TournamentMessages.nextPairings(tournament.code, next, tournament.pending()), false);
    }

    /** Saves the winner and awards the points; returns the winner post (not posted yet). */
    private NamedText finish(ActiveTournament tournament, long winner, Standings standings) throws SQLException {
        store.finish(tournament.id, winner, clock.instant());
        forget(tournament);
        int rounds = tournament.currentRound();
        Map<Long, Long> earned = TournamentPoints.award(standings, rounds, winner);
        List<Long> failed = new ArrayList<>();
        earned.forEach((player, amount) -> {
            try {
                points.add(player, amount);
            } catch (SQLException | RuntimeException e) {
                failed.add(player);
                log.warn("Tournament {}: could not add {} points to player {}", tournament.id, amount, player, e);
            }
        });
        log.info("Tournament {} finished after {} rounds, winner {}", tournament.id, rounds, winner);
        return TournamentMessages.winner(tournament.code, winner, rounds, standings, earned, List.copyOf(failed));
    }

    /** Marks the tournament abandoned; returns the post (not posted yet). */
    private NamedText abandon(ActiveTournament tournament, String reason) throws SQLException {
        store.abandon(tournament.id, clock.instant());
        forget(tournament);
        log.info("Tournament {} abandoned: {}", tournament.id, reason);
        return TournamentMessages.abandoned(tournament.code, reason);
    }

    private void forget(ActiveTournament tournament) {
        tournaments.remove(tournament.id);
        matches.values().removeIf(match -> match.tournamentId() == tournament.id);
    }

    // --- Helpers ---

    private List<ActiveMatch> createMatches(ActiveTournament tournament, int round) {
        List<ActiveMatch> created = new ArrayList<>();
        for (MatchRecord record : tournament.round(round)) {
            if (record.isBye()) {
                continue;
            }
            ActiveMatch match = new ActiveMatch(newMatchId(), tournament.id, round, record.player1(), record.player2());
            matches.put(match.id(), match);
            created.add(match);
        }
        return created;
    }

    private int newMatchId() {
        int id;
        do {
            id = MIN_MATCH_ID + random.nextInt(MAX_MATCH_ID - MIN_MATCH_ID + 1);
        } while (matches.containsKey(id));
        return id;
    }

    private boolean inRunningTournament(long player) {
        return tournaments.values().stream().anyMatch(tournament -> tournament.isActivePlayer(player));
    }

    /** The running tournament with this code in this server, or null. */
    private ActiveTournament running(String code, long guildId) {
        return tournaments.values().stream()
                .filter(tournament -> tournament.code.equals(code) && tournament.guildId == guildId)
                .findFirst()
                .orElse(null);
    }

    private String notRunning(String code, long guildId) throws SQLException {
        return store.loadByCode(code)
                .filter(record -> record.guildId() == guildId)
                .map(record -> TournamentMessages.notRunning(code, record.status()))
                .orElse(TournamentMessages.tournamentNotFound(code));
    }
}
