package at.magi.ygodiscordbot.entity;

import java.util.List;
import java.util.Objects;

/**
 * The Goat format list (April 2005). It never changes, so it is bundled with the bot.
 *
 * @param name name of the underlying official list, e.g. "April 2005"
 */
public record GoatBanlist(String name, List<BanlistEntry> entries) implements Banlist {

    public GoatBanlist {
        Objects.requireNonNull(name, "name");
        entries = List.copyOf(entries);
    }
}
