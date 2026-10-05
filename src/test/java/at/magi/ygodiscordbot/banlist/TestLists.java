package at.magi.ygodiscordbot.banlist;

import at.magi.ygodiscordbot.entity.BanStatus;
import at.magi.ygodiscordbot.entity.BanlistEntry;
import at.magi.ygodiscordbot.entity.EdisonBanlist;
import at.magi.ygodiscordbot.entity.GenesysPointEntry;
import at.magi.ygodiscordbot.entity.GenesysPointlist;
import at.magi.ygodiscordbot.entity.GoatBanlist;
import at.magi.ygodiscordbot.entity.OcgBanlist;
import at.magi.ygodiscordbot.entity.TcgBanlist;

import java.time.Instant;
import java.util.List;

final class TestLists {

    private TestLists() {
    }

    static TcgBanlist tcg(Instant fetchedAt) {
        return new TcgBanlist(fetchedAt, List.of(new BanlistEntry("Pot of Greed", BanStatus.FORBIDDEN)));
    }

    static OcgBanlist ocg(Instant fetchedAt) {
        return new OcgBanlist(fetchedAt, List.of(new BanlistEntry("Graceful Charity", BanStatus.LIMITED)));
    }

    static GenesysPointlist genesys(Instant fetchedAt) {
        return new GenesysPointlist(fetchedAt, List.of(new GenesysPointEntry("Ash Blossom & Joyous Spring", 20)));
    }

    static BanlistSnapshot snapshot(Instant fetchedAt) {
        return new BanlistSnapshot(tcg(fetchedAt), ocg(fetchedAt), genesys(fetchedAt));
    }

    static BanlistRepository repository() {
        return new BanlistRepository(new GoatBanlist("April 2005", List.of()), new EdisonBanlist("March 2010", List.of()));
    }
}
