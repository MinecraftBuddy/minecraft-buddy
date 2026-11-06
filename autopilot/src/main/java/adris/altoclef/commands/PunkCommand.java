package adris.altoclef.commands;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.entity.KillPlayerTask;
import baritone.api.IBaritone;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.exception.CommandException;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

public class PunkCommand extends AltoClefCommand {

    public PunkCommand(IBaritone baritone) {
        super(baritone, "punk");
    }

    @Override
    protected void run(AltoClef mod, String label, IArgConsumer args) throws CommandException {
        args.requireExactly(1);
        String playerName = args.getDatatypeFor(TabListPlayer.INSTANCE);
        startTask(mod, new KillPlayerTask(playerName));
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
        if (args.hasExactlyOne()) {
            return args.tabCompleteDatatype(TabListPlayer.INSTANCE);
        }
        return Stream.empty();
    }

    @Override
    public String getShortDesc() {
        return "Punk 'em";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "The punk command has altoclef hunt down a player and kill them. Use it on people who agreed to it.",
                "",
                "The names come from the tab list.",
                "",
                "Usage:",
                "> punk <player> - Kill that player."
        );
    }
}
