package adris.altoclef.commands;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.construction.SchematicBuildTask;
import baritone.api.IBaritone;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.exception.CommandException;
import baritone.api.command.exception.CommandInvalidStateException;
import baritone.api.command.exception.CommandInvalidTypeException;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

// answers for a survival schematic build (the schematics tab's "build here"). for now that's the nether question, the
// chat buttons run this with the question's id on the end, typing it works without one
public class SchemCommand extends AltoClefCommand {

    public SchemCommand(IBaritone baritone) {
        super(baritone, "schem");
    }

    @Override
    protected void run(AltoClef mod, String label, IArgConsumer args) throws CommandException {
        String what = args.getString().toLowerCase(Locale.ROOT);
        if (!what.equals("nether")) {
            throw new CommandInvalidStateException("schem only knows \"nether yes\" and \"nether no\" for now");
        }
        String answer = args.getString().toLowerCase(Locale.ROOT);
        if (!answer.equals("yes") && !answer.equals("no")) {
            throw new CommandInvalidTypeException(args.consumed(), "yes or no");
        }
        long id = args.hasAny() ? args.getAs(Long.class) : 0;
        args.requireMax(0);
        if (!(mod.getUserTaskChain().getCurrentTask() instanceof SchematicBuildTask build) || !build.askingNether()) {
            mod.log("No build is waiting on that question");
        } else if (!build.answerNether(answer.equals("yes"), id)) {
            // a button from an earlier question. the one that's up now has its own buttons further down the chat
            mod.log("That was an older question, answer the latest one");
        }
        done();
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
        if (args.hasExactlyOne()) {
            String typed = args.peekString().toLowerCase(Locale.ROOT);
            return Stream.of("nether").filter(s -> s.startsWith(typed));
        }
        args.get();
        if (args.hasExactlyOne()) {
            String typed = args.peekString().toLowerCase(Locale.ROOT);
            return Stream.of("yes", "no").filter(s -> s.startsWith(typed));
        }
        return Stream.empty();
    }

    @Override
    public String getShortDesc() {
        return "Answer a survival schematic build's questions";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "A survival build from the schematics tab asks before it goes to the nether for anything, and waits",
                "until you answer. Yes lets it go for the whole build, no leaves those blocks out (they show",
                "unchecked in the tab, check them again to build them next time).",
                "",
                "Usage:",
                "> schem nether yes - go to the nether for the build's nether items",
                "> schem nether no - build without them"
        );
    }
}
