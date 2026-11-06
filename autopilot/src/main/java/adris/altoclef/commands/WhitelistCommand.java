package adris.altoclef.commands;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.butler.UserAuth;
import adris.altoclef.butler.WhitelistEditor;
import baritone.api.IBaritone;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.exception.CommandException;
import baritone.api.command.exception.CommandInvalidStateException;
import baritone.api.command.helpers.TabCompleteHelper;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

// who may whisper commands at the bot, without opening a text file. this is the owner's command: it is in the butler deny
// list, a trusted player can't whisper it to trust a friend of theirs
public class WhitelistCommand extends AltoClefCommand {

    private static final List<String> SUBCOMMANDS = List.of("add", "remove", "list");

    public WhitelistCommand(IBaritone baritone) {
        super(baritone, "whitelist");
    }

    @Override
    protected void run(AltoClef mod, String label, IArgConsumer args) throws CommandException {
        args.requireMin(1);
        args.requireMax(2);
        String first = args.getString();
        String sub = first.toLowerCase(Locale.ROOT);
        try {
            if (sub.equals("list") && !args.hasAny()) {
                list(mod);
            } else if (sub.equals("add") || sub.equals("remove")) {
                if (!args.hasAny()) {
                    throw new CommandInvalidStateException(sub + " needs a player name: whitelist " + sub + " <player>");
                }
                edit(mod, args.getString(), sub.equals("add"));
            } else if (args.hasAny()) {
                // "whitelist bob smith" is not "whitelist add bob", so don't quietly pick one
                throw new CommandInvalidStateException("try: whitelist <player>, whitelist remove <player> or whitelist list");
            } else {
                edit(mod, first, true);
            }
        } catch (IOException e) {
            throw new CommandInvalidStateException("couldn't update the whitelist file: " + e.getMessage());
        }
        done();
    }

    private static void edit(AltoClef mod, String name, boolean adding) throws IOException, CommandInvalidStateException {
        // only adding is checked: an odd entry typed into the file by hand has to be removable by name
        if (adding && !WhitelistEditor.isValidName(name)) {
            throw new CommandInvalidStateException("\"" + name + "\" doesn't look like a minecraft name (letters, digits, _ and ., 16 at most)");
        }
        // Debug and not mod.log: mod.log is copied to whoever has the bot on a whisper, and this is not theirs to read
        switch (mod.getButler().editWhitelist(name, adding)) {
            case ADDED -> Debug.logMessage("Added " + name + " to the whitelist, they can whisper commands to the bot now.");
            case ALREADY_THERE -> Debug.logMessage(name + " is already on the whitelist.");
            case REMOVED -> Debug.logMessage("Removed " + name + " from the whitelist.");
            case NOT_THERE -> Debug.logMessage(name + " is not on the whitelist.");
        }
    }

    private static void list(AltoClef mod) throws IOException {
        List<String> names = mod.getButler().whitelistNames();
        if (names.isEmpty()) {
            Debug.logMessage("Nobody is on the whitelist. whitelist <player> adds someone.");
        } else {
            Debug.logMessage("On the whitelist (" + names.size() + "): " + String.join(", ", names));
        }
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
        if (args.hasExactlyOne()) {
            return new TabCompleteHelper()
                    .append(SUBCOMMANDS.stream())
                    .append(TabListPlayer.online().stream())
                    .filterPrefix(args.getString())
                    .sortAlphabetically()
                    .stream();
        }
        if (args.hasExactly(2)) {
            String sub = args.getString().toLowerCase(Locale.ROOT);
            if (sub.equals("add")) {
                return args.tabCompleteDatatype(TabListPlayer.INSTANCE);
            }
            if (sub.equals("remove")) {
                try {
                    return new TabCompleteHelper()
                            .append(UserAuth.whitelistNames().stream())
                            .filterPrefix(args.getString())
                            .sortAlphabetically()
                            .stream();
                } catch (IOException e) {
                    return Stream.empty();
                }
            }
        }
        return Stream.empty();
    }

    @Override
    public String getShortDesc() {
        return "Choose who can whisper commands to the bot";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "The whitelist command edits altoclef_butler_whitelist.txt for you. A player on it can whisper commands to the bot (/msg <bot> get cobblestone 4) while the altoButler setting is on, which it is by default. Nobody is on it to begin with, so the bot ignores every whisper until you add someone.",
                "",
                "It takes effect right away, no altoreload needed. Names are not case sensitive, and the comments in the file stay as they were.",
                "",
                "This is the owner's command: a whisper can never run it.",
                "",
                "Usage:",
                "> whitelist <player> - Let that player whisper commands to the bot.",
                "> whitelist remove <player> - Take them off again.",
                "> whitelist list - Show who is on it."
        );
    }
}
