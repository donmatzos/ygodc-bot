package at.magi.ygodiscordbot.banlist;

import at.magi.ygodiscordbot.entity.banlist.BanStatus;
import at.magi.ygodiscordbot.entity.banlist.BanlistEntry;
import at.magi.ygodiscordbot.entity.banlist.BanlistSnapshot;
import at.magi.ygodiscordbot.entity.banlist.EdisonBanlist;
import at.magi.ygodiscordbot.entity.banlist.GenesysPointEntry;
import at.magi.ygodiscordbot.entity.banlist.GenesysPointlist;
import at.magi.ygodiscordbot.entity.banlist.GoatBanlist;
import at.magi.ygodiscordbot.entity.banlist.OcgBanlist;
import at.magi.ygodiscordbot.entity.banlist.TcgBanlist;

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
