package adris.altoclef.commands;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.movement.GoToStrongholdPortalTask;
import adris.altoclef.tasks.movement.LocateDesertTempleTask;
import baritone.api.IBaritone;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.exception.CommandException;
import baritone.api.command.exception.CommandInvalidStateException;
import baritone.api.command.helpers.TabCompleteHelper;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

public class LocateStructureCommand extends AltoClefCommand {

    public LocateStructureCommand(IBaritone baritone) {
        // locate_structure is what altoclef called it, locatestructure is how soprano would spell it
        super(baritone, "locate_structure", "locatestructure");
    }

    public enum Structure {
        DESERT_TEMPLE,
        STRONGHOLD;

        public String commandName() {
            return name().toLowerCase(Locale.ROOT);
        }

        // desert_temple, DESERT_TEMPLE and deserttemple all work
        public static Structure parse(String word) {
            String w = word.replace("_", "");
            for (Structure s : values()) {
                if (s.name().replace("_", "").equalsIgnoreCase(w)) {
                    return s;
                }
            }
            return null;
        }
    }

    @Override
    protected void run(AltoClef mod, String label, IArgConsumer args) throws CommandException {
        args.requireExactly(1);
        String word = args.getString();
        Structure structure = Structure.parse(word);
        if (structure == null) {
            throw new CommandInvalidStateException("\"" + word + "\" is not a structure altoclef can locate, try one of: "
                    + String.join(", ", Arrays.stream(Structure.values()).map(Structure::commandName).toList()));
        }
        switch (structure) {
            case STRONGHOLD -> startTask(mod, new GoToStrongholdPortalTask(1));
            case DESERT_TEMPLE -> startTask(mod, new LocateDesertTempleTask());
        }
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
        if (args.hasExactlyOne()) {
            return new TabCompleteHelper()
                    .append(Arrays.stream(Structure.values()).map(Structure::commandName))
                    .filterPrefix(args.getString())
                    .sortAlphabetically()
                    .stream();
        }
        return Stream.empty();
    }

    @Override
    public String getShortDesc() {
        return "Locate a world generated structure";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "The locate_structure command has altoclef go and find a world generated structure.",
                "",
                "For the stronghold it heads for the portal room, for the desert temple it goes looking for the nearest one.",
                "",
                "Usage:",
                "> locate_structure <structure> - Find a desert_temple or the stronghold.",
                "",
                "Examples:",
                "> locatestructure desert_temple"
        );
    }
}
