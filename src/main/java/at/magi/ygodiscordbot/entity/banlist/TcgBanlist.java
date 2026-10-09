package at.magi.ygodiscordbot.entity.banlist;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** The current TCG Forbidden \& Limited list, refreshed daily. */
public record TcgBanlist(Instant fetchedAt, List<BanlistEntry> entries) implements Banlist {

    public TcgBanlist {
        Objects.requireNonNull(fetchedAt, "fetchedAt");
        entries = List.copyOf(entries);
    }
}
