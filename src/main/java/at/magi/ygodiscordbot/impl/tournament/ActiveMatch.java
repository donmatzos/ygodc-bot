package at.magi.ygodiscordbot.impl.tournament;

/** A match of the current round, addressed by its 5-digit ID. Its result lives in the tournament's records. */
record ActiveMatch(int id, long tournamentId, int round, long player1, long player2) {

    boolean involves(long player) {
        return player == player1 || player == player2;
    }

    long opponentOf(long player) {
        return player == player1 ? player2 : player1;
    }
}
