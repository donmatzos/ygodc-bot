package at.magi.ygodiscordbot.leaderboard;

/**
 * Result of a write to one player's points.
 *
 * @param created true if the player had no entry before (before is then 0)
 */
public record PointChange(long before, long after, boolean created) {

    /** The change that was really applied, which is smaller than requested if a limit was hit. */
    public long applied() {
        return after - before;
    }
}
