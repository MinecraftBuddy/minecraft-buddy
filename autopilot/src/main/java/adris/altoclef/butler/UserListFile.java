package adris.altoclef.butler;

import adris.altoclef.util.helpers.ConfigHelper;
import adris.altoclef.util.serialization.IListConfigFile;

import java.util.HashSet;
import java.util.Locale;
import java.util.function.Consumer;

public class UserListFile implements IListConfigFile {

    private final HashSet<String> _users = new HashSet<>();

    public static void load(String path, Consumer<UserListFile> onLoad) {
        ConfigHelper.loadListConfig(path, UserListFile::new, onLoad);
    }

    // minecraft names are case-insensitive for every practical purpose, and "Jacob" vs "jacob" in a text file is
    // exactly the typo that makes the whitelist look broken
    private static String normalize(String name) {
        return name.trim().toLowerCase(Locale.ROOT);
    }

    public boolean containsUser(String username) {
        return username != null && _users.contains(normalize(username));
    }

    public boolean isEmpty() {
        return _users.isEmpty();
    }

    @Override
    public void onLoadStart() {
        _users.clear();
    }

    @Override
    public void addLine(String line) {
        if (line == null) {
            return;
        }
        String name = normalize(line);
        if (!name.isEmpty()) {
            _users.add(name);
        }
    }
}
