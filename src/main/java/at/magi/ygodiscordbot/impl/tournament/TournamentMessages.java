package at.magi.ygodiscordbot.impl.tournament;

import at.magi.ygodiscordbot.entity.tournament.MatchRecord;
import at.magi.ygodiscordbot.entity.tournament.Standings;
import at.magi.ygodiscordbot.entity.tournament.TournamentCode;
import at.magi.ygodiscordbot.entity.tournament.TournamentListPage;
import at.magi.ygodiscordbot.entity.tournament.TournamentStatus;
import at.magi.ygodiscordbot.entity.tournament.TournamentSummary;
import at.magi.ygodiscordbot.entity.tournament.WinnerRule;
import at.magi.ygodiscordbot.utils.discord.DcMessageUtils;
import at.magi.ygodiscordbot.utils.discord.DisplayNames;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * All tournament texts: channel posts and DMs (lists of messages ≤ 2000 chars) and command replies. Posts with tables
 * are {@link NamedText}s: their render functions only capture immutable snapshots.
 */
final class TournamentMessages {

    static final String TIMEOUT_REASON = "it was not finished within 48 hours";
    static final String CANCELLED_REASON = "an organizer cancelled it";
    static final String NO_PLAYERS_REASON = "no players are left";

    private TournamentMessages() {
    }

    static String mention(long user) {
        return "<@" + user + ">";
    }

    private static String tournament(String code) {
        return "Tournament `" + code + "`";
    }

    /** Mid-sentence form, for replies that kept their wording. */
    private static String ofTournament(String code) {
        return "tournament `" + code + "`";
    }

    static String name(Map<Long, String> names, long user) {
        return DcMessageUtils.safe(DisplayNames.nameOrUnknown(names, user));
    }

    private static String matchLine(ActiveMatch match) {
        return "`" + match.id() + "` · " + mention(match.player1()) + " vs " + mention(match.player2());
    }

    private static List<String> concat(List<String> first, List<String> second) {
        List<String> all = new ArrayList<>(first);
        all.addAll(second);
        return all;
    }

    private static Set<Long> players(Standings standings) {
        return standings.ranked().stream().map(Standings.Entry::player).collect(Collectors.toSet());
    }

    // --- Standings table (posts and /tournament standings) ---

    /** Rank / Player / W-L / OMW% code block; players only the lot separates share a rank (1, 1, 3). */
    static List<String> standingsTable(Standings standings, Map<Long, String> names) {
        List<Standings.Entry> ranked = standings.ranked();
        int width = "Player".length();
        for (Standings.Entry entry : ranked) {
            width = Math.max(width, name(names, entry.player()).length());
        }
        List<String> rows = new ArrayList<>();
        for (Standings.Entry entry : ranked) {
            rows.add(String.format("%4d  %-" + width + "s  %d-%d  %5s%s", standings.rank(entry.player()),
                    name(names, entry.player()), entry.wins(), entry.losses(),
                    percent(standings.tieBreaks(entry.player()).omw()), entry.dropped() ? " (dropped)" : ""));
        }
        String header = String.format("%-4s  %-" + width + "s  %s  %5s", "Rank", "Player", "W-L", "OMW%") + "\n"
                + "----  " + "-".repeat(width) + "  ---  -----";
        return DcMessageUtils.packTables("", List.of(
                new DcMessageUtils.Section("**Standings**", "**Standings** (continued)", header, rows)));
    }

    /** A rate as a percentage with one decimal, the precision the tie-breakers are compared at. */
    private static String percent(double rate) {
        long permille = Standings.permille(rate);
        return permille / 10 + "." + permille % 10;
    }

    /** How the winner got ahead of the next active player with as few losses; null if nobody had as few. */
    private static String tieLine(long winner, Standings standings) {
        List<Long> active = standings.activeRanked();
        if (active.size() < 2 || active.get(0) != winner) {
            return null;
        }
        Standings.Entry first = standings.entry(winner);
        Standings.Entry runnerUp = standings.entry(active.get(1));
        if (runnerUp.losses() != first.losses()) {
            return null;
        }
        Standings.TieBreaks mine = standings.tieBreaks(winner);
        Standings.TieBreaks theirs = standings.tieBreaks(runnerUp.player());
        String reason = switch (standings.decidedBy(winner, runnerUp.player())) {
            case RECORD -> "decided by more wins (" + first.wins() + " vs " + runnerUp.wins() + ")";
            case OMW -> "decided by opponents' win rate (" + percent(mine.omw()) + "% vs "
                    + percent(theirs.omw()) + "%)";
            case OOMW -> "decided by opponents' opponents' win rate (" + percent(mine.oomw()) + "% vs "
                    + percent(theirs.oomw()) + "%)";
            case HEAD_TO_HEAD -> "decided by head-to-head (" + mention(winner) + " beat "
                    + mention(runnerUp.player()) + ")";
            case LOT -> "all tie-breakers equal, decided by lot";
        };
        return "Tied on " + first.losses() + (first.losses() == 1 ? " loss" : " losses") + " with "
                + mention(runnerUp.player()) + ", " + reason + ".";
    }

    // --- Channel posts ---

    private static List<String> matchupLines(List<ActiveMatch> matches, List<Long> byes) {
        List<String> lines = new ArrayList<>();
        matches.forEach(match -> lines.add(matchLine(match)));
        byes.forEach(bye -> lines.add(mention(bye) + " gets a free win this round"));
        lines.add("Report your result with `/match finish id:<match ID> winner:<@player>`, or with "
                + "`/match doubleloss id:<match ID>` if time ran out without a winner (both players lose). "
                + "Results are sent to both players by DM; the channel gets the full round.");
        return lines;
    }

    /** The start post. */
    static NamedText announced(String code, LocalDate day, List<ActiveMatch> matches, List<Long> byes) {
        return NamedText.plain(DcMessageUtils.packLines("## 🏁 " + tournament(code) + " · " + day + " · Round 1",
                matchupLines(matches, byes)));
    }

    /** The continue post: the round's matchups, then the standings. */
    static NamedText roundStart(String code, int round, List<ActiveMatch> matches, List<Long> byes,
                                Standings standings, List<Long> playOff) {
        List<String> lines = new ArrayList<>();
        addPlayOffLine(lines, round, playOff);
        lines.addAll(matchupLines(matches, byes));
        List<String> head = DcMessageUtils.packLines("## 🏁 " + tournament(code) + " · Round " + round, lines);
        return new NamedText(players(standings), names -> concat(head, standingsTable(standings, names)));
    }

    /** @param standings the table under the results, or null when a winner post with the final table follows */
    static NamedText roundResults(String code, int round, List<MatchRecord> results, Standings standings) {
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
        List<String> head = DcMessageUtils.packLines("## 📋 " + tournament(code) + " · Round " + round + " results",
                lines);
        if (standings == null) {
            return NamedText.plain(head);
        }
        return new NamedText(players(standings), names -> concat(head, standingsTable(standings, names)));
    }

    /** @param playOff the tied leaders if this is a {@link WinnerRule#playOff} round, else empty */
    static NamedText nextPairings(String code, int round, List<MatchRecord> pending, List<Long> playOff) {
        List<String> lines = new ArrayList<>();
        addPlayOffLine(lines, round, playOff);
        for (MatchRecord pairing : pending) {
            lines.add(pairing.isBye()
                    ? mention(pairing.player1()) + " gets a free win"
                    : mention(pairing.player1()) + " vs " + mention(pairing.player2()));
        }
        lines.add("An organizer starts round " + round + " with `/tournament continue id:" + code + "`.");
        return NamedText.plain(DcMessageUtils.packLines("## ⏭️ " + tournament(code) + " · Round " + round
                + " pairings", lines));
    }

    private static void addPlayOffLine(List<String> lines, int round, List<Long> playOff) {
        if (playOff.isEmpty()) {
            return;
        }
        List<String> mentions = playOff.stream().map(TournamentMessages::mention).toList();
        String players = String.join(", ", mentions.subList(0, mentions.size() - 1)) + " and "
                + mentions.get(mentions.size() - 1);
        lines.add("⚔️ Play-off: " + players + " are still tied after " + (round - 1)
                + " rounds, so only they play this round (rematches allowed).");
    }

    /** Always carries the final table: it is also posted without round results before it (drop, restart). */
    static NamedText winner(String code, long winner, int rounds, Standings standings, Map<Long, Long> points,
                            List<Long> failed) {
        List<String> lines = new ArrayList<>();
        lines.add("🏆 " + mention(winner) + " wins after " + rounds + (rounds == 1 ? " round!" : " rounds!"));
        String tie = tieLine(winner, standings);
        if (tie != null) {
            lines.add(tie);
        }
        if (!points.isEmpty()) {
            lines.add("");
            lines.add("**Leaderboard points**");
            points.forEach((player, earned) -> lines.add(mention(player) + " +" + earned
                    + (failed.contains(player) ? " ⚠️ not saved, an organizer has to add them with `/points add`" : "")));
        }
        List<String> head = DcMessageUtils.packLines("## 🎉 " + tournament(code) + " finished", lines);
        return new NamedText(players(standings), names -> concat(head, standingsTable(standings, names)));
    }

    static NamedText abandoned(String code, String reason) {
        return NamedText.plain("## " + tournament(code) + " abandoned\nIt ended without a winner because " + reason
                + ". No points were awarded.");
    }

    /** {@code /tournament standings}: state, open matches, then the table. */
    static NamedText standings(String code, TournamentStatus status, int round, Standings standings,
                               List<ActiveMatch> open, Long winner) {
        String state = switch (status) {
            case RUNNING -> "Round " + round;
            case FINISHED -> "Finished, winner " + mention(winner);
            case ABANDONED -> "Abandoned";
        };
        List<String> openLines = new ArrayList<>();
        if (!open.isEmpty()) {
            openLines.add("");
            openLines.add("**Open matches**");
            open.forEach(match -> openLines.add(matchLine(match)));
        }
        List<String> head = DcMessageUtils.packLines("## " + tournament(code) + " · " + state, openLines);
        return new NamedText(players(standings), names -> concat(head, standingsTable(standings, names)));
    }

    // --- /tournament list ---

    static NamedText list(TournamentListPage page, LocalDate day) {
        List<TournamentSummary> summaries = List.copyOf(page.rows());
        Set<Long> winners = summaries.stream().map(TournamentSummary::winner).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        String summary = (day == null ? "" : TournamentCode.day(day) + " · ") + "Page " + page.page() + " of "
                + page.pageCount() + " · " + page.total() + (page.total() == 1 ? " tournament" : " tournaments");
        return new NamedText(winners, names -> {
            List<String> rows = new ArrayList<>();
            for (TournamentSummary row : summaries) {
                String winner = switch (row.status()) {
                    case FINISHED -> name(names, row.winner());
                    case RUNNING -> "(running)";
                    case ABANDONED -> "(abandoned)";
                };
                rows.add(String.format("%-18s  %-10s  %s", row.code(), row.playedOn(), winner));
            }
            String header = String.format("%-18s  %-10s  %s", "Tournament ID", "Date", "Winner") + "\n"
                    + "-".repeat(18) + "  " + "-".repeat(10) + "  ------";
            return DcMessageUtils.packTables("## 🏆 Tournaments",
                    List.of(new DcMessageUtils.Section(summary, summary + " (continued)", header, rows)));
        });
    }

    static String listEmpty(LocalDate day) {
        return day == null ? "No tournaments in this server yet." : "No tournaments on " + TournamentCode.day(day) + ".";
    }

    static String listPageOutOfRange(TournamentListPage page) {
        return "Page " + page.page() + " does not exist, there " + (page.pageCount() == 1 ? "is 1 page." : "are "
                + page.pageCount() + " pages.");
    }

    // --- DMs ---

    static List<String> matchResultDm(String code, int round, int matchId, MatchRecord result, boolean corrected) {
        String outcome = result.doubleLoss()
                ? mention(result.player1()) + " and " + mention(result.player2()) + " both lose (time limit)"
                : mention(result.winner()) + " beat " + mention(result.loser());
        return List.of("🎴 " + tournament(code) + " · Round " + round + " · Match `" + matchId + "`\n" + outcome
                + (corrected ? " (corrected by an organizer)" : "") + ".\nThe round results are posted in the "
                + "tournament channel once every match is done.");
    }

    static List<String> droppedDm(String code) {
        return List.of("➖ You dropped out of " + tournament(code) + ".");
    }

    static List<String> forfeitWinDm(String code, long dropped, int matchId) {
        return List.of("✅ " + mention(dropped) + " dropped out of " + tournament(code) + ", you win match `"
                + matchId + "`.");
    }

    static List<String> newMatchIdDm(String code, int round, ActiveMatch match) {
        return List.of("🔄 The bot restarted. Your match in " + tournament(code) + " · Round " + round + " ("
                + mention(match.player1()) + " vs " + mention(match.player2()) + ") now has the ID `" + match.id()
                + "`. Report it with `/match finish id:" + match.id() + "`.");
    }

    // --- Replies ---

    static String started(String code, int players) {
        return "✅ " + tournament(code) + " started with " + players + " players. Round 1 is posted in this channel.";
    }

    static String playerCount(int count) {
        return "❌ A tournament needs " + TournamentService.MIN_PLAYERS + "–" + TournamentService.MAX_PLAYERS
                + " players, you listed " + count + ".";
    }

    static String tooManyRunning(int max) {
        return "❌ This server already has " + max + " running tournaments. Finish or cancel one first.";
    }

    static String alreadyPlaying(List<Long> players) {
        return "❌ Already playing in a running tournament: "
                + players.stream().map(TournamentMessages::mention).collect(Collectors.joining(", ")) + ".";
    }

    static String matchNotFound(int matchId) {
        return "❌ There is no open match `" + matchId + "`. Match IDs change when the bot restarts, "
                + "the bot sends the new ones to both players by DM, or ask an organizer (`/tournament standings`).";
    }

    static String notYourMatch(String code, int matchId) {
        return "❌ " + tournament(code) + " · You are not playing in match `" + matchId + "`. An organizer can use `/match-admin finish`.";
    }

    static String winnerNotInMatch(String code, int matchId, long winner) {
        return "❌ " + tournament(code) + " · " + mention(winner) + " is not playing in match `" + matchId + "`.";
    }

    static String alreadyFinished(String code, int matchId, MatchRecord result) {
        String outcome = result.doubleLoss() ? "as a double loss" : "and " + mention(result.winner()) + " won";
        return tournament(code) + " · Match `" + matchId + "` is already finished " + outcome
                + ". An organizer can correct it with `/match-admin`.";
    }

    static String matchFinished(String code, int matchId, long winner, boolean corrected) {
        return "✅ " + tournament(code) + " · Match `" + matchId + "`: " + mention(winner)
                + (corrected ? " is now the winner." : " wins.") + " Both players were sent a DM.";
    }

    static String doubleLossRecorded(String code, int matchId, long player1, long player2, boolean corrected) {
        return "✅ " + tournament(code) + " · Match `" + matchId + "`: "
                + (corrected ? "now a double loss, " : "double loss, ")
                + mention(player1) + " and " + mention(player2) + " both lose. Both players were sent a DM.";
    }

    static String roundComplete(String code, int round) {
        return "Round " + round + " of " + tournament(code)
                + " is complete, the results are posted in the tournament channel.";
    }

    static String prepareFailed(String code) {
        return "⚠️ The next round could not be prepared. Try again with `/tournament continue id:" + code + "`.";
    }

    static String invalidCode(String raw) {
        String shown = raw.length() > 30 ? raw.substring(0, 30) + "…" : raw;
        return "❌ `" + DcMessageUtils.safe(shown) + "` is not a tournament ID. IDs look like `" + TournamentCode.EXAMPLE
                + "` and are in every tournament post.";
    }

    static String tournamentNotFound(String code) {
        return "❌ There is no " + ofTournament(code) + " in this server.";
    }

    static String notRunning(String code, TournamentStatus status) {
        return "❌ " + tournament(code) + " is already " + status.name().toLowerCase(Locale.ROOT) + ".";
    }

    static String roundStillOpen(String code, int round, int open) {
        return "❌ Round " + round + " of " + ofTournament(code) + " still has " + open
                + (open == 1 ? " open match." : " open matches.");
    }

    static String roundStarted(String code, int round) {
        return "✅ Round " + round + " of " + ofTournament(code) + " started.";
    }

    static String endedInsteadOfContinue(String code) {
        return tournament(code) + " is over, see the tournament channel.";
    }

    static String cancelled(String code) {
        return "✅ " + tournament(code) + " was cancelled.";
    }

    static String notAPlayer(String code, long player) {
        return "❌ " + mention(player) + " is not playing in " + ofTournament(code) + ".";
    }

    static String alreadyDropped(String code, long player) {
        return "❌ " + mention(player) + " already dropped out of " + ofTournament(code) + ".";
    }

    static String dropped(String code, long player) {
        return "✅ " + mention(player) + " dropped out of " + ofTournament(code) + ".";
    }

    static String repaired(int round) {
        return "The pairings for round " + round + " were made again without them.";
    }
}
