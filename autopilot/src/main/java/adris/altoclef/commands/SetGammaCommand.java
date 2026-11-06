package adris.altoclef.commands;

import baritone.api.IBaritone;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.exception.CommandException;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import net.minecraft.client.Minecraft;

// a video setting, nothing to do with the AltoClef instance
public class SetGammaCommand extends Command {

    public SetGammaCommand(IBaritone baritone) {
        super(baritone, "gamma", "setgamma", "set_gamma");
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        double gamma = args.getAsOrDefault(Double.class, 1.0);
        args.requireMax(0);
        Minecraft.getInstance().options.gamma().set(gamma);
        logDirect("Gamma set to " + gamma);
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
        if (args.hasExactlyOne()) {
            String typed = args.peekString();
            return Stream.of("1.0", "5.0", "15.0").filter(s -> s.startsWith(typed));
        }
        return Stream.empty();
    }

    @Override
    public String getShortDesc() {
        return "Set the brightness";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "The gamma command sets the brightness option to any number, so you can go past what the video settings slider allows.",
                "",
                "With no number it goes back to 1.0.",
                "",
                "Usage:",
                "> gamma [value] - Set the brightness.",
                "",
                "Examples:",
                "> gamma 15"
        );
    }
}
