package at.magi.ygodiscordbot.deck;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Parses and encodes YDKE URIs.
 *
 * <p>The format uses the scheme {@code ydke://} followed by three Base64-encoded sections separated by
 * {@code !} and terminated by a trailing {@code !}:
 *
 * <pre>{@code ydke://<base64-main>!<base64-extra>!<base64-side>!}</pre>
 *
 * <p>Each section is a sequence of card passcodes stored as little-endian unsigned 32-bit integers
 * (4 bytes per card). Stateless: parsing allocates only the decoded bytes and the passcode lists.
 */
public final class Ydke {

    public static final String PREFIX = "ydke://";
    public static final String FORMAT = PREFIX + "<main>!<extra>!<side>!";

    private static final int BYTES_PER_CARD = 4;

    private Ydke() {
    }

    /** @throws IllegalArgumentException if the URI is not valid YDKE; the message says what is wrong */
    public static YdkeDeck parse(String uri) {
        if (uri == null || !uri.startsWith(PREFIX)) {
            throw new IllegalArgumentException("must start with " + PREFIX);
        }
        String[] parts = uri.substring(PREFIX.length()).split("!", -1);
        if (parts.length < 3) {
            throw new IllegalArgumentException("expected three sections separated by \"!\"");
        }
        return new YdkeDeck(decodeZone(parts[0]), decodeZone(parts[1]), decodeZone(parts[2]));
    }

    public static String encode(YdkeDeck deck) {
        return PREFIX + encodeZone(deck.main()) + "!" + encodeZone(deck.extra()) + "!" + encodeZone(deck.side()) + "!";
    }

    private static List<Long> decodeZone(String base64) {
        if (base64.isEmpty()) {
            return List.of();
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("a section is not valid Base64", e);
        }
        if (bytes.length % BYTES_PER_CARD != 0) {
            throw new IllegalArgumentException(
                    "section length (" + bytes.length + " bytes) is not a multiple of " + BYTES_PER_CARD);
        }
        int count = bytes.length / BYTES_PER_CARD;
        List<Long> ids = new ArrayList<>(count);
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < count; i++) {
            // Passcodes are unsigned 32-bit
            ids.add(Integer.toUnsignedLong(buffer.getInt()));
        }
        return ids;
    }

    private static String encodeZone(List<Long> ids) {
        if (ids.isEmpty()) {
            return "";
        }
        ByteBuffer buffer = ByteBuffer.allocate(ids.size() * BYTES_PER_CARD).order(ByteOrder.LITTLE_ENDIAN);
        for (long id : ids) {
            buffer.putInt((int) (id & 0xFFFFFFFFL));
        }
        return Base64.getEncoder().encodeToString(buffer.array());
    }
}
