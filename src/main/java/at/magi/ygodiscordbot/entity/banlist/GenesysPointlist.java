package at.magi.ygodiscordbot.entity.banlist;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * The TCG Genesys points list, refreshed daily. Cards not on the list cost 0 points;
 * Link and Pendulum Monsters are not allowed at all.
 */
public record GenesysPointlist(Instant fetchedAt, List<GenesysPointEntry> entries) {

    /** Point cap for standard events; stores may run events with other caps. */
    public static final int STANDARD_POINT_CAP = 100;

    public GenesysPointlist {
        Objects.requireNonNull(fetchedAt, "fetchedAt");
        entries = List.copyOf(entries);
    }
}
