package at.magi.ygodiscordbot.utils.discord;

import at.magi.ygodiscordbot.utils.text.Truncation;
import net.dv8tion.jda.api.utils.MarkdownSanitizer;

import java.util.ArrayList;
import java.util.List;

/**
 * All splitting of bot output into Discord messages of at most {@value #MAX_MESSAGE_LENGTH} characters:
 * code-block tables ({@link #packTables}) and plain lines ({@link #packLines}). A table that does not fit is
 * continued in the next message with its header row repeated, so every message renders on its own.
 */
public final class DcMessageUtils {

    /** Discord's limit for message content sent by bots. */
    public static final int MAX_MESSAGE_LENGTH = 2000;

    private static final String FENCE = "```";

    private DcMessageUtils() {
    }

    /** User-chosen text (deck or player names) with its Markdown escaped, so it shows literally. */
    public static String escape(String text) {
        return MarkdownSanitizer.escape(text);
    }

    /** {@code text} escaped and wrapped in bold. */
    public static String bold(String text) {
        return "**" + escape(text) + "**";
    }

    /**
     * @param heading          markdown shown above the table, e.g. "## 🔴 Forbidden · 121 cards"
     * @param continuedHeading shown instead when the table continues in a new message
     * @param tableHeader      header lines inside the code block (column titles and rule), or empty for none
     */
    public record Section(String heading, String continuedHeading, String tableHeader, List<String> rows) {
    }

    public static List<String> packTables(String intro, List<Section> sections) {
        List<String> messages = new ArrayList<>();
        StringBuilder message = new StringBuilder(intro).append('\n');
        String close = FENCE + "\n";

        for (Section section : sections) {
            List<String> rows = section.rows();
            int next = 0;
            boolean first = true;
            while (next < rows.size()) {
                String open = "\n" + (first ? section.heading() : section.continuedHeading()) + "\n"
                        + FENCE + "\n" + (section.tableHeader().isEmpty() ? "" : section.tableHeader() + "\n");
                // A row that cannot fit even into an empty message is truncated; it would never be added otherwise
                int maxRow = MAX_MESSAGE_LENGTH - open.length() - close.length() - 1;
                String row = Truncation.truncate(rows.get(next), maxRow);
                if (message.length() + open.length() + row.length() + 1 + close.length() > MAX_MESSAGE_LENGTH) {
                    messages.add(message.toString().strip());
                    message = new StringBuilder();
                }
                message.append(open);
                int start = next;
                while (next < rows.size()) {
                    row = Truncation.truncate(rows.get(next), maxRow);
                    if (message.length() + row.length() + 1 + close.length() > MAX_MESSAGE_LENGTH) {
                        break;
                    }
                    message.append(row).append('\n');
                    next++;
                }
                message.append(close);
                if (next == start) {
                    next++; // only possible with an absurdly long heading; skip the row rather than loop forever
                }
                first = false;
            }
        }
        if (!message.isEmpty()) {
            messages.add(message.toString().strip());
        }
        return messages;
    }

    /**
     * Joins {@code header} and {@code lines} with line breaks into as few messages as possible. A message is only
     * split between lines, never inside one.
     *
     * @param lines each must be shorter than {@value #MAX_MESSAGE_LENGTH} characters
     */
    public static List<String> packLines(String header, List<String> lines) {
        List<String> messages = new ArrayList<>();
        StringBuilder message = new StringBuilder(header);
        for (String line : lines) {
            if (message.length() + 1 + line.length() > MAX_MESSAGE_LENGTH) {
                messages.add(message.toString());
                message = new StringBuilder(line);
            } else {
                message.append('\n').append(line);
            }
        }
        messages.add(message.toString());
        return messages;
    }

    /** Names must not be able to close the code block. */
    public static String safe(String text) {
        return text.replace('`', '\'');
    }
}
