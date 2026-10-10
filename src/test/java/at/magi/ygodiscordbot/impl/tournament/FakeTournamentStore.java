package at.magi.ygodiscordbot.impl.tournament;

import at.magi.ygodiscordbot.entity.tournament.MatchRecord;
import at.magi.ygodiscordbot.entity.tournament.NewTournament;
import at.magi.ygodiscordbot.entity.tournament.Pairing;
import at.magi.ygodiscordbot.entity.tournament.TournamentListPage;
import at.magi.ygodiscordbot.entity.tournament.TournamentRecord;
import at.magi.ygodiscordbot.entity.tournament.TournamentStatus;
import at.magi.ygodiscordbot.entity.tournament.TournamentSummary;

import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
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
    /** When ≥ 0: that many more writes succeed, then the database is down. */
    int writesUntilFailure = -1;

    /** The next this-many creates are refused as duplicate codes (recorded in {@link #rejectedCodes}). */
    int collisions;
    final List<String> rejectedCodes = new ArrayList<>();

    long lastId() {
        return nextId - 1;
    }

    private void write() throws SQLException {
        if (failWrites || writesUntilFailure == 0) {
            throw new SQLException("database down");
        }
        if (writesUntilFailure > 0) {
            writesUntilFailure--;
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
        if (collisions > 0 || rows.values().stream().anyMatch(existing -> existing.tournament.code().equals(tournament.code()))) {
            if (collisions > 0) {
                collisions--;
            }
            rejectedCodes.add(tournament.code());
            throw new SQLIntegrityConstraintViolationException("Duplicate code");
        }
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
    public void drop(long tournamentId, long player, int round, MatchRecord forfeit) throws SQLException {
        write(); // one transaction: one write
        Row row = row(tournamentId);
        if (forfeit != null) {
            MatchRecord open = row.matches.stream()
                    .filter(match -> match.round() == forfeit.round() && match.player1() == forfeit.player1())
                    .findFirst()
                    .orElseThrow(() -> new SQLException("No match of " + forfeit.player1()));
            row.matches.set(row.matches.indexOf(open), forfeit);
        }
        row.dropped.put(player, round);
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

    @Override
    public Optional<TournamentRecord> loadByCode(String code) {
        return rows.entrySet().stream()
                .filter(entry -> entry.getValue().tournament.code().equals(code))
                .findFirst()
                .map(entry -> snapshot(entry.getKey(), entry.getValue()));
    }

    @Override
    public TournamentListPage list(long guildId, LocalDate day, int page) {
        List<Long> ids = new ArrayList<>(rows.keySet());
        Collections.reverse(ids); // newest id first, like ORDER BY id DESC
        List<TournamentSummary> all = new ArrayList<>();
        for (long id : ids) {
            Row row = rows.get(id);
            if (row.tournament.guildId() == guildId && (day == null || row.tournament.playedOn().equals(day))) {
                all.add(new TournamentSummary(row.tournament.code(), row.tournament.playedOn(), row.status, row.winner));
            }
        }
        all.sort(Comparator.comparing(TournamentSummary::playedOn).reversed()); // stable: ties keep id order
        int from = Math.min(all.size(), (page - 1) * TournamentListPage.PAGE_SIZE);
        int to = Math.min(all.size(), from + TournamentListPage.PAGE_SIZE);
        return new TournamentListPage(page, TournamentListPage.pageCount(all.size()), all.size(),
                List.copyOf(all.subList(from, to)));
    }

    private static TournamentRecord snapshot(long id, Row row) {
        NewTournament t = row.tournament;
        return new TournamentRecord(id, t.code(), t.playedOn(), t.guildId(), t.channelId(), t.createdBy(), row.status, row.winner,
                t.startedAt(), row.finishedAt, row.currentRound, List.copyOf(t.players()), Map.copyOf(row.dropped),
                List.copyOf(row.matches));
    }
}
