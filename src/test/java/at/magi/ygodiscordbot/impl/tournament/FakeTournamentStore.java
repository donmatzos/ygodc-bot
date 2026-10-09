package at.magi.ygodiscordbot.impl.tournament;

import at.magi.ygodiscordbot.entity.tournament.MatchRecord;
import at.magi.ygodiscordbot.entity.tournament.NewTournament;
import at.magi.ygodiscordbot.entity.tournament.Pairing;
import at.magi.ygodiscordbot.entity.tournament.TournamentRecord;
import at.magi.ygodiscordbot.entity.tournament.TournamentStatus;

import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** In-memory {@link TournamentStore} with the same rules as the JDBC one; can simulate a database that is down. */
final class FakeTournamentStore implements TournamentStore {

    private static final class Row {
        final NewTournament tournament;
        TournamentStatus status = TournamentStatus.RUNNING;
        Long winner;
        Instant finishedAt;
        int currentRound = 1;
        final Map<Long, Integer> dropped = new HashMap<>();
        final List<MatchRecord> matches = new ArrayList<>();

        Row(NewTournament tournament) {
            this.tournament = tournament;
        }
    }

    private final Map<Long, Row> rows = new LinkedHashMap<>();
    private long nextId = 1;
    /** When true, every write fails like a database that is down. */
    boolean failWrites;

    long lastId() {
        return nextId - 1;
    }

    private void write() throws SQLException {
        if (failWrites) {
            throw new SQLException("database down");
        }
    }

    private Row row(long id) throws SQLException {
        Row row = rows.get(id);
        if (row == null) {
            throw new SQLException("No tournament " + id);
        }
        return row;
    }

    private Row running(long id) throws SQLException {
        Row row = row(id);
        if (row.status != TournamentStatus.RUNNING) {
            throw new SQLException("Tournament " + id + " is not running");
        }
        return row;
    }

    @Override
    public long create(NewTournament tournament, List<Pairing> round1) throws SQLException {
        write();
        Row row = new Row(tournament);
        round1.forEach(pairing -> row.matches.add(MatchRecord.of(1, pairing)));
        long id = nextId++;
        rows.put(id, row);
        return id;
    }

    @Override
    public void savePairings(long tournamentId, int round, List<Pairing> pairings) throws SQLException {
        write();
        Row row = row(tournamentId);
        pairings.forEach(pairing -> row.matches.add(MatchRecord.of(round, pairing)));
    }

    @Override
    public void deletePairings(long tournamentId, int round) throws SQLException {
        write();
        row(tournamentId).matches.removeIf(match -> match.round() == round);
    }

    @Override
    public void startRound(long tournamentId, int round) throws SQLException {
        write();
        running(tournamentId).currentRound = round;
    }

    @Override
    public void recordWinner(long tournamentId, int round, long player1, long winner) throws SQLException {
        write();
        List<MatchRecord> matches = row(tournamentId).matches;
        for (int i = 0; i < matches.size(); i++) {
            MatchRecord match = matches.get(i);
            if (match.round() == round && match.player1() == player1) {
                matches.set(i, match.withWinner(winner));
                return;
            }
        }
        throw new SQLException("No match of " + player1 + " in round " + round);
    }

    @Override
    public void recordDoubleLoss(long tournamentId, int round, long player1) throws SQLException {
        write();
        List<MatchRecord> matches = row(tournamentId).matches;
        for (int i = 0; i < matches.size(); i++) {
            MatchRecord match = matches.get(i);
            if (match.round() == round && match.player1() == player1 && !match.isBye()) {
                matches.set(i, match.asDoubleLoss());
                return;
            }
        }
        throw new SQLException("No match of " + player1 + " in round " + round);
    }

    @Override
    public void drop(long tournamentId, long player, int round) throws SQLException {
        write();
        row(tournamentId).dropped.put(player, round);
    }

    @Override
    public void finish(long tournamentId, long winner, Instant at) throws SQLException {
        write();
        Row row = running(tournamentId);
        row.status = TournamentStatus.FINISHED;
        row.winner = winner;
        row.finishedAt = at;
    }

    @Override
    public void abandon(long tournamentId, Instant at) throws SQLException {
        write();
        Row row = running(tournamentId);
        row.status = TournamentStatus.ABANDONED;
        row.finishedAt = at;
    }

    @Override
    public Optional<TournamentRecord> load(long tournamentId) {
        Row row = rows.get(tournamentId);
        return row == null ? Optional.empty() : Optional.of(snapshot(tournamentId, row));
    }

    @Override
    public List<TournamentRecord> loadRunning() {
        List<TournamentRecord> running = new ArrayList<>();
        rows.forEach((id, row) -> {
            if (row.status == TournamentStatus.RUNNING) {
                running.add(snapshot(id, row));
            }
        });
        return running;
    }

    private static TournamentRecord snapshot(long id, Row row) {
        NewTournament t = row.tournament;
        return new TournamentRecord(id, t.guildId(), t.channelId(), t.createdBy(), row.status, row.winner,
                t.startedAt(), row.finishedAt, row.currentRound, List.copyOf(t.players()), Map.copyOf(row.dropped),
                List.copyOf(row.matches));
    }
}
