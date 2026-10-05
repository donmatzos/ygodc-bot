package at.magi.ygodiscordbot.card;

import java.time.Instant;

/**
 * The card names of one YGOProDeck database version.
 *
 * @param version   YGOProDeck {@code database_version} the names were downloaded for
 * @param checkedAt last time the version was checked against YGOProDeck
 */
public record CardCatalog(String version, Instant checkedAt, CardNames names) {

    CardCatalog checkedAgain(Instant now) {
        return new CardCatalog(version, now, names);
    }
}
