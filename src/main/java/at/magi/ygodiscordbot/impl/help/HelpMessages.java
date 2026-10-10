package at.magi.ygodiscordbot.impl.help;

import at.magi.ygodiscordbot.utils.discord.DcMessageUtils;
import net.dv8tion.jda.api.interactions.commands.Command;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandGroupData;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/** Renders the /help text from the command definitions sent to Discord, so it never drifts from them. */
public final class HelpMessages {

    public static final String HEADER = "## 📖 Commands";

    private HelpMessages() {
    }

    /** One line per invocable form: the command itself, or each of its subcommands (also inside groups). */
    public static List<String> usages(SlashCommandData command) {
        return usages(command, path -> true, path -> "");
    }

    /**
     * Like {@link #usages(SlashCommandData)}, but only lines whose subcommand path (e.g. {@code add} or
     * {@code role add}; empty for a command without subcommands) passes {@code visible}, each followed by
     * {@code suffix} applied to that path.
     */
    public static List<String> usages(SlashCommandData command, Predicate<String> visible,
                                      Function<String, String> suffix) {
        String root = "/" + command.getName();
        List<String> lines = new ArrayList<>();
        if (command.getSubcommands().isEmpty() && command.getSubcommandGroups().isEmpty()) {
            if (visible.test("")) {
                lines.add(line(root, command.getOptions(), command.getDescription()) + suffix.apply(""));
            }
            return lines;
        }
        for (SubcommandData sub : command.getSubcommands()) {
            add(lines, root, sub.getName(), sub, visible, suffix);
        }
        for (SubcommandGroupData group : command.getSubcommandGroups()) {
            for (SubcommandData sub : group.getSubcommands()) {
                add(lines, root, group.getName() + " " + sub.getName(), sub, visible, suffix);
            }
        }
        return lines;
    }

    private static void add(List<String> lines, String root, String path, SubcommandData sub,
                            Predicate<String> visible, Function<String, String> suffix) {
        if (visible.test(path)) {
            lines.add(line(root + " " + path, sub.getOptions(), sub.getDescription()) + suffix.apply(path));
        }
    }

    public static List<String> build(List<String> lines) {
        return DcMessageUtils.packLines(HEADER, lines);
    }

    /** Required options as {@code name:<name>}, optional ones in brackets; fixed choices are listed by label. */
    private static String line(String path, List<OptionData> options, String description) {
        StringBuilder usage = new StringBuilder(path);
        for (OptionData option : options) {
            String placeholder = option.getChoices().isEmpty()
                    ? option.getName()
                    : option.getChoices().stream().map(Command.Choice::getName).collect(Collectors.joining("|"));
            String text = option.getName() + ":<" + placeholder + ">";
            usage.append(' ').append(option.isRequired() ? text : "[" + text + "]");
        }
        return "`" + usage + "` – " + description;
    }
}
