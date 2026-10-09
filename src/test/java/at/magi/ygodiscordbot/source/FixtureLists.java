package at.magi.ygodiscordbot.source;

import at.magi.ygodiscordbot.entity.banlist.GenesysPointlist;
import at.magi.ygodiscordbot.entity.banlist.TcgBanlist;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;

/** Real lists parsed from the test fixtures, for tests in other packages. */
public final class FixtureLists {

    public static final Instant FETCHED_AT = Instant.parse("2026-10-05T01:00:00Z");

    private FixtureLists() {
    }

    public static TcgBanlist tcg() {
        try {
            return new TcgBanlist(FETCHED_AT, YgoProDeckSource.parse(Fixtures.read("ygoprodeck-tcg.json"), "tcg"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static GenesysPointlist genesys() {
        try {
            return new GenesysPointlist(FETCHED_AT, GenesysSource.parse(Fixtures.read("genesys.html")));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
