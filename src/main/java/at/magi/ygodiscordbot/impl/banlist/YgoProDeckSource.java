package at.magi.ygodiscordbot.impl.banlist;

import at.magi.ygodiscordbot.entity.banlist.BanStatus;
import at.magi.ygodiscordbot.entity.banlist.BanlistEntry;
import at.magi.ygodiscordbot.entity.banlist.OcgBanlist;
import at.magi.ygodiscordbot.entity.banlist.TcgBanlist;
import at.magi.ygodiscordbot.utils.http.HttpDownloader;
import at.magi.ygodiscordbot.utils.json.JsonUtils;
import at.magi.ygodiscordbot.utils.text.Truncation;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * TCG and OCG lists from the YGOProDeck API. Filtering by banlist keeps the response small
 * (only cards on the list, roughly 0.5 MB) instead of downloading the whole card database.
 */
public final class YgoProDeckSource {

    private static final String API = "https://db.ygoprodeck.com/api/v7/cardinfo.php?banlist=";

    /** Fewer entries than this means the response is broken, not that the list got that short. */
    static final int MIN_ENTRIES = 50;

    private final HttpDownloader http;
    private final Clock clock;

    public YgoProDeckSource(HttpDownloader http, Clock clock) {
        this.http = http;
        this.clock = clock;
    }

    public TcgBanlist fetchTcg() throws IOException, InterruptedException {
        return new TcgBanlist(clock.instant(), fetch("tcg"));
    }

    public OcgBanlist fetchOcg() throws IOException, InterruptedException {
        return new OcgBanlist(clock.instant(), fetch("ocg"));
    }

    private List<BanlistEntry> fetch(String banlist) throws IOException, InterruptedException {
        return parse(http.get(URI.create(API + banlist)), banlist);
    }

    static List<BanlistEntry> parse(byte[] json, String banlist) throws IOException {
        Response response = JsonUtils.MAPPER.readValue(json, Response.class);
        if (response.data() == null) {
            throw new IOException("YGOProDeck response for " + banlist + " has no data");
        }
        String key = "ban_" + banlist;
        // Keyed by name: alternate artworks can appear as separate cards with the same name.
        Map<String, BanlistEntry> entries = new LinkedHashMap<>();
        for (Card card : response.data()) {
            String status = card.banlistInfo() == null ? null : card.banlistInfo().get(key);
            if (card.name() != null && status != null) {
                String name = Truncation.capName(card.name());
                entries.putIfAbsent(name, new BanlistEntry(name, BanStatus.fromLabel(status)));
            }
        }
        if (entries.size() < MIN_ENTRIES) {
            throw new IOException("YGOProDeck " + banlist + " list has only " + entries.size() + " entries");
        }
        return entries.values().stream()
                .sorted(Comparator.comparing(BanlistEntry::status)
                        .thenComparing(BanlistEntry::cardName, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    private record Response(List<Card> data) {
    }

    private record Card(String name, @JsonProperty("banlist_info") Map<String, String> banlistInfo) {
    }
}
