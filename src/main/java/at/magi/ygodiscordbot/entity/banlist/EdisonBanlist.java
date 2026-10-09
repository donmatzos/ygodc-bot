package at.magi.ygodiscordbot.entity.banlist;

import java.util.List;
import java.util.Objects;

/**
 * The Edison format list (March 2010). It never changes, so it is bundled with the bot.
 *
 * @param name name of the underlying official list, e.g. "March 2010"
 */
public record EdisonBanlist(String name, List<BanlistEntry> entries) implements Banlist {

    public EdisonBanlist {
        Objects.requireNonNull(name, "name");
        entries = List.copyOf(entries);
    }
}
