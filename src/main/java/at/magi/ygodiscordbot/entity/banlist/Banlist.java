package at.magi.ygodiscordbot.entity.banlist;

import java.util.List;

/** A Forbidden & Limited list. Implementations are immutable and therefore safe to share between threads. */
public interface Banlist {

    List<BanlistEntry> entries();

    default List<BanlistEntry> withStatus(BanStatus status) {
        return entries().stream().filter(entry -> entry.status() == status).toList();
    }
}
