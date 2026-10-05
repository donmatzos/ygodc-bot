package at.magi.ygodiscordbot.entity;

import java.util.Objects;

public record GenesysPointEntry(String cardName, int points) {

    public GenesysPointEntry {
        Objects.requireNonNull(cardName, "cardName");
        if (points < 0) {
            throw new IllegalArgumentException("Negative points for " + cardName + ": " + points);
        } else if (points > 100) {
            throw new IllegalArgumentException("Too many points for " + cardName + ": " + points);
        }
    }
}
