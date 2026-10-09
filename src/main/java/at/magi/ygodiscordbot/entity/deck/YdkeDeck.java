package at.magi.ygodiscordbot.entity.deck;

import java.util.List;

/**
 * Card passcodes of a deck, per zone, in deck order (copies appear once per copy).
 *
 * @param main  Main Deck passcodes
 * @param extra Extra Deck passcodes
 * @param side  Side Deck passcodes
 */
public record YdkeDeck(List<Long> main, List<Long> extra, List<Long> side) {

    public YdkeDeck {
        main = List.copyOf(main);
        extra = List.copyOf(extra);
        side = List.copyOf(side);
    }
}
