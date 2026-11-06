package adris.altoclef.commands;

import adris.altoclef.AltoClef;
import adris.altoclef.util.helpers.ConfigHelper;
import baritone.api.IBaritone;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.exception.CommandException;
import java.util.Arrays;
import java.util.List;

public class ReloadSettingsCommand extends AltoClefCommand {

    public ReloadSettingsCommand(IBaritone baritone) {
        // reload_settings is the old name. the settings live in #set now, this is for the json files that are left
        super(baritone, "altoreload", "reload_settings", "reloadsettings");
    }

    @Override
    protected void run(AltoClef mod, String label, IArgConsumer args) throws CommandException {
        args.requireMax(0);
        ConfigHelper.reloadAllConfigs();
        mod.log("Reload successful!");
        done();
    }

    @Override
    public String getShortDesc() {
        return "Reload AltoClef's json configs";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "The altoreload command reloads AltoClef's json config files from disk: the beat minecraft, food chain, block tracker and mlg configs, the butler config and its whitelist and blacklist, and the custom tasks.",
                "AltoClef's plain settings are not in there anymore, they are normal settings: use `set alto<tab>` to see and change them.",
                "",
                "Usage:",
                "> altoreload"
        );
    }
}
