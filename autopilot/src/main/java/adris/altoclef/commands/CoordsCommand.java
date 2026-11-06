package adris.altoclef.commands;

import adris.altoclef.AltoClef;
import adris.altoclef.util.helpers.WorldHelper;
import baritone.api.IBaritone;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.exception.CommandException;
import java.util.Arrays;
import java.util.List;

public class CoordsCommand extends AltoClefCommand {

    public CoordsCommand(IBaritone baritone) {
        super(baritone, "coords");
    }

    @Override
    protected void run(AltoClef mod, String label, IArgConsumer args) throws CommandException {
        args.requireMax(0);
        mod.log("CURRENT COORDINATES: " + mod.getPlayer().blockPosition().toShortString() + " (Current dimension: " + WorldHelper.getCurrentDimension() + ")");
        done();
    }

    @Override
    public String getShortDesc() {
        return "Get the bot's current coordinates";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "The coords command says where you are and which dimension you are in. A butler whispers it back to whoever asked.",
                "",
                "Usage:",
                "> coords"
        );
    }
}
