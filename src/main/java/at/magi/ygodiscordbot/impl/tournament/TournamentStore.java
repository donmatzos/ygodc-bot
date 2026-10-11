package at.magi.ygodiscordbot.impl.tournament;

import at.magi.ygodiscordbot.entity.tournament.MatchRecord;
import at.magi.ygodiscordbot.entity.tournament.NewTournament;
import at.magi.ygodiscordbot.entity.tournament.Pairing;
import at.magi.ygodiscordbot.entity.tournament.TournamentListPage;
import at.magi.ygodiscordbot.entity.tournament.TournamentRecord;

import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** Where tournaments, their players and their match results are stored. Calls block. */
public interface TournamentStore {

    /** Creates a RUNNING tournament with its players and the round-1 pairings (round 1 started); returns its ID. */
    long create(NewTournament tournament, List<Pairing> round1) throws SQLException;

    /** Stores the pairings of a round; byes are stored as already won. */
    void savePairings(long tournamentId, int round, List<Pairing> pairings) throws SQLException;

    /** Marks {@code round} as started (its pairings become matches); throws {@link TournamentNotRunningException} if it is not running. */
    void startRound(long tournamentId, int round) throws SQLException;

    /** Sets or overwrites the winner of the pairing whose first player is {@code player1}; fails if there is none. */
    void recordWinner(long tournamentId, int round, long player1, long winner) throws SQLException;

    /** Sets or overwrites the result of that pairing to a double loss; fails if there is none or it is a bye. */
    void recordDoubleLoss(long tournamentId, int round, long player1) throws SQLException;

    /**
     * Marks the player as dropped and, in the same transaction, stores {@code forfeit} (their open match, won by the
     * opponent) if it is not null and, if {@code deletePendingRound}, the prepared pairings of round + 1: all are
     * saved or none.
     */
    void drop(long tournamentId, long player, int round, MatchRecord forfeit, boolean deletePendingRound)
            throws SQLException;

    /** RUNNING → FINISHED with the winner; throws {@link TournamentNotRunningException} if it is not running. */
    void finish(long tournamentId, long winner, Instant at) throws SQLException;

    /** RUNNING → ABANDONED; throws {@link TournamentNotRunningException} if it is not running. */
    void abandon(long tournamentId, Instant at) throws SQLException;

    Optional<TournamentRecord> load(long tournamentId) throws SQLException;

    List<TournamentRecord> loadRunning() throws SQLException;

    /** The tournament with that public code, if any. */
    Optional<TournamentRecord> loadByCode(String code) throws SQLException;

    /**
     * A server's tournaments, most recent first. {@code day} null = all days; {@code page} is 1-based and may be past
     * the last page (then the page has no rows).
     */
    TournamentListPage list(long guildId, LocalDate day, int page) throws SQLException;
}
