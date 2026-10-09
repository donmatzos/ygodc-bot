package at.magi.ygodiscordbot.entity.card;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;

/**
 * Card name per passcode, immutable. Stored as a sorted passcode array plus a parallel name array (about 2 MB
 * for the whole game) instead of a map, and looked up by binary search. Alternate artworks have their own
 * passcode and share the card's name instance.
 */
public final class CardNames {

    public static final CardNames EMPTY = new CardNames(new int[0], new String[0]);

    private final int[] passcodes;
    private final String[] names;

    private CardNames(int[] passcodes, String[] names) {
        this.passcodes = passcodes;
        this.names = names;
    }

    public static CardNames of(Map<Integer, String> byPasscode) {
        int[] passcodes = byPasscode.keySet().stream().mapToInt(Integer::intValue).sorted().toArray();
        String[] names = new String[passcodes.length];
        for (int i = 0; i < passcodes.length; i++) {
            names[i] = byPasscode.get(passcodes[i]);
        }
        return new CardNames(passcodes, names);
    }

    /** YDKE passcodes are unsigned 32-bit; real passcodes all fit in an int. */
    public Optional<String> name(long passcode) {
        if (passcode < 0 || passcode > Integer.MAX_VALUE) {
            return Optional.empty();
        }
        int index = Arrays.binarySearch(passcodes, (int) passcode);
        return index >= 0 ? Optional.of(names[index]) : Optional.empty();
    }

    public int size() {
        return passcodes.length;
    }

    public int passcodeAt(int index) {
        return passcodes[index];
    }

    public String nameAt(int index) {
        return names[index];
    }
}
