package adris.altoclef.commands;

import adris.altoclef.TaskCatalogue;
import baritone.api.IBaritone;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.exception.CommandException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;

// pure client side, never touches the AltoClef instance (so it works even when altoclef failed to start)
public class ListCommand extends Command {

    public ListCommand(IBaritone baritone) {
        super(baritone, "list");
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        args.requireMax(1);
        String filter = args.hasAny() ? args.getString().toLowerCase(Locale.ROOT) : "";
        List<String> names = TaskCatalogue.resourceNames().stream()
                .filter(n -> n.contains(filter))
                .sorted()
                .collect(Collectors.toList());
        if (names.isEmpty()) {
            logDirect("Nothing in the catalogue matches \"" + filter + "\".");
            return;
        }
        logDirect(names.size() + " obtainable items" + (filter.isEmpty() ? "" : " matching \"" + filter + "\"") + ":");
        logDirect(String.join(", ", names));
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
        // the filter is a substring, so there is nothing sensible to finish
        return Stream.empty();
    }

    @Override
    public String getShortDesc() {
        return "List all obtainable items";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "The list command prints every item and group altoclef knows how to get, which is what get, give and friends accept.",
                "",
                "Give it a filter to only see the names that contain it.",
                "",
                "Usage:",
                "> list - Everything in the catalogue.",
                "> list <filter> - Only names containing the filter.",
                "",
                "Examples:",
                "> list pickaxe"
        );
    }
}
