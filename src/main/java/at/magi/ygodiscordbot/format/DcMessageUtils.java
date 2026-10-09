package at.magi.ygodiscordbot.format;

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
                if (message.length() + open.length() + rows.get(next).length() + 1 + close.length() > MAX_MESSAGE_LENGTH) {
                    messages.add(message.toString().strip());
                    message = new StringBuilder();
                }
                message.append(open);
                while (next < rows.size()
                        && message.length() + rows.get(next).length() + 1 + close.length() <= MAX_MESSAGE_LENGTH) {
                    message.append(rows.get(next++)).append('\n');
                }
                message.append(close);
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
