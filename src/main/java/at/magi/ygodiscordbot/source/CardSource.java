package at.magi.ygodiscordbot.source;

import at.magi.ygodiscordbot.card.CardNames;
import at.magi.ygodiscordbot.json.Json;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Card names from the YGOProDeck API. The full card list is about 21 MB of JSON (3 MB gzipped), so it is
 * streamed and trimmed on the fly: only passcodes and names are kept, never the whole document.
 *
 * <p>API rules: at most 20 requests per second, and data should be stored locally instead of fetched
 * repeatedly; {@link at.magi.ygodiscordbot.card.CardRefresher} calls this every few days at most.
 */
public final class CardSource {

    private static final URI VERSION = URI.create("https://db.ygoprodeck.com/api/v7/checkDBVer.php");
    private static final URI CARDS = URI.create("https://db.ygoprodeck.com/api/v7/cardinfo.php");

    /** Fewer cards than this means the response is broken, not that the game shrank (about 14,600 in 2026). */
    static final int MIN_CARDS = 10_000;

    private final HttpDownloader http;

    public CardSource(HttpDownloader http) {
        this.http = http;
    }

    /** The YGOProDeck database version; changes whenever a card is added or edited. Not cached by the API. */
    public String fetchVersion() throws IOException, InterruptedException {
        return parseVersion(http.get(VERSION));
    }

    public CardNames fetchCards() throws IOException, InterruptedException {
        return http.stream(CARDS, body -> parseCards(body, MIN_CARDS));
    }

    static String parseVersion(byte[] json) throws IOException {
        // [{"database_version":"147.22","last_update":"2026-10-02 00:03:40"}]
        JsonNode version = Json.MAPPER.readTree(json).path(0).path("database_version");
        if (!version.isValueNode() || version.asText().isBlank()) {
            throw new IOException("YGOProDeck version response has no database_version");
        }
        return version.asText();
    }

    /**
     * Reads {@code {"data":[{"id":..,"name":..,"card_images":[{"id":..},..],..},..]}} token by token.
     * Alternate artworks have their own passcode in {@code card_images} and map to the card's name.
     */
    static CardNames parseCards(InputStream body, int minCards) throws IOException {
        Map<Integer, String> names = new HashMap<>(20_000);
        int cards = 0;
        try (JsonParser parser = Json.MAPPER.getFactory().createParser(body)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new IOException("Expected a JSON object");
            }
            while (parser.nextToken() == JsonToken.FIELD_NAME) {
                String field = parser.currentName();
                if (parser.nextToken() == JsonToken.START_ARRAY && field.equals("data")) {
                    JsonToken element;
                    while ((element = next(parser)) != JsonToken.END_ARRAY) {
                        if (element != JsonToken.START_OBJECT) {
                            parser.skipChildren();
                        } else if (readCard(parser, names)) {
                            cards++;
                        }
                    }
                } else {
                    parser.skipChildren();
                }
            }
        }
        if (cards < minCards) {
            throw new IOException("YGOProDeck returned only " + cards + " cards, expected at least " + minCards);
        }
        return CardNames.of(names);
    }

    /** Reads one card object; returns false if it has no passcode or name. */
    private static boolean readCard(JsonParser parser, Map<Integer, String> names) throws IOException {
        Integer passcode = null;
        String name = null;
        List<Integer> artworks = new ArrayList<>(2);
        while (parser.nextToken() == JsonToken.FIELD_NAME) {
            String field = parser.currentName();
            JsonToken value = parser.nextToken();
            if (field.equals("id") && value == JsonToken.VALUE_NUMBER_INT) {
                passcode = parser.getIntValue();
            } else if (field.equals("name") && value == JsonToken.VALUE_STRING) {
                name = parser.getText();
            } else if (field.equals("card_images") && value == JsonToken.START_ARRAY) {
                readArtworks(parser, artworks);
            } else {
                // Unwanted or unexpected value: skip it whole (no-op for plain values)
                parser.skipChildren();
            }
        }
        if (passcode == null || name == null) {
            return false;
        }
        // A card's own passcode wins over another card's artwork with the same number
        names.put(passcode, name);
        for (int artwork : artworks) {
            names.putIfAbsent(artwork, name);
        }
        return true;
    }

    /** Reads {@code [{"id":..,"image_url":..},..]} up to its closing bracket. */
    private static void readArtworks(JsonParser parser, List<Integer> artworks) throws IOException {
        JsonToken element;
        while ((element = next(parser)) != JsonToken.END_ARRAY) {
            if (element != JsonToken.START_OBJECT) {
                parser.skipChildren();
                continue;
            }
            while (parser.nextToken() == JsonToken.FIELD_NAME) {
                String field = parser.currentName();
                if (parser.nextToken() == JsonToken.VALUE_NUMBER_INT && field.equals("id")) {
                    artworks.add(parser.getIntValue());
                } else {
                    parser.skipChildren();
                }
            }
        }
    }

    /** Jackson reports a truncated document itself; this only guards loops against a missing token. */
    private static JsonToken next(JsonParser parser) throws IOException {
        JsonToken token = parser.nextToken();
        if (token == null) {
            throw new IOException("Card list ended unexpectedly");
        }
        return token;
    }
}
