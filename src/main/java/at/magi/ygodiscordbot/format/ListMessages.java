package at.magi.ygodiscordbot.format;

import at.magi.ygodiscordbot.entity.BanStatus;
import at.magi.ygodiscordbot.entity.Banlist;
import at.magi.ygodiscordbot.entity.BanlistEntry;
import at.magi.ygodiscordbot.entity.GenesysPointEntry;
import at.magi.ygodiscordbot.entity.GenesysPointlist;
import at.magi.ygodiscordbot.format.MessagePacker.Section;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Renders lists as plain-text Discord messages: markdown headings plus monospace tables in code blocks.
 * Plain text (rather than embeds) keeps every card name findable with Discord's search.
 */
public final class ListMessages {

    /** Keeps table rules short enough for phone screens. */
    private static final int MAX_RULE_WIDTH = 32;

    private ListMessages() {
    }

    /**
     * @param title    e.g. "TCG Forbidden & Limited List"
     * @param subtitle e.g. "Source: YGOProDeck", shown after the card count
     */
    public static List<String> banlist(String title, String subtitle, Banlist banlist) {
        String intro = "# " + title + "\n-# " + banlist.entries().size() + " cards · " + subtitle;
        List<Section> sections = new ArrayList<>();
        for (BanStatus status : BanStatus.values()) {
            List<BanlistEntry> entries = banlist.withStatus(status);
            if (entries.isEmpty()) {
                continue;
            }
            List<String> rows = new ArrayList<>();
            for (int i = 0; i < entries.size(); i++) {
                rows.add(String.format("%3d  %s", i + 1, MessagePacker.safe(entries.get(i).cardName())));
            }
            String name = icon(status) + " " + status.label();
            sections.add(new Section(
                    "## " + name + " · " + entries.size() + " cards\n-# " + copies(status),
                    "-# " + name + " (continued)",
                    "  #  Card\n" + rule(3) + "  " + rule(longest(entries.stream().map(BanlistEntry::cardName).toList())),
                    rows));
        }
        return MessagePacker.pack(intro, sections);
    }

    public static List<String> genesys(GenesysPointlist pointlist) {
        String intro = "# Genesys Points List\n-# " + pointlist.entries().size() + " cards · standard point cap "
                + GenesysPointlist.STANDARD_POINT_CAP + " · unlisted cards cost 0 points · no Link or Pendulum Monsters · "
                + "updated " + timestamp(pointlist.fetchedAt()) + " · Source: Konami";
        List<Section> sections = new ArrayList<>();
        for (PointTier tier : PointTier.values()) {
            List<GenesysPointEntry> entries = pointlist.entries().stream()
                    .filter(entry -> tier.contains(entry.points()))
                    .sorted(Comparator.comparingInt(GenesysPointEntry::points).reversed()
                            .thenComparing(GenesysPointEntry::cardName, String.CASE_INSENSITIVE_ORDER))
                    .toList();
            if (entries.isEmpty()) {
                continue;
            }
            List<String> rows = entries.stream()
                    .map(entry -> String.format("%3d  %s", entry.points(), MessagePacker.safe(entry.cardName())))
                    .toList();
            sections.add(new Section(
                    "## " + tier.label + " · " + entries.size() + " cards",
                    "-# " + tier.label + " (continued)",
                    "Pts  Card\n" + rule(3) + "  " + rule(longest(entries.stream().map(GenesysPointEntry::cardName).toList())),
                    rows));
        }
        return MessagePacker.pack(intro, sections);
    }

    /** Discord timestamp markup: rendered in each viewer's own time zone. */
    public static String timestamp(Instant instant) {
        return "<t:" + instant.getEpochSecond() + ":f>";
    }

    private enum PointTier {
        MAX("💯 100 points", 100, 100),
        HIGH("🔺 50–99 points", 50, 99),
        MEDIUM("🔸 20–49 points", 20, 49),
        LOW("🔹 10–19 points", 10, 19),
        MINIMAL("▫️ 1–9 points", 1, 9);

        private final String label;
        private final int min;
        private final int max;

        PointTier(String label, int min, int max) {
            this.label = label;
            this.min = min;
            this.max = max;
        }

        boolean contains(int points) {
            return points >= min && points <= max;
        }
    }

    private static String icon(BanStatus status) {
        return switch (status) {
            case FORBIDDEN -> "🔴";
            case LIMITED -> "🟠";
            case SEMI_LIMITED -> "🟡";
        };
    }

    private static String copies(BanStatus status) {
        return switch (status) {
            case FORBIDDEN -> "Not allowed in your Deck";
            case LIMITED -> "Max. 1 copy";
            case SEMI_LIMITED -> "Max. 2 copies";
        };
    }

    private static int longest(List<String> names) {
        return names.stream().mapToInt(String::length).max().orElse(4);
    }

    private static String rule(int width) {
        return "─".repeat(Math.min(width, MAX_RULE_WIDTH));
    }
}
