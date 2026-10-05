package at.magi.ygodiscordbot.format;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits titled tables into Discord messages of at most {@value #MAX_MESSAGE_LENGTH} characters.
 * A table that does not fit is continued in the next message with its header row repeated,
 * so every message renders on its own.
 */
final class MessagePacker {

    /** Discord's limit for message content sent by bots. */
    static final int MAX_MESSAGE_LENGTH = 2000;

    private static final String FENCE = "```";

    private MessagePacker() {
    }

    /**
     * @param heading          markdown shown above the table, e.g. "## 🔴 Forbidden · 121 cards"
     * @param continuedHeading shown instead when the table continues in a new message
     * @param tableHeader      header lines inside the code block (column titles and rule), or empty for none
     */
    record Section(String heading, String continuedHeading, String tableHeader, List<String> rows) {
    }

    static List<String> pack(String intro, List<Section> sections) {
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

    /** Card names must not be able to close the code block. */
    static String safe(String text) {
        return text.replace('`', '\'');
    }
}
