package at.magi.ygodiscordbot.leaderboard;

/** Limits for a player's points: never below 0, never above {@link #MAX}. */
public final class Points {

    /** One below the largest {@code BIGINT}. */
    public static final long MAX = Long.MAX_VALUE - 1;

    /** Most points one /points call may add or remove. */
    public static final int MAX_CHANGE = 99;

    private Points() {
    }

    /** {@code current + delta}, kept within 0 … {@link #MAX} without overflowing. */
    public static long apply(long current, long delta) {
        long base = Math.min(Math.max(current, 0), MAX);
        if (delta >= 0) {
            return base > MAX - delta ? MAX : base + delta;
        }
        return base + delta < 0 ? 0 : base + delta;
    }
}
