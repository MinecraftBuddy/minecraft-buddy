package adris.altoclef.commands;

import adris.altoclef.AltoClef;
import adris.altoclef.tasks.entity.HuntTask;
import baritone.api.IBaritone;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.exception.CommandException;
import java.util.Arrays;
import java.util.List;

public class HuntCommand extends AltoClefCommand {

    public HuntCommand(IBaritone baritone) {
        super(baritone, "hunt");
    }

    @Override
    protected void run(AltoClef mod, String label, IArgConsumer args) throws CommandException {
        args.requireMax(0);
        startTask(mod, new HuntTask());
    }

    @Override
    public String getShortDesc() {
        return "Hunt the closest player";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "The hunt command chases the closest other player and attacks them until they die, then goes for the next one. It never stops on its own. Use it on people who agreed to it.",
                "",
                "Usage:",
                "> hunt - Hunt whoever is closest until you stop it."
        );
    }
}
