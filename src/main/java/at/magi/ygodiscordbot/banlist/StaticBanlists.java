package at.magi.ygodiscordbot.banlist;

import at.magi.ygodiscordbot.entity.EdisonBanlist;
import at.magi.ygodiscordbot.entity.GoatBanlist;
import at.magi.ygodiscordbot.json.Json;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/** Loads the lists of formats that never change; they are bundled as JSON in the jar. */
public final class StaticBanlists {

    private StaticBanlists() {
    }

    public static GoatBanlist goat() {
        return load("/banlists/goat.json", GoatBanlist.class);
    }

    public static EdisonBanlist edison() {
        return load("/banlists/edison.json", EdisonBanlist.class);
    }

    private static <T> T load(String resource, Class<T> type) {
        try (InputStream in = StaticBanlists.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Missing resource " + resource);
            }
            return Json.MAPPER.readValue(in, type);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + resource, e);
        }
    }
}
