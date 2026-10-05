package at.magi.ygodiscordbot.entity;

import java.util.Locale;

public enum BanStatus {

    FORBIDDEN("Forbidden", 0),
    LIMITED("Limited", 1),
    SEMI_LIMITED("Semi-Limited", 2);

    private final String label;
    private final int maxCopies;

    BanStatus(String label, int maxCopies) {
        this.label = label;
        this.maxCopies = maxCopies;
    }

    public String label() {
        return label;
    }

    public int maxCopies() {
        return maxCopies;
    }

    /** Parses labels as used by data sources, e.g. "Forbidden", "Banned", "Semi-Limited". */
    public static BanStatus fromLabel(String label) {
        return switch (label.strip().toLowerCase(Locale.ROOT)) {
            case "forbidden", "banned" -> FORBIDDEN;
            case "limited" -> LIMITED;
            case "semi-limited", "semi limited", "semilimited" -> SEMI_LIMITED;
            default -> throw new IllegalArgumentException("Unknown ban status: " + label);
        };
    }
}
