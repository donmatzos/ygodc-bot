package at.magi.ygodiscordbot.format;

import at.magi.ygodiscordbot.format.DcMessageUtils.Section;
import at.magi.ygodiscordbot.leaderboard.LeaderboardPage;
import at.magi.ygodiscordbot.leaderboard.RankedPlayer;

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
}
