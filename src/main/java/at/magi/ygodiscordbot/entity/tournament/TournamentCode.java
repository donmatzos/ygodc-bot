package at.magi.ygodiscordbot.entity.tournament;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.Locale;
import java.util.Optional;
import java.util.Random;
import java.util.regex.Pattern;

/**
 * Public tournament IDs: a random 9-character key plus the day the tournament started, e.g. {@value #EXAMPLE}. The
 * key skips the look-alikes 0/o and 1/l/i, so an ID read from a screenshot can be typed back.
 */
public final class TournamentCode {

    /** Tournament days are Vienna days, like the banlist refresh. */
    public static final ZoneId ZONE = ZoneId.of("Europe/Vienna");
    public static final int KEY_LENGTH = 9;
    /** Key, dash, "yy-MM-dd". */
    public static final int LENGTH = KEY_LENGTH + 9;
    public static final String EXAMPLE = "k7m2x9qp4-26-10-10";

    static final String ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("uu-MM-dd", Locale.ROOT)
            .withResolverStyle(ResolverStyle.STRICT);
    private static final Pattern CODE = Pattern.compile("[" + ALPHABET + "]{" + KEY_LENGTH + "}-(\\d{2}-\\d{2}-\\d{2})");

    private TournamentCode() {
    }

    public static String generate(Random random, LocalDate day) {
        StringBuilder key = new StringBuilder(LENGTH);
        for (int i = 0; i < KEY_LENGTH; i++) {
            key.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return key.append('-').append(day(day)).toString();
    }

    /** The code in its stored form (trimmed, lowercase), or empty if {@code raw} is not a tournament ID. */
    public static Optional<String> parse(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String code = raw.strip().toLowerCase(Locale.ROOT);
        var matcher = CODE.matcher(code);
        if (!matcher.matches() || parseDay(matcher.group(1)).isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(code);
    }

    /** {@code "26-10-10"} → 2026-10-10; empty for anything else, including days that don't exist. */
    public static Optional<LocalDate> parseDay(String raw) {
        try {
            return Optional.of(LocalDate.parse(raw.strip(), DAY));
        } catch (DateTimeParseException | NullPointerException e) {
            return Optional.empty();
        }
    }

    public static String day(LocalDate day) {
        return DAY.format(day);
    }
}
