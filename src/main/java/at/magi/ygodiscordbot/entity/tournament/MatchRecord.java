package at.magi.ygodiscordbot.entity.tournament;

/**
 * One pairing of a round with its result: a winner, a double loss (time ran out, both players lose) or nothing yet.
 * A bye is stored as already won by {@code player1}, so it never keeps a round open.
 */
public record MatchRecord(int round, long player1, Long player2, Long winner, boolean doubleLoss) {

    public MatchRecord {
        if (doubleLoss && (winner != null || player2 == null)) {
            throw new IllegalArgumentException("A double loss needs two players and no winner");
        }
    }

    /** A pairing without a double loss; {@code winner == null} means not played yet. */
    public MatchRecord(int round, long player1, Long player2, Long winner) {
        this(round, player1, player2, winner, false);
    }

    public static MatchRecord of(int round, Pairing pairing) {
        return new MatchRecord(round, pairing.player1(), pairing.player2(), pairing.isBye() ? pairing.player1() : null);
    }

    public boolean isBye() {
        return player2 == null;
    }

    public boolean isPlayed() {
        return winner != null || doubleLoss;
    }

    public MatchStatus status() {
        return isPlayed() ? MatchStatus.FINISHED : MatchStatus.RUNNING;
    }

    public boolean involves(long player) {
        return player1 == player || (player2 != null && player2 == player);
    }

    /** The other player of a match with a winner; null for byes, double losses and unplayed matches. */
    public Long loser() {
        if (winner == null || isBye()) {
            return null;
        }
        return winner.longValue() == player1 ? player2 : Long.valueOf(player1);
    }

    public MatchRecord withWinner(long winner) {
        return new MatchRecord(round, player1, player2, winner, false);
    }

    public MatchRecord asDoubleLoss() {
        return new MatchRecord(round, player1, player2, null, true);
    }
}
