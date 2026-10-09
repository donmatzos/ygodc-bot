package at.magi.ygodiscordbot.impl.tournament;

import at.magi.ygodiscordbot.entity.tournament.MatchRecord;
import at.magi.ygodiscordbot.entity.tournament.Standings;
import at.magi.ygodiscordbot.entity.tournament.TournamentStatus;
import at.magi.ygodiscordbot.utils.discord.DcMessageUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/** All tournament texts: channel posts (lists of messages ≤ 2000 chars) and command replies. */
final class TournamentMessages {

    static final String TIMEOUT_REASON = "it was not finished within 48 hours";
    static final String CANCELLED_REASON = "an organizer cancelled it";
    static final String NO_PLAYERS_REASON = "no players are left";

    private TournamentMessages() {
    }

    static String mention(long user) {
        return "<@" + user + ">";
    }

    private static String matchLine(ActiveMatch match) {
        return "`" + match.id() + "` · " + mention(match.player1()) + " vs " + mention(match.player2());
    }

    // --- Channel posts ---

    static List<String> roundStart(long id, int round, List<ActiveMatch> matches, List<Long> byes) {
        List<String> lines = new ArrayList<>();
        matches.forEach(match -> lines.add(matchLine(match)));
        byes.forEach(bye -> lines.add(mention(bye) + " gets a free win this round"));
        lines.add("Report your result with `/match finish id:<match ID> winner:<@player>`, or with "
                + "`/match doubleloss id:<match ID>` if time ran out without a winner (both players lose).");
        return DcMessageUtils.packLines("## 🏁 Tournament #" + id + " · Round " + round, lines);
    }

    static List<String> restarted(long id, int round, List<ActiveMatch> open) {
        List<String> lines = new ArrayList<>();
        open.forEach(match -> lines.add(matchLine(match)));
        lines.add("Use these new match IDs with `/match finish`; the old ones no longer work.");
        return DcMessageUtils.packLines("## 🔄 Tournament #" + id + " · Round " + round
                + "\nThe bot restarted, so the open matches got new IDs:", lines);
    }

    static List<String> roundResults(long id, int round, List<MatchRecord> results, Standings standings) {
        List<String> lines = new ArrayList<>();
        for (MatchRecord result : results) {
            if (result.isBye()) {
                lines.add(mention(result.player1()) + " had a free win");
            } else if (result.doubleLoss()) {
                lines.add(mention(result.player1()) + " and " + mention(result.player2()) + " both lose (time limit)");
            } else {
                lines.add(mention(result.winner()) + " beat " + mention(result.loser()));
            }
        }
        lines.add("");
        lines.add("**Standings**");
        lines.addAll(standingsLines(standings));
        return DcMessageUtils.packLines("## 📋 Tournament #" + id + " · Round " + round + " results", lines);
    }

    static List<String> nextPairings(long id, int round, List<MatchRecord> pending) {
        List<String> lines = new ArrayList<>();
        for (MatchRecord pairing : pending) {
            lines.add(pairing.isBye()
                    ? mention(pairing.player1()) + " gets a free win"
                    : mention(pairing.player1()) + " vs " + mention(pairing.player2()));
        }
        lines.add("An organizer starts round " + round + " with `/tournament continue id:" + id + "`.");
        return DcMessageUtils.packLines("## ⏭️ Tournament #" + id + " · Round " + round + " pairings", lines);
    }

    static List<String> winner(long id, long winner, int rounds, Standings standings, Map<Long, Long> points,
                               List<Long> failed) {
        List<String> lines = new ArrayList<>();
        lines.add("🏆 " + mention(winner) + " wins after " + rounds + (rounds == 1 ? " round!" : " rounds!"));
        lines.add("");
        lines.add("**Final standings**");
        lines.addAll(standingsLines(standings));
        if (!points.isEmpty()) {
            lines.add("");
            lines.add("**Leaderboard points**");
            points.forEach((player, earned) -> lines.add(mention(player) + " +" + earned
                    + (failed.contains(player) ? " ⚠️ not saved, an organizer has to add them with `/points add`" : "")));
        }
        return DcMessageUtils.packLines("## 🎉 Tournament #" + id + " finished", lines);
    }

    static List<String> abandoned(long id, String reason) {
        return List.of("## Tournament #" + id + " abandoned\nIt ended without a winner because " + reason
                + ". No points were awarded.");
    }

    static String droppedPost(long id, long player, Integer matchId, Long opponent) {
        return "➖ " + mention(player) + " dropped out of tournament #" + id + "."
                + (matchId == null ? "" : " " + mention(opponent) + " wins match `" + matchId + "`.");
    }

    // --- Standings (post and /tournament standings) ---

    /** "1. <@id> · 2-0", equal records share a rank (1, 1, 3). */
    static List<String> standingsLines(Standings standings) {
        List<String> lines = new ArrayList<>();
        List<Standings.Entry> ranked = standings.ranked();
        int rank = 0;
        for (int i = 0; i < ranked.size(); i++) {
            Standings.Entry entry = ranked.get(i);
            Standings.Entry previous = i == 0 ? null : ranked.get(i - 1);
            if (previous == null || previous.wins() != entry.wins() || previous.losses() != entry.losses()) {
                rank = i + 1;
            }
            lines.add(rank + ". " + mention(entry.player()) + " · " + entry.wins() + "-" + entry.losses()
                    + (entry.dropped() ? " (dropped)" : ""));
        }
        return lines;
    }

    static List<String> standings(long id, TournamentStatus status, int round, Standings standings,
                                  List<ActiveMatch> open, Long winner) {
        String state = switch (status) {
            case RUNNING -> "Round " + round;
            case FINISHED -> "Finished, winner " + mention(winner);
            case ABANDONED -> "Abandoned";
        };
        List<String> lines = new ArrayList<>(standingsLines(standings));
        if (!open.isEmpty()) {
            lines.add("");
            lines.add("**Open matches**");
            open.forEach(match -> lines.add(matchLine(match)));
        }
        return DcMessageUtils.packLines("## Tournament #" + id + " · " + state, lines);
    }

    // --- Replies ---

    static String started(long id, int players) {
        return "✅ Tournament #" + id + " started with " + players + " players. Round 1 is posted in this channel.";
    }

    static String playerCount(int count) {
        return "❌ A tournament needs " + TournamentService.MIN_PLAYERS + "–" + TournamentService.MAX_PLAYERS
                + " players, you listed " + count + ".";
    }

    static String alreadyPlaying(List<Long> players) {
        return "❌ Already playing in a running tournament: "
                + players.stream().map(TournamentMessages::mention).collect(Collectors.joining(", ")) + ".";
    }

    static String matchNotFound(int matchId) {
        return "❌ There is no open match `" + matchId + "`. Match IDs change when the bot restarts, "
                + "check the tournament channel.";
    }

    static String notYourMatch(int matchId) {
        return "❌ You are not playing in match `" + matchId + "`. An organizer can use `/match-admin finish`.";
    }

    static String winnerNotInMatch(int matchId, long winner) {
        return "❌ " + mention(winner) + " is not playing in match `" + matchId + "`.";
    }

    static String alreadyFinished(int matchId, MatchRecord result) {
        String outcome = result.doubleLoss() ? "as a double loss" : "and " + mention(result.winner()) + " won";
        return "Match `" + matchId + "` is already finished " + outcome
                + ". An organizer can correct it with `/match-admin`.";
    }

    static String matchFinished(int matchId, long winner, boolean corrected) {
        return "✅ Match `" + matchId + "`: " + mention(winner) + (corrected ? " is now the winner." : " wins.");
    }

    static String doubleLossRecorded(int matchId, long player1, long player2, boolean corrected) {
        return "✅ Match `" + matchId + "`: " + (corrected ? "now a double loss, " : "double loss, ")
                + mention(player1) + " and " + mention(player2) + " both lose.";
    }

    static String roundComplete(int round) {
        return "Round " + round + " is complete, the results are posted in the tournament channel.";
    }

    static String prepareFailed(long id) {
        return "⚠️ The next round could not be prepared. Try again with `/tournament continue id:" + id + "`.";
    }

    static String tournamentNotFound(long id) {
        return "❌ There is no tournament #" + id + " in this server.";
    }

    static String notRunning(long id, TournamentStatus status) {
        return "❌ Tournament #" + id + " is already " + status.name().toLowerCase(Locale.ROOT) + ".";
    }

    static String roundStillOpen(long id, int round, int open) {
        return "❌ Round " + round + " of tournament #" + id + " still has " + open
                + (open == 1 ? " open match." : " open matches.");
    }

    static String roundStarted(long id, int round) {
        return "✅ Round " + round + " of tournament #" + id + " started.";
    }

    static String endedInsteadOfContinue(long id) {
        return "Tournament #" + id + " is over, see the tournament channel.";
    }

    static String cancelled(long id) {
        return "✅ Tournament #" + id + " was cancelled.";
    }

    static String notAPlayer(long id, long player) {
        return "❌ " + mention(player) + " is not playing in tournament #" + id + ".";
    }

    static String alreadyDropped(long id, long player) {
        return "❌ " + mention(player) + " already dropped out of tournament #" + id + ".";
    }

    static String dropped(long id, long player) {
        return "✅ " + mention(player) + " dropped out of tournament #" + id + ".";
    }

    static String repaired(int round) {
        return "The pairings for round " + round + " were made again without them.";
    }
}
