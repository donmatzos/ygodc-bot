package at.magi.ygodiscordbot.impl.leaderboard;

import at.magi.ygodiscordbot.entity.leaderboard.LeaderboardPage;
import at.magi.ygodiscordbot.entity.leaderboard.PointChange;
import at.magi.ygodiscordbot.entity.leaderboard.RankedPlayer;
import at.magi.ygodiscordbot.utils.discord.DcMessageUtils.Section;
import at.magi.ygodiscordbot.utils.discord.DcMessageUtils;
import net.dv8tion.jda.api.utils.MarkdownSanitizer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Renders a leaderboard page as a Rank / Player / Points table, split like every other bot output. */
public final class LeaderboardMessages {

    private static final String PLAYER = "Player";

    private LeaderboardMessages() {
    }

    /**
     * @param title markdown heading text, e.g. "🏆 Leaderboard"
     * @param names display names by user ID; missing IDs are shown as "Unknown user (id)"
     */
    public static List<String> page(String title, LeaderboardPage page, Map<Long, String> names) {
        List<RankedPlayer> players = page.rows();
        List<String> playerNames = new ArrayList<>(players.size());
        int width = PLAYER.length();
        for (RankedPlayer player : players) {
            String name = DcMessageUtils.safe(
                    names.getOrDefault(player.userId(), "Unknown user (" + player.userId() + ")"));
            playerNames.add(name);
            width = Math.max(width, name.length());
        }

        String rowFormat = "%4d  %-" + width + "s  %6d";
        List<String> rows = new ArrayList<>(players.size());
        for (int i = 0; i < players.size(); i++) {
            rows.add(String.format(rowFormat, players.get(i).rank(), playerNames.get(i), players.get(i).points()));
        }
        String tableHeader = String.format("%-4s  %-" + width + "s  %6s", "Rank", PLAYER, "Points") + "\n"
                + "----  " + "-".repeat(width) + "  ------";
        String summary = "Page " + page.page() + " of " + page.pageCount() + " · " + page.totalPlayers()
                + (page.totalPlayers() == 1 ? " player" : " players");
        return DcMessageUtils.packTables("## " + title,
                List.of(new Section(summary, summary + " (continued)", tableHeader, rows)));
    }

    public static String empty() {
        return "No players on the leaderboard yet.";
    }

    public static String pageOutOfRange(LeaderboardPage page) {
        return "Page " + page.page() + " does not exist, the leaderboard has " + page.pageCount()
                + (page.pageCount() == 1 ? " page." : " pages.");
    }

    public static String playerPoints(String name, RankedPlayer player) {
        return bold(name) + " has " + points(player.points()) + " (rank " + player.rank() + ").";
    }

    public static String notOnBoard(String name) {
        return bold(name) + " is not on the leaderboard.";
    }

    public static String added(String name) {
        return "✅ Added " + bold(name) + " to the leaderboard with 0 points.";
    }

    public static String alreadyOnBoard(String name) {
        return bold(name) + " is already on the leaderboard.";
    }

    public static String pointsSet(String name, PointChange change) {
        return "✅ " + bold(name) + " now has " + points(change.after()) + " (was " + change.before() + ").";
    }

    /** @param requested the asked change: positive for /points add, negative for /points remove */
    public static String pointsChanged(String name, PointChange change, long requested) {
        if (change.created()) {
            return "✅ Added " + bold(name) + " to the leaderboard with " + points(change.after()) + ".";
        }
        long applied = Math.abs(change.applied());
        long asked = Math.abs(requested);
        boolean adding = requested > 0;
        String amount = applied == asked ? points(asked) : applied + " of " + points(asked);
        String limit = applied == asked ? "" : adding ? " (maximum reached)" : " (stopped at 0)";
        return "✅ " + (adding ? "Added " : "Removed ") + amount + (adding ? " to " : " from ") + bold(name) + limit
                + ": " + change.before() + " → " + change.after() + ".";
    }

    private static String bold(String name) {
        return "**" + MarkdownSanitizer.escape(name) + "**";
    }

    private static String points(long points) {
        return points + (points == 1 ? " point" : " points");
    }
}
