package at.magi.ygodiscordbot.utils.text;

/** Caps text from remote sources so that a single value can never outgrow a Discord message. */
public final class Truncation {

    /** Longest card or list name kept from remote data; real names are below 100 characters. */
    public static final int MAX_NAME_LENGTH = 200;

    private static final String ELLIPSIS = "…";

    private Truncation() {
    }

    /** {@code text} unchanged if it has at most {@code max} chars, else cut and ending with "…" (total {@code max}). */
    public static String truncate(String text, int max) {
        if (text.length() <= max) {
            return text;
        }
        if (max <= ELLIPSIS.length()) {
            return text.substring(0, Math.max(max, 0));
        }
        int end = max - ELLIPSIS.length();
        if (Character.isHighSurrogate(text.charAt(end - 1))) {
            end--; // do not split a surrogate pair
        }
        return text.substring(0, end) + ELLIPSIS;
    }

    public static String capName(String name) {
        return truncate(name, MAX_NAME_LENGTH);
    }
}
