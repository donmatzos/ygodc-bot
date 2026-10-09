package at.magi.ygodiscordbot.command;

import org.testng.annotations.Test;

import java.util.List;
import java.util.Map;

import static org.testng.Assert.assertEquals;

public class CommandRegistryTest {

    @Test
    public void callLineHasCommandOptionsUserAndPlace() {
        assertEquals(CommandRegistry.callLine("points add", List.of(Map.entry("player", "123"), Map.entry("amount", "5")),
                        "yugi (123)", "server 9"),
                "/points add player:123 amount:5 by yugi (123) in server 9");
    }

    @Test
    public void callLineWithoutOptions() {
        assertEquals(CommandRegistry.callLine("help", List.of(), "yugi (123)", "bot DM"), "/help by yugi (123) in bot DM");
    }

    @Test
    public void longOptionValuesAreShortened() {
        String ydke = "ydke://" + "A".repeat(500);
        assertEquals(CommandRegistry.callLine("deck save", List.of(Map.entry("name", "Snake"), Map.entry("ydke", ydke)),
                        "yugi (123)", "bot DM"),
                "/deck save name:Snake ydke:<507 characters> by yugi (123) in bot DM");
    }
}
