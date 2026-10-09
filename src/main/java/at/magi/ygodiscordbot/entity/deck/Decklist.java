package at.magi.ygodiscordbot.entity.deck;

/**
 * A deck a user saved under a name. Names are unique per user, ignoring case.
 *
 * @param userId    Discord user ID (snowflake)
 * @param createdAt epoch millis
 * @param updatedAt epoch millis
 */
public record Decklist(long id, long userId, String name, String ydke, long createdAt, long updatedAt) {

    public static final int MAX_NAME_LENGTH = 50;
    public static final int MAX_YDKE_LENGTH = 4000;
}
