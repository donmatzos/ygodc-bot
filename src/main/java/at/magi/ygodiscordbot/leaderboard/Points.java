package at.magi.ygodiscordbot.leaderboard;

/** Limits for a player's points: never below 0, never above {@link #MAX}. */
public final class Points {

    /** Six digits, so totals fit the Points column of the leaderboard table. */
    public static final long MAX = 999_999;

    /** Most points one /points call may add or remove. */
    public static final int MAX_CHANGE = 99;

    private Points() {
    }

    /** {@code current + delta}, kept within 0 … {@link #MAX}. */
    public static long apply(long current, long delta) {
        return Math.min(Math.max(current + delta, 0), MAX);
    }
}
