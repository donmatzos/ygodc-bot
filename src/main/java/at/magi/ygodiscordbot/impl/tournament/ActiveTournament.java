package at.magi.ygodiscordbot.impl.tournament;

import at.magi.ygodiscordbot.entity.tournament.MatchRecord;
import at.magi.ygodiscordbot.entity.tournament.Pairing;
import at.magi.ygodiscordbot.entity.tournament.Standings;
import at.magi.ygodiscordbot.entity.tournament.TournamentRecord;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** A running tournament in memory: the same data as in the database, changed only after a successful write. */
final class ActiveTournament {

    final long id;
    final long guildId;
    final long channelId;
    final Instant startedAt;
    private final List<Long> players;
    private final Map<Long, Integer> droppedInRound;
    private final List<MatchRecord> matches;
    private int currentRound;

    ActiveTournament(TournamentRecord record) {
        id = record.id();
        guildId = record.guildId();
        channelId = record.channelId();
        startedAt = record.startedAt();
        players = List.copyOf(record.players());
        droppedInRound = new HashMap<>(record.droppedInRound());
        matches = new ArrayList<>(record.matches());
        currentRound = record.currentRound();
    }

    int currentRound() {
        return currentRound;
    }

    void startRound(int round) {
        currentRound = round;
    }

    Standings standings() {
        return Standings.of(players, droppedInRound.keySet(), matches);
    }

    boolean hasPlayer(long player) {
        return players.contains(player);
    }

    boolean isDropped(long player) {
        return droppedInRound.containsKey(player);
    }

    boolean isActivePlayer(long player) {
        return hasPlayer(player) && !isDropped(player);
    }

    void drop(long player, int round) {
        droppedInRound.put(player, round);
    }

    List<MatchRecord> round(int round) {
        return matches.stream().filter(match -> match.round() == round).toList();
    }

    List<Long> byes(int round) {
        return round(round).stream().filter(MatchRecord::isBye).map(MatchRecord::player1).toList();
    }

    /** True once every pairing of the current round has a winner. */
    boolean roundClosed() {
        return round(currentRound).stream().allMatch(MatchRecord::isPlayed);
    }

    /** Pairings of the next round that are posted but not started yet. */
    List<MatchRecord> pending() {
        return round(currentRound + 1);
    }

    void addPairings(int round, List<Pairing> pairings) {
        pairings.forEach(pairing -> matches.add(MatchRecord.of(round, pairing)));
    }

    void removeRound(int round) {
        matches.removeIf(match -> match.round() == round);
    }

    MatchRecord record(int round, long player1) {
        return matches.stream()
                .filter(match -> match.round() == round && match.player1() == player1)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "No match of " + player1 + " in round " + round + " of tournament " + id));
    }

    void setWinner(int round, long player1, long winner) {
        MatchRecord match = record(round, player1);
        matches.set(matches.indexOf(match), match.withWinner(winner));
    }

    void setDoubleLoss(int round, long player1) {
        MatchRecord match = record(round, player1);
        matches.set(matches.indexOf(match), match.asDoubleLoss());
    }
}
