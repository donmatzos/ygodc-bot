package at.magi.ygodiscordbot.entity;

import java.util.Objects;

public record BanlistEntry(String cardName, BanStatus status) {

    public BanlistEntry {
        Objects.requireNonNull(cardName, "cardName");
        Objects.requireNonNull(status, "status");
    }
}
