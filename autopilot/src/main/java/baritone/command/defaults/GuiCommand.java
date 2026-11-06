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

import adris.altoclef.ui.gui.mc.GuiHotkey;
import baritone.api.IBaritone;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.exception.CommandException;
import baritone.api.command.exception.CommandInvalidStateException;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

public class GuiCommand extends Command {

    public GuiCommand(IBaritone baritone) {
        super(baritone, "gui", "clickgui");
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        args.requireMax(0);
        String problem = GuiHotkey.open();
        if (problem != null) {
            throw new CommandInvalidStateException(problem);
        }
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) {
        return Stream.empty();
    }

    @Override
    public String getShortDesc() {
        return "Open the click gui";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "Opens the click gui: every button in it is a chat command, and it shows you which one it ran.",
                "",
                "Right shift opens it by default. To use another key, `set guiKey <code>` (a GLFW key code, -1 for none) or use the key control in its Settings tab. `set guiKeyEnabled false` turns the key off.",
                "This command works whether or not the key is bound.",
                "",
                "Usage:",
                "> gui - Open the click gui"
        );
    }
}
