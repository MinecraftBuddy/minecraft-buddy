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

package baritone.command.defaults;

import baritone.Baritone;
import baritone.api.IBaritone;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.exception.CommandException;
import baritone.api.command.exception.CommandInvalidStateException;
import baritone.api.command.helpers.TabCompleteHelper;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

// the answer to "may I collect some blocks?". the [Yes] and [No] in chat run this with the trip id on the end, so a
// click on an old question can't answer a new one. typed without an id it answers whatever is being asked now
public class GatherCommand extends Command {

    public GatherCommand(IBaritone baritone) {
        super(baritone, "gather");
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        args.requireMin(1);
        args.requireMax(2);
        String word = args.getString().toLowerCase(Locale.ROOT);
        boolean yes;
        if (word.equals("yes") || word.equals("y")) {
            yes = true;
        } else if (word.equals("no") || word.equals("n")) {
            yes = false;
        } else {
            throw new CommandInvalidStateException("yes or no");
        }
        Long id = null;
        if (args.hasAny()) {
            try {
                id = Long.parseLong(args.getString());
            } catch (NumberFormatException e) {
                throw new CommandInvalidStateException("that's not a trip id");
            }
        }
        String reply = ((Baritone) baritone).getTripBehavior().answer(id, yes);
        if (reply != null) {
            logDirect(reply);
        }
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
        if (args.hasExactlyOne()) {
            return new TabCompleteHelper().append("yes", "no").filterPrefix(args.getString()).stream();
        }
        return Stream.empty();
    }

    @Override
    public String getShortDesc() {
        return "Answer the question about collecting blocks for a path";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "When a trip you started can't be finished without blocks, or a much faster route needs blocks or a pickaxe,",
                "Soprano asks in chat if it may go and collect them (see the askToGatherForPath setting).",
                "Click [Yes] or [No] there, or answer with this command. The answer holds for the whole trip.",
                "",
                "Usage:",
                "> gather yes - go and collect what the path needs, then carry on",
                "> gather no - don't, for the rest of this trip"
        );
    }
}
