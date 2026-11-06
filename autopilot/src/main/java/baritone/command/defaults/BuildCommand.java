/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.command.defaults;

import adris.altoclef.ui.gui.mc.McSchematics;
import baritone.Baritone;
import baritone.api.IBaritone;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.datatypes.RelativeBlockPos;
import baritone.api.command.datatypes.RelativeFile;
import baritone.api.command.exception.CommandException;
import baritone.api.command.exception.CommandInvalidStateException;
import baritone.api.schematic.IStaticSchematic;
import baritone.api.schematic.format.ISchematicFormat;
import baritone.api.utils.BetterBlockPos;
import baritone.utils.schematic.SchematicSystem;
import baritone.utils.schematic.library.SchematicPlacement;
import net.minecraft.core.BlockPos;
import org.apache.commons.io.FilenameUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.stream.Stream;

public class BuildCommand extends Command {

    private final File schematicsDir;

    public BuildCommand(IBaritone baritone) {
        super(baritone, "build");
        // the same folder (and so the same relative keys) the schematics tab places from, see SchematicLibrary.forGameDir
        this.schematicsDir = new File(baritone.getPlayerContext().minecraft().gameDirectory, "schematics");
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        if (!args.hasAny()) {
            listPlaced(label);
            return;
        }
        String typed = args.peekString();
        final File file0 = args.getDatatypePost(RelativeFile.INSTANCE, schematicsDir).getAbsoluteFile();
        File file = file0;
        // coordinates typed = the old command exactly. without them a file placed in the gui builds where it was put
        Map<String, SchematicPlacement> here = args.hasAny() ? Map.of() : pinnedHere();
        String placedKey = placedKey(typed, here);
        SchematicPlacement placed = null;
        if (placedKey != null) {
            placed = here.get(placedKey);
            file = new File(schematicsDir, placedKey).getAbsoluteFile();
        } else if (FilenameUtils.getExtension(file.getAbsolutePath()).isEmpty()) {
            file = new File(file.getAbsolutePath() + "." + Baritone.settings().schematicFallbackExtension.value);
        }
        if (!file.exists()) {
            if (file0.exists()) {
                throw new CommandInvalidStateException(String.format(
                        "Cannot load %s because I do not know which schematic format"
                                + " that is. Please rename the file to include the correct"
                                + " file extension.",
                        file));
            }
            throw new CommandInvalidStateException("Cannot find " + file);
        }
        Optional<ISchematicFormat> format = SchematicSystem.INSTANCE.getByFile(file);
        if (!format.isPresent()) {
            StringJoiner formats = new StringJoiner(", ");
            SchematicSystem.INSTANCE.getFileExtensions().forEach(formats::add);
            throw new CommandInvalidStateException(String.format(
                    "Unsupported schematic format. Reckognized file extensions are: %s",
                    formats
            ));
        }
        if (placed != null) {
            buildPlaced(file, format.get(), placed);
            return;
        }
        BetterBlockPos origin = ctx.playerFeet();
        BetterBlockPos buildOrigin;
        boolean atFeet = !args.hasAny();
        if (args.hasAny()) {
            args.requireMax(3);
            buildOrigin = args.getDatatypePost(RelativeBlockPos.INSTANCE, origin);
        } else {
            args.requireMax(0);
            buildOrigin = origin;
        }
        boolean success = baritone.getBuilderProcess().build(file.getName(), file, buildOrigin);
        if (!success) {
            throw new CommandInvalidStateException("Couldn't load the schematic. Either your schematic is corrupt or this is a bug.");
        }
        if (atFeet) {
            logDirect(String.format("Successfully loaded schematic for building at %s %s %s (at your feet)",
                    buildOrigin.x, buildOrigin.y, buildOrigin.z));
        } else {
            logDirect(String.format("Successfully loaded schematic for building\nOrigin: %s", buildOrigin));
        }
    }

    // the key of the gui placement the typed name means, null when there is none (or it's outside the folder, those
    // never had one). two files with the same name and no extension typed is a question only the player can answer
    private static String placedKey(String typed, Map<String, SchematicPlacement> here) throws CommandException {
        String key = BuildTargets.libraryKey(typed);
        if (key == null || here.isEmpty()) {
            return null;
        }
        List<String> hits = BuildTargets.matches(here.keySet(), key);
        if (hits.size() > 1) {
            throw new CommandInvalidStateException("More than one placed schematic is called " + typed + ": "
                    + String.join(", ", hits) + ". Type it with the extension");
        }
        return hits.isEmpty() ? null : hits.get(0);
    }

    // same call the schematics tab makes in creative: the placement's own corner, turn and flip. no gathering and no
    // unchecked rows, #build stays the plain builder it always was
    private void buildPlaced(File file, ISchematicFormat format, SchematicPlacement p) throws CommandException {
        IStaticSchematic parsed;
        try (InputStream in = new FileInputStream(file)) {
            parsed = format.parse(in);
        } catch (Exception e) {
            throw new CommandInvalidStateException("Couldn't load the schematic. Either your schematic is corrupt or this is a bug.");
        }
        BlockPos o = p.origin();
        baritone.getBuilderProcess().build(file.getName(), parsed, o, p.rotation(), p.mirror());
        logDirect(String.format("Successfully loaded schematic for building at %s %s %s (where you placed it)",
                o.getX(), o.getY(), o.getZ()));
    }

    private void listPlaced(String label) {
        getLongDesc().stream().filter(line -> line.startsWith(">")).forEach(this::logDirect);
        Map<String, SchematicPlacement> placed = pinnedHere();
        if (placed.isEmpty()) {
            logDirect("Nothing placed in this dimension yet. Place one in the Schematics tab (#gui), or type "
                    + label + " <file> to build at your feet");
            return;
        }
        logDirect("Placed in this dimension:");
        placed.forEach((path, p) -> logDirect("  " + path + " at " + p.origin().getX() + " " + p.origin().getY() + " "
                + p.origin().getZ()));
    }

    // the gui's live placements for this world and dimension. a broken gui must never take #build down with it, then
    // it's just the feet like upstream. the store only prunes deleted files when it loads, so a placement whose file
    // went away since is skipped here, or "#build castle" would chase a gone castle.schem past a castle.schematic
    private Map<String, SchematicPlacement> pinnedHere() {
        try {
            Map<String, SchematicPlacement> out = new LinkedHashMap<>(McSchematics.shared().pinnedHere());
            out.keySet().removeIf(path -> !new File(schematicsDir, path).isFile());
            return out;
        } catch (Throwable t) {
            return Map.of();
        }
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
        if (args.hasExactlyOne()) {
            return RelativeFile.tabComplete(args, schematicsDir);
        } else if (args.has(2)) {
            args.get();
            return args.tabCompleteDatatype(RelativeBlockPos.INSTANCE);
        }
        return Stream.empty();
    }

    @Override
    public String getShortDesc() {
        return "Build a schematic";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "Build a schematic from a file.",
                "",
                "Without coordinates it builds where you placed the file in the Schematics tab (#gui),",
                "turned and flipped the same way. Never placed in this dimension = at your feet.",
                "",
                "Usage:",
                "> build - Lists what you placed in this dimension",
                "> build <filename> - Builds it where you placed it, else at your feet ('.schematic' if no extension)",
                "> build <filename> <x> <y> <z> - Custom position"
        );
    }
}
