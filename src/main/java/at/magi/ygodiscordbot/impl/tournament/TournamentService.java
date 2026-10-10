package at.magi.ygodiscordbot.impl.tournament;

import at.magi.ygodiscordbot.entity.tournament.MatchRecord;
import at.magi.ygodiscordbot.entity.tournament.NewTournament;
import at.magi.ygodiscordbot.entity.tournament.Pairing;
import at.magi.ygodiscordbot.entity.tournament.Standings;
import at.magi.ygodiscordbot.entity.tournament.SwissPairer;
import at.magi.ygodiscordbot.entity.tournament.TournamentCode;
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
     * resumes each: abandons it if it is older than 48 h, re-creates the open matches with new IDs (and posts them),
     * or finishes a round end that was interrupted.
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
            abandon(tournament, TournamentMessages.TIMEOUT_REASON);
        } else if (!tournament.roundClosed()) {
            createMatches(tournament, tournament.currentRound());
            announcer.post(tournament.channelId, TournamentMessages.restarted(tournament.id,
                    tournament.currentRound(), openMatches(tournament.id)), true);
        } else {
            prepareNextRound(tournament);
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
        announcer.post(channelId, TournamentMessages.roundStart(id, 1, created, tournament.byes(1)), true);
        return TournamentMessages.started(id, players.size());
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
            return TournamentMessages.notYourMatch(matchId);
        }
        if (winner != null && !match.involves(winner)) {
            return TournamentMessages.winnerNotInMatch(matchId, winner);
        }
        MatchRecord record = tournament.record(match.round(), match.player1());
        boolean sameResult = winner == null ? record.doubleLoss() : winner.equals(record.winner());
        if (record.isPlayed() && (!admin || sameResult)) {
            return TournamentMessages.alreadyFinished(matchId, record);
        }
        boolean corrected = record.isPlayed();
        String reply;
        if (winner == null) {
            store.recordDoubleLoss(tournament.id, match.round(), match.player1());
            tournament.setDoubleLoss(match.round(), match.player1());
            log.info("Tournament {}: match {} is a double loss{}", tournament.id, matchId,
                    corrected ? " (corrected)" : "");
            reply = TournamentMessages.doubleLossRecorded(matchId, match.player1(), match.player2(), corrected);
        } else {
            store.recordWinner(tournament.id, match.round(), match.player1(), winner);
            tournament.setWinner(match.round(), match.player1(), winner);
            log.info("Tournament {}: match {} won by {}{}", tournament.id, matchId, winner,
                    corrected ? " (corrected)" : "");
            reply = TournamentMessages.matchFinished(matchId, winner, corrected);
        }
        String roundEnd = closeRoundIfDone(tournament);
        return roundEnd == null ? reply : reply + "\n" + roundEnd;
    }

    public String continueRound(long tournamentId, long guildId) throws SQLException {
        recover();
        ActiveTournament tournament = running(tournamentId, guildId);
        if (tournament == null) {
            return notRunning(tournamentId, guildId);
        }
        if (!tournament.roundClosed()) {
            return TournamentMessages.roundStillOpen(tournamentId, tournament.currentRound(),
                    openMatches(tournamentId).size());
        }
        // Retries a round end that failed before (e.g. the database was down); may finish the tournament
        prepareNextRound(tournament);
        if (!tournaments.containsKey(tournamentId)) {
            return TournamentMessages.endedInsteadOfContinue(tournamentId);
        }
        int next = tournament.currentRound() + 1;
        store.startRound(tournamentId, next);
        tournament.startRound(next);
        List<ActiveMatch> created = createMatches(tournament, next);
        log.info("Tournament {}: round {} started", tournamentId, next);
        announcer.post(tournament.channelId,
                TournamentMessages.roundStart(tournamentId, next, created, tournament.byes(next)), true);
        return TournamentMessages.roundStarted(tournamentId, next);
    }

    public List<String> standings(long tournamentId, long guildId) throws SQLException {
        recover();
        ActiveTournament tournament = running(tournamentId, guildId);
        if (tournament != null) {
            return TournamentMessages.standings(tournamentId, TournamentStatus.RUNNING, tournament.currentRound(),
                    tournament.standings(), openMatches(tournamentId), null);
        }
        Optional<TournamentRecord> stored = store.load(tournamentId).filter(record -> record.guildId() == guildId);
        if (stored.isEmpty()) {
            return List.of(TournamentMessages.tournamentNotFound(tournamentId));
        }
        TournamentRecord record = stored.get();
        Standings standings = Standings.of(record.players(), record.droppedInRound().keySet(), record.matches());
        return TournamentMessages.standings(tournamentId, record.status(), record.currentRound(), standings,
                List.of(), record.winner());
    }

    public String cancel(long tournamentId, long guildId) throws SQLException {
        recover();
        ActiveTournament tournament = running(tournamentId, guildId);
        if (tournament == null) {
            return notRunning(tournamentId, guildId);
        }
        abandon(tournament, TournamentMessages.CANCELLED_REASON);
        return TournamentMessages.cancelled(tournamentId);
    }

    /**
     * Removes a player from the remaining rounds. Their open match is won by the opponent; posted next pairings are
     * made again without them (which may also decide the winner).
     */
    public String drop(long tournamentId, long guildId, long player) throws SQLException {
        recover();
        ActiveTournament tournament = running(tournamentId, guildId);
        if (tournament == null) {
            return notRunning(tournamentId, guildId);
        }
        if (!tournament.hasPlayer(player)) {
            return TournamentMessages.notAPlayer(tournamentId, player);
        }
        if (tournament.isDropped(player)) {
            return TournamentMessages.alreadyDropped(tournamentId, player);
        }
        int round = tournament.currentRound();
        ActiveMatch match = openMatches(tournamentId).stream()
                .filter(open -> open.involves(player))
                .findFirst()
                .orElse(null);
        // The drop and the opponent's win are one write, so a failure can't leave the player dropped but playing
        MatchRecord forfeit = match == null ? null
                : tournament.record(match.round(), match.player1()).withWinner(match.opponentOf(player));
        store.drop(tournamentId, player, round, forfeit);
        tournament.drop(player, round);
        log.info("Tournament {}: player {} dropped in round {}", tournamentId, player, round);
        StringBuilder reply = new StringBuilder(TournamentMessages.dropped(tournamentId, player));

        if (match != null) {
            long opponent = match.opponentOf(player);
            tournament.setWinner(match.round(), match.player1(), opponent);
            reply.append('\n').append(TournamentMessages.matchFinished(match.id(), opponent, false));
            announcer.post(tournament.channelId,
                    List.of(TournamentMessages.droppedPost(tournamentId, player, match.id(), opponent)), false);
            String roundEnd = closeRoundIfDone(tournament);
            if (roundEnd != null) {
                reply.append('\n').append(roundEnd);
            }
            return reply.toString();
        }

        announcer.post(tournament.channelId,
                List.of(TournamentMessages.droppedPost(tournamentId, player, null, null)), false);
        if (!tournament.pending().isEmpty()) {
            int next = round + 1;
            store.deletePairings(tournamentId, next);
            tournament.removeRound(next);
            prepareNextRound(tournament);
            if (tournaments.containsKey(tournamentId)) {
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
                abandon(tournament, TournamentMessages.TIMEOUT_REASON);
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

    /** After the last result of the current round: posts the results, then finishes or prepares the next round. */
    private String closeRoundIfDone(ActiveTournament tournament) {
        if (!tournament.roundClosed()) {
            return null;
        }
        int round = tournament.currentRound();
        matches.values().removeIf(match -> match.tournamentId() == tournament.id);
        log.info("Tournament {}: round {} complete", tournament.id, round);
        announcer.post(tournament.channelId, TournamentMessages.roundResults(tournament.id, round,
                tournament.round(round), tournament.standings()), false);
        try {
            prepareNextRound(tournament);
        } catch (SQLException | RuntimeException e) {
            // The result is saved; /tournament continue (or the next startup) prepares the round again
            log.warn("Tournament {}: could not prepare the round after round {}", tournament.id, round, e);
            return TournamentMessages.roundComplete(round) + "\n" + TournamentMessages.prepareFailed(tournament.id);
        }
        return TournamentMessages.roundComplete(round);
    }

    /** For a closed round without posted next pairings: ends the tournament, or pairs and posts the next round. */
    private void prepareNextRound(ActiveTournament tournament) throws SQLException {
        if (!tournament.roundClosed() || !tournament.pending().isEmpty()) {
            return;
        }
        Standings standings = tournament.standings();
        if (standings.active().isEmpty()) {
            abandon(tournament, TournamentMessages.NO_PLAYERS_REASON);
            return;
        }
        Optional<Long> winner = WinnerRule.winner(standings, tournament.currentRound());
        if (winner.isPresent()) {
            finish(tournament, winner.get(), standings);
            return;
        }
        int next = tournament.currentRound() + 1;
        List<Pairing> pairings = SwissPairer.pair(standings, next, random);
        store.savePairings(tournament.id, next, pairings);
        tournament.addPairings(next, pairings);
        log.info("Tournament {}: round {} paired", tournament.id, next);
        announcer.post(tournament.channelId,
                TournamentMessages.nextPairings(tournament.id, next, tournament.pending()), false);
    }

    private void finish(ActiveTournament tournament, long winner, Standings standings) throws SQLException {
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
        announcer.post(tournament.channelId,
                TournamentMessages.winner(tournament.id, winner, rounds, standings, earned, failed), true);
    }

    private void abandon(ActiveTournament tournament, String reason) throws SQLException {
        store.abandon(tournament.id, clock.instant());
        forget(tournament);
        log.info("Tournament {} abandoned: {}", tournament.id, reason);
        announcer.post(tournament.channelId, TournamentMessages.abandoned(tournament.id, reason), false);
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

    /** The running tournament with this ID in this server, or null. */
    private ActiveTournament running(long tournamentId, long guildId) {
        ActiveTournament tournament = tournaments.get(tournamentId);
        return tournament != null && tournament.guildId == guildId ? tournament : null;
    }

    private String notRunning(long tournamentId, long guildId) throws SQLException {
        return store.load(tournamentId)
                .filter(record -> record.guildId() == guildId)
                .map(record -> TournamentMessages.notRunning(tournamentId, record.status()))
                .orElse(TournamentMessages.tournamentNotFound(tournamentId));
    }
}
