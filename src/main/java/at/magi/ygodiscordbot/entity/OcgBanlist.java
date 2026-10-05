package at.magi.ygodiscordbot.entity;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** The current OCG Forbidden \& Limited list (English card names), refreshed daily. */
public record OcgBanlist(Instant fetchedAt, List<BanlistEntry> entries) implements Banlist {

    public OcgBanlist {
        Objects.requireNonNull(fetchedAt, "fetchedAt");
        entries = List.copyOf(entries);
    }
}
