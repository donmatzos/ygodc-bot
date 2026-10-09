package at.magi.ygodiscordbot.source;

import at.magi.ygodiscordbot.entity.banlist.GenesysPointEntry;
import at.magi.ygodiscordbot.entity.banlist.GenesysPointlist;
import at.magi.ygodiscordbot.utils.http.HttpDownloader;
import at.magi.ygodiscordbot.utils.text.LenientDecoder;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Scrapes the points table from Konami's official TCG Genesys page. */
public final class GenesysSource {

    private static final URI PAGE = URI.create("https://www.yugioh-card.com/en/genesys/");
    private static final String TABLE_ROWS = "table#tablepress-genesys tbody tr";

    /** Fewer entries than this means the page layout changed, not that the list got that short. */
    static final int MIN_ENTRIES = 100;

    private final HttpDownloader http;
    private final Clock clock;

    public GenesysSource(HttpDownloader http, Clock clock) {
        this.http = http;
        this.clock = clock;
    }

    public GenesysPointlist fetch() throws IOException, InterruptedException {
        return new GenesysPointlist(clock.instant(), parse(http.get(PAGE)));
    }

    static List<GenesysPointEntry> parse(byte[] html) throws IOException {
        // The page is mostly UTF-8 but contains some Windows-1252 bytes (e.g. the Ø in "K9-ØØ Lupis").
        Document document = Jsoup.parse(LenientDecoder.decode(html), PAGE.toString());
        Map<String, GenesysPointEntry> entries = new LinkedHashMap<>();
        for (Element row : document.select(TABLE_ROWS)) {
            if (row.childrenSize() < 2) {
                continue;
            }
            String name = row.child(0).text().strip();
            String points = row.child(1).text().strip();
            if (name.isEmpty() || !points.matches("\\d+")) {
                continue;
            }
            entries.putIfAbsent(name, new GenesysPointEntry(name, Integer.parseInt(points)));
        }
        if (entries.size() < MIN_ENTRIES) {
            throw new IOException("Genesys page has only " + entries.size() + " entries; did the layout change?");
        }
        return entries.values().stream()
                .sorted(Comparator.comparing(GenesysPointEntry::cardName, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }
}
