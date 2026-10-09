package at.magi.ygodiscordbot.format;

import at.magi.ygodiscordbot.card.CardNames;
import at.magi.ygodiscordbot.deck.YdkeDeck;
import at.magi.ygodiscordbot.utils.discord.DcMessageUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Renders a saved deck as Discord messages: card list per zone, then the YDKE URI to copy. */
public final class DeckMessages {

    private DeckMessages() {
    }

    /**
     * @param heading first line, e.g. "You saved the following deck **Dragons**:"
     * @param names   {@link CardNames#EMPTY} while the card list is not loaded; the cards are then left out
     * @return one message, or more if the card list does not fit into one
     */
    public static List<String> deck(String heading, long updatedAtMillis, String ydke, YdkeDeck deck,
                                    CardNames names) {
        String intro = heading + "\nMain " + deck.main().size() + " · Extra " + deck.extra().size()
                + " · Side " + deck.side().size() + " · updated <t:" + updatedAtMillis / 1000 + ":R>"
                + unknownCardsNote(deck, names);
        String uri = "\n\n**YDKE**\n```\n" + ydke + "\n```";
        if (names.size() == 0) {
            return List.of(intro + "\n_Card names are not loaded yet, try again in a few minutes._" + uri);
        }

        List<DcMessageUtils.Section> sections = new ArrayList<>();
        addZone(sections, "Main Deck", deck.main(), names);
        addZone(sections, "Extra Deck", deck.extra(), names);
        addZone(sections, "Side Deck", deck.side(), names);
        List<String> messages = new ArrayList<>(DcMessageUtils.packTables(intro, sections));
        int last = messages.size() - 1;
        if (messages.get(last).length() + uri.length() <= DcMessageUtils.MAX_MESSAGE_LENGTH) {
            messages.set(last, messages.get(last) + uri);
        } else {
            messages.add(uri.strip());
        }
        return messages;
    }

    /** Cards newer than the last card list download are still saved, but the user should know. */
    static String unknownCardsNote(YdkeDeck deck, CardNames names) {
        if (names.size() == 0) {
            return "";
        }
        int unknown = unknownCards(deck, names);
        return switch (unknown) {
            case 0 -> "";
            case 1 -> "\n⚠️ 1 card is not recognized (very new, or not a real passcode).";
            default -> "\n⚠️ " + unknown + " cards are not recognized (very new, or not real passcodes).";
        };
    }

    /** Number of passcodes in the deck that are not in {@code names}. */
    static int unknownCards(YdkeDeck deck, CardNames names) {
        int unknown = 0;
        for (List<Long> zone : List.of(deck.main(), deck.extra(), deck.side())) {
            for (long passcode : zone) {
                if (names.name(passcode).isEmpty()) {
                    unknown++;
                }
            }
        }
        return unknown;
    }

    private static void addZone(List<DcMessageUtils.Section> sections, String title, List<Long> passcodes,
                                CardNames names) {
        if (passcodes.isEmpty()) {
            return;
        }
        // Copies grouped, in order of first appearance
        Map<Long, Integer> copies = new LinkedHashMap<>();
        for (long passcode : passcodes) {
            copies.merge(passcode, 1, Integer::sum);
        }
        List<String> rows = new ArrayList<>(copies.size());
        copies.forEach((passcode, count) -> rows.add(count + "x " + DcMessageUtils.safe(
                names.name(passcode).orElse("Unknown card (" + passcode + ")"))));
        sections.add(new DcMessageUtils.Section("**" + title + "** · " + passcodes.size(),
                "**" + title + "** (continued)", "", rows));
    }
}
