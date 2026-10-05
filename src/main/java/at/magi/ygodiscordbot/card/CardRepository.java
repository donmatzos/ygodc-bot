package at.magi.ygodiscordbot.card;

import java.util.Optional;

/** Current card names. Replaced as a whole by {@link CardRefresher}, so readers never need a lock. */
public final class CardRepository {

    private volatile CardCatalog catalog;

    /** {@link CardNames#EMPTY} until the first list is loaded. */
    public CardNames names() {
        CardCatalog current = catalog;
        return current == null ? CardNames.EMPTY : current.names();
    }

    public Optional<CardCatalog> catalog() {
        return Optional.ofNullable(catalog);
    }

    void replace(CardCatalog catalog) {
        this.catalog = catalog;
    }
}
