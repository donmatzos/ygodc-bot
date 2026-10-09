package at.magi.ygodiscordbot.format;

import at.magi.ygodiscordbot.utils.discord.DcMessageUtils;
import net.dv8tion.jda.api.interactions.commands.Command;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandGroupData;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/** Renders the /help text from the command definitions sent to Discord, so it never drifts from them. */
public final class HelpMessages {

    public static final String HEADER = "## 📖 Commands";

    private HelpMessages() {
    }

    /** One line per invocable form: the command itself, or each of its subcommands (also inside groups). */
    public static List<String> usages(SlashCommandData command) {
        String root = "/" + command.getName();
        if (command.getSubcommands().isEmpty() && command.getSubcommandGroups().isEmpty()) {
            return List.of(line(root, command.getOptions(), command.getDescription()));
        }
        List<String> lines = new ArrayList<>();
        for (SubcommandData sub : command.getSubcommands()) {
            lines.add(line(root + " " + sub.getName(), sub.getOptions(), sub.getDescription()));
        }
        for (SubcommandGroupData group : command.getSubcommandGroups()) {
            for (SubcommandData sub : group.getSubcommands()) {
                lines.add(line(root + " " + group.getName() + " " + sub.getName(), sub.getOptions(),
                        sub.getDescription()));
            }
        }
        return lines;
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
