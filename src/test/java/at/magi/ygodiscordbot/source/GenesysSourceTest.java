package at.magi.ygodiscordbot.source;

import at.magi.ygodiscordbot.entity.GenesysPointEntry;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertThrows;

public class GenesysSourceTest {

    @Test
    public void parsesPointsTable() throws IOException {
        List<GenesysPointEntry> entries = GenesysSource.parse(Fixtures.read("genesys.html"));
        Map<String, Integer> points = entries.stream()
                .collect(Collectors.toMap(GenesysPointEntry::cardName, GenesysPointEntry::points));

        assertEquals(entries.size(), 761);
        assertEquals(points.get("\"A Case for K9\""), 20);
        assertEquals(points.get("Ash Blossom & Joyous Spring"), 20);
        assertEquals(points.get("Abyss Dweller"), 100);
    }

    @Test
    public void decodesWindows1252Bytes() throws IOException {
        Map<String, GenesysPointEntry> byName = GenesysSource.parse(Fixtures.read("genesys.html")).stream()
                .collect(Collectors.toMap(GenesysPointEntry::cardName, Function.identity()));

        assertEquals(byName.get("K9-ØØ Lupis").points(), 5);
    }

    @Test
    public void rejectsChangedLayout() {
        byte[] html = "<html><body><table><tr><td>Card</td><td>1</td></tr></table></body></html>"
                .getBytes(StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> GenesysSource.parse(html));
    }
}
