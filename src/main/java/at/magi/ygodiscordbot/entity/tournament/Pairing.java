package at.magi.ygodiscordbot.entity.tournament;

/** Two players of one round, or a bye ({@code player2 == null}): {@code player1} gets a free win. */
public record Pairing(long player1, Long player2) {

    public static Pairing bye(long player) {
        return new Pairing(player, null);
    }

    public boolean isBye() {
        return player2 == null;
    }
}
