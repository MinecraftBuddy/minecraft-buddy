/*
 * This file is part of Soprano.
 *
 * Soprano is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Soprano is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Soprano.  If not, see <https://www.gnu.org/licenses/>.
 */

package adris.altoclef.commands;

import adris.altoclef.AltoClef;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import baritone.altoclef.AltoClefBridge;
import baritone.api.IBaritone;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.exception.CommandException;
import baritone.api.command.exception.CommandInvalidStateException;
import java.util.List;
import java.util.stream.Stream;

// base of every altoclef command. these are registered before altoclef exists (so tab completion and #help work from
// the title screen), which is why execute is the only place that asks for the instance
public abstract class AltoClefCommand extends Command {

    protected AltoClefCommand(IBaritone baritone, String... names) {
        super(baritone, names);
    }

    @Override
    public final void execute(String label, IArgConsumer args) throws CommandException {
        try {
            run(AltoClefBridge.require(), label, args);
        } catch (CommandException | RuntimeException e) {
            // so a butler line finds out it failed, the manager is about to print it for everyone else
            AltoClefCommands.failed(e);
            throw e;
        }
    }

    protected abstract void run(AltoClef mod, String label, IArgConsumer args) throws CommandException;

    // never created altoclef and never will, completion has to cope with that on its own
    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
        return Stream.empty();
    }

    // "iron_ingot 3 diamond 2" (or the [a 1, b 2] spelling) off the rest of the line, checked against what the slot
    // takes. eats all the arguments, a list is always the end of the line
    protected static ItemTarget[] parseItems(IArgConsumer args, AltoItem kind) throws CommandException {
        List<ItemArgs.Entry> entries;
        try {
            entries = ItemArgs.parse(args.rawRest());
        } catch (ItemArgs.ParseException e) {
            throw new CommandInvalidStateException(e.getMessage());
        }
        while (args.hasAny()) {
            args.get();
        }
        ItemTarget[] out = new ItemTarget[entries.size()];
        for (int i = 0; i < out.length; i++) {
            ItemArgs.Entry e = entries.get(i);
            if (!kind.accepts(e.name())) {
                throw new CommandInvalidStateException("\"" + e.name() + "\" is not " + switch (kind) {
                    case CATALOGUE -> "a catalogued resource, " + AltoClefCommands.prefix() + "list shows what there is";
                    case ANY_ITEM -> "an item or a catalogued resource";
                    case ARMOR -> "armor altoclef knows about";
                });
            }
            out[i] = kind.target(e.name(), e.count());
        }
        return out;
    }

    // runs a user task, and when it ends a butler or "a;b" line gets told so it can carry on
    protected final void startTask(AltoClef mod, Task task) {
        // a task the owner types in takes the bot away from whoever whispered the last one
        AltoClefCommands.noteOwnerTask(mod);
        mod.runUserTask(task, AltoClefCommands.takeFinish());
    }

    // for commands that are done by the time run() returns
    protected final void done() {
        AltoClefCommands.finishNow();
    }
}
