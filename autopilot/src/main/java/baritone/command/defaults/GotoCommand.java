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

import baritone.Baritone;
import adris.altoclef.AltoClef;
import adris.altoclef.commands.CrossDimensionGoto;
import adris.altoclef.tasksystem.Task;
import baritone.altoclef.AltoClefBridge;
import baritone.api.IBaritone;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.argument.ICommandArgument;
import baritone.api.command.datatypes.ForBlockOptionalMeta;
import baritone.api.command.datatypes.RelativeCoordinate;
import baritone.api.command.datatypes.RelativeGoal;
import baritone.api.command.exception.CommandException;
import baritone.api.command.exception.CommandInvalidStateException;
import baritone.api.pathing.goals.Goal;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.BlockOptionalMeta;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class GotoCommand extends Command {

    protected GotoCommand(IBaritone baritone) {
        super(baritone, "goto");
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        // a dimension on the end means altoclef's goto: it walks through portals to get there, baritone alone can't
        List<String> words = args.getArgs().stream().map(ICommandArgument::getValue).collect(Collectors.toList());
        if (CrossDimensionGoto.endsInDimension(words)) {
            Task task;
            try {
                task = CrossDimensionGoto.parse(words);
            } catch (CrossDimensionGoto.ParseException e) {
                throw new CommandInvalidStateException(e.getMessage());
            }
            AltoClef altoClef = AltoClefBridge.require();
            while (args.hasAny()) {
                args.get();
            }
            logDirect(String.format("Going to: %s", String.join(" ", words)));
            altoClef.getButler().onOwnerTask();
            altoClef.runUserTask(task);
            return;
        }
        // If we have a numeric first argument, then parse arguments as coordinates.
        // Note: There is no reason to want to go where you're already at so there
        // is no need to handle the case of empty arguments.
        if (args.peekDatatypeOrNull(RelativeCoordinate.INSTANCE) != null) {
            args.requireMax(3);
            BetterBlockPos origin = ctx.playerFeet();
            Goal goal = args.getDatatypePost(RelativeGoal.INSTANCE, origin);
            logDirect(String.format("Going to: %s", goal.toString()));
            ((Baritone) baritone).getTripBehavior().userTrip(goal);
            baritone.getCustomGoalProcess().setGoalAndPath(goal);
            return;
        }
        args.requireMax(1);
        BlockOptionalMeta destination = args.getDatatypeFor(ForBlockOptionalMeta.INSTANCE);
        baritone.getGetToBlockProcess().getToBlock(destination);
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
        // since it's either a goal or a block, I don't think we can tab complete properly?
        // so just tab complete for the block variant, plus the dimension that can follow up to three numbers
        int count = args.getArgs().size();
        String prefix = args.peekString(count - 1).toLowerCase(Locale.ROOT);
        Stream<String> dimensions = CrossDimensionGoto.DIMENSION_NAMES.stream().filter(d -> d.startsWith(prefix));
        if (count == 1) {
            return Stream.concat(args.tabCompleteDatatype(ForBlockOptionalMeta.INSTANCE), dimensions);
        }
        // only numbers before the word being typed, anything else isn't a coordinate and has no dimension after it
        for (int i = 0; i < count - 1; i++) {
            if (!args.peekString(i).matches("[+-]?\\d+")) {
                return Stream.empty();
            }
        }
        return count <= 4 ? dimensions : Stream.empty();
    }

    @Override
    public String getShortDesc() {
        return "Go to a coordinate or block";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "The goto command tells Baritone to head towards a given goal or block.",
                "",
                "Wherever a coordinate is expected, you can use ~ just like in regular Minecraft commands. Or, you can just use regular numbers.",
                "",
                "End it with a dimension (overworld, nether or the_end) and AltoClef takes over: it finds a portal and goes there, even from another dimension. Coordinates have to be plain numbers then. A dimension on its own just takes you to that dimension.",
                "",
                "Usage:",
                "> goto <block> - Go to a block, wherever it is in the world",
                "> goto <y> - Go to a Y level",
                "> goto <x> <z> - Go to an X,Z position",
                "> goto <x> <y> <z> - Go to an X,Y,Z position",
                "> goto <dimension> - Go to another dimension",
                "> goto <x> <y> <z> <dimension> - Go to an X,Y,Z position in that dimension (<y> and <x> <z> work too)"
        );
    }
}
