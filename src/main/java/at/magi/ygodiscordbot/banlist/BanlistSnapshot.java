package at.magi.ygodiscordbot.banlist;

import at.magi.ygodiscordbot.entity.GenesysPointlist;
import at.magi.ygodiscordbot.entity.OcgBanlist;
import at.magi.ygodiscordbot.entity.TcgBanlist;

import java.time.Instant;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * The lists that change over time, as one immutable unit. A refresh builds a new snapshot and swaps
 * it in, so readers always see a complete, consistent set of lists without any locking.
 * Components are {@code null} while a list has never been fetched successfully.
 */
public record BanlistSnapshot(TcgBanlist tcg, OcgBanlist ocg, GenesysPointlist genesys) {

    public static final BanlistSnapshot EMPTY = new BanlistSnapshot(null, null, null);

    /** Fetch time of the oldest list, or empty if any list is missing. */
    Optional<Instant> oldestFetch() {
        if (tcg == null || ocg == null || genesys == null) {
            return Optional.empty();
        }
        return Stream.of(tcg.fetchedAt(), ocg.fetchedAt(), genesys.fetchedAt()).min(Instant::compareTo);
    }
}
