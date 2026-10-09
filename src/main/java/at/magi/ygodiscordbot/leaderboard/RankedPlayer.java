package at.magi.ygodiscordbot.leaderboard;

/**
 * One leaderboard row. The rank is computed when querying, never stored: players with equal points share
 * a rank and the next rank is skipped (1, 2, 2, 4).
 *
 * @param userId Discord user ID (snowflake), the {@code players.id} column
 */
public record RankedPlayer(long rank, long userId, long points) {
}
