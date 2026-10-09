package at.magi.ygodiscordbot.impl.banlist;

import at.magi.ygodiscordbot.entity.banlist.BanStatus;
import at.magi.ygodiscordbot.entity.banlist.BanlistEntry;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertThrows;

public class YgoProDeckSourceTest {

    @Test
    public void parsesTcgList() throws IOException {
        List<BanlistEntry> entries = YgoProDeckSource.parse(Fixtures.read("ygoprodeck-tcg.json"), "tcg");

        // The fixture contains 222 cards plus one alternate artwork with a duplicate name.
        assertEquals(entries.size(), 222);
        assertEquals(count(entries, BanStatus.FORBIDDEN), 121);
        assertEquals(count(entries, BanStatus.LIMITED), 93);
        assertEquals(count(entries, BanStatus.SEMI_LIMITED), 8);
        assertEquals(entries.get(0).status(), BanStatus.FORBIDDEN);
        assertEquals(entries.get(entries.size() - 1).status(), BanStatus.SEMI_LIMITED);
    }

    @Test
    public void usesStatusOfRequestedFormat() throws IOException {
        // "Called by the Grave" is Limited in the TCG but Forbidden in the OCG.
        BanlistEntry entry = YgoProDeckSource.parse(Fixtures.read("ygoprodeck-tcg.json"), "tcg").stream()
                .filter(e -> e.cardName().equals("Called by the Grave"))
                .findFirst()
                .orElseThrow();

        assertEquals(entry.status(), BanStatus.LIMITED);
    }

    @Test
    public void rejectsErrorResponse() {
        byte[] json = "{\"error\":\"No card matching your query was found in the database.\"}"
                .getBytes(StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> YgoProDeckSource.parse(json, "tcg"));
    }

    private static long count(List<BanlistEntry> entries, BanStatus status) {
        return entries.stream().filter(entry -> entry.status() == status).count();
    }
}
