package at.magi.ygodiscordbot.banlist;

import at.magi.ygodiscordbot.entity.banlist.BanlistSnapshot;
import at.magi.ygodiscordbot.entity.banlist.EdisonBanlist;
import at.magi.ygodiscordbot.entity.banlist.GenesysPointlist;
import at.magi.ygodiscordbot.entity.banlist.GoatBanlist;
import at.magi.ygodiscordbot.entity.banlist.OcgBanlist;
import at.magi.ygodiscordbot.entity.banlist.TcgBanlist;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Read access to all lists for command handlers. Safe for any number of concurrent readers:
 * every list is immutable, and the changing ones are swapped in atomically as one snapshot.
 *
 * <p>To read several lists consistently (e.g. compare TCG and OCG), use {@link #snapshot()} once
 * instead of calling {@link #tcg()} and {@link #ocg()} separately, as a refresh could happen in between.
 */
public final class BanlistRepository {

    private final AtomicReference<BanlistSnapshot> current = new AtomicReference<>(BanlistSnapshot.EMPTY);
    private final GoatBanlist goat;
    private final EdisonBanlist edison;

    public BanlistRepository(GoatBanlist goat, EdisonBanlist edison) {
        this.goat = Objects.requireNonNull(goat, "goat");
        this.edison = Objects.requireNonNull(edison, "edison");
    }

    public BanlistSnapshot snapshot() {
        return current.get();
    }

    public Optional<TcgBanlist> tcg() {
        return Optional.ofNullable(current.get().tcg());
    }

    public Optional<OcgBanlist> ocg() {
        return Optional.ofNullable(current.get().ocg());
    }

    public Optional<GenesysPointlist> genesys() {
        return Optional.ofNullable(current.get().genesys());
    }

    public GoatBanlist goat() {
        return goat;
    }

    public EdisonBanlist edison() {
        return edison;
    }

    void replace(BanlistSnapshot snapshot) {
        current.set(Objects.requireNonNull(snapshot, "snapshot"));
    }
}
