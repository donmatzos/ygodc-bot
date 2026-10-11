package at.magi.ygodiscordbot.impl.card;

import at.magi.ygodiscordbot.entity.card.CardCatalog;
import at.magi.ygodiscordbot.entity.card.CardNames;
import at.magi.ygodiscordbot.utils.io.AtomicFiles;
import at.magi.ygodiscordbot.utils.json.JsonUtils;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Stores the trimmed card list ({@code {"version":..,"checkedAt":..,"cards":{"<passcode>":"<name>",..}}},
 * about 0.5 MB), so a restart needs no download. Read and written with Jackson's streaming API.
 */
public final class CardFileStore {

    public static final Path DEFAULT_FILE = Path.of("data", "cards.json");

    private static final String TEMP_PREFIX = "cards";

    private final Path file;

    public CardFileStore(Path file) {
        this.file = file;
    }

    /** Call before the first {@link #save}, i.e. at startup. */
    public Optional<CardCatalog> load() {
        return AtomicFiles.readIfPresent(file, TEMP_PREFIX, path -> {
            try (InputStream in = Files.newInputStream(path);
                 JsonParser parser = JsonUtils.MAPPER.getFactory().createParser(in)) {
                return read(parser);
            }
        });
    }

    public void save(CardCatalog catalog) throws IOException {
        AtomicFiles.write(file, TEMP_PREFIX, out -> {
            try (JsonGenerator json = JsonUtils.MAPPER.getFactory().createGenerator(out)) {
                json.writeStartObject();
                json.writeStringField("version", catalog.version());
                json.writeStringField("checkedAt", catalog.checkedAt().toString());
                json.writeObjectFieldStart("cards");
                CardNames names = catalog.names();
                for (int i = 0; i < names.size(); i++) {
                    json.writeStringField(Integer.toString(names.passcodeAt(i)), names.nameAt(i));
                }
                json.writeEndObject();
                json.writeEndObject();
            }
        });
    }

    private static CardCatalog read(JsonParser parser) throws IOException {
        if (parser.nextToken() != JsonToken.START_OBJECT) {
            throw new IOException("Expected a JSON object");
        }
        String version = null;
        Instant checkedAt = null;
        Map<Integer, String> cards = null;
        while (parser.nextToken() == JsonToken.FIELD_NAME) {
            String field = parser.currentName();
            parser.nextToken();
            switch (field) {
                case "version" -> version = parser.getText();
                case "checkedAt" -> checkedAt = Instant.parse(parser.getText());
                case "cards" -> {
                    cards = new HashMap<>();
                    while (parser.nextToken() == JsonToken.FIELD_NAME) {
                        int passcode = Integer.parseInt(parser.currentName());
                        parser.nextToken();
                        cards.put(passcode, parser.getText());
                    }
                }
                default -> parser.skipChildren();
            }
        }
        if (version == null || checkedAt == null || cards == null) {
            throw new IOException("Card file is incomplete");
        }
        return new CardCatalog(version, checkedAt, CardNames.of(cards));
    }
}
