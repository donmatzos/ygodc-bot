package at.magi.ygodiscordbot.entity.card;

import java.time.Instant;
import java.util.Map;

public final class TestCards {

    public static final long BLUE_EYES = 89631139L;
    public static final long BLUE_EYES_ALT = 89631141L;
    public static final long DARK_MAGICIAN = 46986414L;

    private TestCards() {
    }

    public static CardNames names() {
        return CardNames.of(Map.of(
                (int) BLUE_EYES, "Blue-Eyes White Dragon",
                (int) BLUE_EYES_ALT, "Blue-Eyes White Dragon",
                (int) DARK_MAGICIAN, "Dark Magician",
                55144522, "Pot of Greed",
                23995346, "Blue-Eyes Ultimate Dragon"));
    }

    public static CardCatalog catalog(String version, Instant checkedAt) {
        return new CardCatalog(version, checkedAt, names());
    }
}
