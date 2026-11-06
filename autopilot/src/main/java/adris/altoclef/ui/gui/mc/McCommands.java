package adris.altoclef.ui.gui.mc;

import adris.altoclef.ui.gui.facade.CommandsAccess;
import adris.altoclef.ui.gui.facade.Result;
import adris.altoclef.ui.gui.kit.Safe;
import adris.altoclef.ui.gui.model.Wording;
import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.Settings;
import baritone.api.command.ICommand;
import baritone.api.command.argument.ICommandArgument;
import baritone.api.command.exception.CommandException;
import baritone.api.command.manager.ICommandManager;
import baritone.command.argument.ArgConsumer;
import baritone.command.manager.CommandManager;
import baritone.api.utils.Pair;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

// the command registry for the help tab and a way to run a line. runs the command itself rather than going through
// ICommandManager.execute: that one prints a failure to chat and tells us nothing, this one hands the message back
final class McCommands implements CommandsAccess {

    @Override
    public List<CommandInfo> commands() {
        List<CommandInfo> out = new ArrayList<>();
        try {
            for (ICommand c : manager().getRegistry().entries) {
                if (c.hiddenFromHelp() || c.getNames().isEmpty()) {
                    continue;
                }
                List<String> longDesc = new ArrayList<>();
                if (c.getLongDesc() != null) {
                    for (String line : c.getLongDesc()) {
                        longDesc.add(Wording.soprano(line));
                    }
                }
                String shortDesc = c.getShortDesc() == null ? "" : Wording.soprano(c.getShortDesc());
                out.add(new CommandInfo(List.copyOf(c.getNames()), shortDesc, List.copyOf(longDesc)));
            }
            out.sort(Comparator.comparing(CommandInfo::name));
        } catch (Throwable t) {
            Safe.report("commands list", t);
        }
        return out;
    }

    @Override
    public Result run(String line) {
        try {
            String trimmed = line == null ? "" : line.trim();
            String prefix = BaritoneAPI.getSettings().prefix.value;
            if (trimmed.startsWith(prefix)) {
                trimmed = trimmed.substring(prefix.length()).trim();
            }
            if (trimmed.isEmpty()) {
                return Result.fail("Type a command. Try help.");
            }
            ICommandManager manager = manager();
            Pair<String, List<ICommandArgument>> pair = CommandManager.expand(trimmed);
            String label = pair.first();
            ICommand command = manager.getCommand(label);
            if (command == null) {
                String viaSet = settingShortcut(label, pair.second(), trimmed.substring(label.length()));
                if (viaSet != null) {
                    return run(viaSet);
                }
                return Result.fail("No command called " + label + ". Try help.");
            }
            try {
                command.execute(label, new ArgConsumer(manager, pair.second()));
            } catch (CommandException e) {
                return Result.fail(e.getMessage() == null ? "That did not work." : Wording.soprano(e.getMessage()), trimmed);
            } catch (Throwable t) {
                Safe.report("command " + label, t);
                return Result.fail("That command broke. The log has the details.", trimmed);
            }
            // whatever the command said went to chat, it is not ours to catch (logDirect queues behind the click that
            // got us here, so there is nothing to hold a sink open for)
            return Result.ok("Ran " + trimmed, trimmed);
        } catch (Throwable t) {
            Safe.report("command run", t);
            return Result.fail("Could not run that.");
        }
    }

    // typing a setting's name on its own is a command too: "allowBreak" toggles, "primaryTimeoutMS 250" sets. a string
    // setting takes the rest of the line, same as #set ("altoIdleCommand follow Jacob")
    static String settingShortcut(String label, List<ICommandArgument> args, String rest) {
        Settings.Setting<?> s = BaritoneAPI.getSettings().findByLowerName(label.toLowerCase(Locale.US));
        if (s == null || s.isJavaOnly()) {
            return null;
        }
        if (args.isEmpty()) {
            return s.getValueClass() == Boolean.class ? "set toggle " + s.getName() : "set " + s.getName();
        }
        if (args.size() == 1 || s.getValueClass() == String.class) {
            return "set " + s.getName() + " " + rest.trim();
        }
        return null;
    }

    private static ICommandManager manager() {
        IBaritone b = BaritoneAPI.getProvider().getPrimaryBaritone();
        return b.getCommandManager();
    }
}
