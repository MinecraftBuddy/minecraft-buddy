package adris.altoclef.butler;

import adris.altoclef.AltoClef;
import adris.altoclef.util.helpers.ConfigHelper;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

public class UserAuth {
    private static final String BLACKLIST_PATH = "altoclef_butler_blacklist.txt";
    private static final String WHITELIST_PATH = "altoclef_butler_whitelist.txt";
    private final AltoClef _mod;
    private UserListFile _blacklist;
    private UserListFile _whitelist;

    public UserAuth(AltoClef mod) {
        _mod = mod;

        ConfigHelper.ensureCommentedListFileExists(BLACKLIST_PATH, """
                Add butler blacklisted players here, one name per line (case does not matter).
                A blacklisted player is refused even when they are on the whitelist, as long as useButlerBlacklist is true in configs/butler.json.
                Anything after a pound sign (#) will be ignored.""");
        ConfigHelper.ensureCommentedListFileExists(WHITELIST_PATH, """
                Add butler whitelisted players here, one name per line (case does not matter).
                Only these players can whisper commands to the bot. An empty list means nobody can.
                The whitelist command in game edits this file for you (whitelist <name>, whitelist remove <name>, whitelist list).
                Anything after a pound sign (#) will be ignored.""");

        UserListFile.load(BLACKLIST_PATH, newList -> _blacklist = newList);
        UserListFile.load(WHITELIST_PATH, newList -> _whitelist = newList);
    }

    private static Path whitelistFile() {
        return ConfigHelper.getConfigFolder().resolve(WHITELIST_PATH);
    }

    // edits the file and reads it again right away, so the answer is live without an altoreload. if the write fails we
    // throw before touching the loaded list, which keeps saying what the file said last
    public WhitelistEditor.Outcome editWhitelist(String name, boolean adding) throws IOException {
        WhitelistEditor.Outcome outcome = WhitelistEditor.apply(whitelistFile(), name, adding);
        reloadWhitelist();
        return outcome;
    }

    public void reloadWhitelist() {
        UserListFile.load(WHITELIST_PATH, newList -> _whitelist = newList);
    }

    // straight from the file, so tab completion works before there is a bot
    public static List<String> whitelistNames() throws IOException {
        return WhitelistEditor.names(WhitelistEditor.read(whitelistFile()));
    }

    public boolean isUserAuthorized(String username) {
        return isAuthorized(_whitelist, _blacklist, ButlerConfig.getInstance().useButlerBlacklist, username);
    }

    // the whitelist is not optional: an empty or missing one means nobody gets to boss the bot around, and so does a
    // list that failed to load (null). the blacklist is checked on top and wins, and when it is switched on but could
    // not be read we also say no, because we can't tell who is on it
    public static boolean isAuthorized(UserListFile whitelist, UserListFile blacklist, boolean useBlacklist, String username) {
        if (username == null || whitelist == null) {
            return false;
        }
        if (useBlacklist && (blacklist == null || blacklist.containsUser(username))) {
            return false;
        }
        return whitelist.containsUser(username);
    }

    // not about who may command the bot: who the force field and the terminator leave alone. that was always "anyone who is
    // not blacklisted" by default, and switching the butler to whitelist only must not turn it into "attack everyone who
    // is not on the whitelist", so it keeps the old answer
    public boolean isUserSpared(String username) {
        return !ButlerConfig.getInstance().useButlerBlacklist || _blacklist == null || !_blacklist.containsUser(username);
    }

}
