/*
 * This file is part of Soprano.
 *
 * Soprano is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Soprano is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Soprano.  If not, see <https://www.gnu.org/licenses/>.
 */

package adris.altoclef;

import adris.altoclef.util.helpers.ConfigHelper;
import baritone.Baritone;
import baritone.api.Settings;
import baritone.api.utils.BlockRange;
import baritone.api.utils.Dimension;
import baritone.api.utils.SettingsUtil;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

// altoclef used to keep its settings in baritone/altoclef/altoclef_settings.json. they are soprano settings now
// (altoShowTaskChains and friends in settings.txt), this carries what somebody had changed over once and gets the old
// file out of the way. the names map by rule, old "foo" is "altoFoo", so the ones that were dropped
// (commandPrefix, chatLogPrefix, useCraftingBookToCraft) just have nothing to land on
public final class AltoSettingsMigration {

    public static final String OLD_FILE = "altoclef_settings.json";
    public static final String MIGRATED_FILE = OLD_FILE + ".migrated";
    public static final String FAILED_FILE = OLD_FILE + ".failed";

    public enum Outcome {
        NO_FILE,
        IMPORTED,
        // not json (or not utf-8): nothing was imported and the file was moved out of the way
        BAD_FILE,
        // could not read the file or get it out of the way, so nothing was imported
        STUCK,
        // already looked at it this session
        SKIPPED
    }

    // the files we already dealt with in this session, so a file that refuses to move is never looked at twice
    private static final Set<Path> SEEN = new HashSet<>();

    // how a file gets out of the way, a test swaps it for one that always fails
    interface Retirer {
        boolean retire(Path file, Path target);
    }

    private AltoSettingsMigration() {
    }

    // called on startup, does nothing at all when the old file is not there
    public static void migrateIfNeeded() {
        try {
            Settings settings = Baritone.settings();
            migrateIfNeeded(ConfigHelper.getConfigFolder(), settings, () -> SettingsUtil.save(settings));
        } catch (RuntimeException e) {
            // the settings are fine without this, a startup that dies over an old file is not
            Debug.logWarning("Could not move " + OLD_FILE + " into the normal settings: " + e);
        }
    }

    public static Outcome migrateIfNeeded(Path dir, Settings settings, Runnable save) {
        return migrateIfNeeded(dir, settings, save, AltoSettingsMigration::moveOrDelete);
    }

    // the order is the whole point: read and parse first (nothing touched yet), then get the file out of the way, and
    // only then apply. a file that can't be moved or deleted (a lock, a folder squatting on the new name) is never
    // imported, because it would come back on every start and put the old values over whatever was #set since
    static Outcome migrateIfNeeded(Path dir, Settings settings, Runnable save, Retirer retirer) {
        Path file = dir.resolve(OLD_FILE);
        if (!Files.isRegularFile(file)) {
            return Outcome.NO_FILE;
        }
        if (!SEEN.add(file.toAbsolutePath().normalize())) {
            return Outcome.SKIPPED;
        }
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (IOException | RuntimeException e) {
            // not the file's fault (a lock, permissions), so it stays where it is
            Debug.logWarning("Could not read " + OLD_FILE + ", its settings were not imported: " + e);
            return Outcome.STUCK;
        }
        JsonObject json;
        try {
            json = parse(bytes);
        } catch (IOException | RuntimeException e) {
            // an empty file, binary junk, a json array, broken json. it is not getting better, so it goes to .failed
            // and we say so once instead of on every start
            boolean moved = retirer.retire(file, dir.resolve(FAILED_FILE));
            Debug.logWarning(OLD_FILE + " is not a valid settings file (" + e.getMessage() + "), nothing was imported from it"
                    + (moved ? " and it is now " + FAILED_FILE : ", and it could not be moved out of the way, you can delete it"));
            return Outcome.BAD_FILE;
        }
        if (!retirer.retire(file, dir.resolve(MIGRATED_FILE))) {
            Debug.logWarning("Could not move " + OLD_FILE + " out of the way, so its settings were not imported. Delete it or move it by hand");
            return Outcome.STUCK;
        }
        int moved = apply(json, settings);
        if (moved > 0) {
            save.run();
        }
        Debug.logMessage(moved > 0
                ? "Moved " + moved + " AltoClef settings from " + OLD_FILE + " into the normal settings (#set alto<tab>), the old file is now " + MIGRATED_FILE
                : "AltoClef's " + OLD_FILE + " had nothing changed in it, its settings are normal settings now (#set alto<tab>), the file is now " + MIGRATED_FILE);
        return Outcome.IMPORTED;
    }

    // rename, and when that fails (windows lock, a directory sitting on the target) delete. true when the file is gone
    // from where it was
    private static boolean moveOrDelete(Path file, Path target) {
        try {
            Files.move(file, target, StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException | RuntimeException e) {
            try {
                Files.deleteIfExists(file);
                return !Files.exists(file);
            } catch (IOException | RuntimeException e2) {
                return false;
            }
        }
    }

    // strict utf-8, because a lossy decode would happily turn binary junk into something that half parses
    private static JsonObject parse(byte[] bytes) throws IOException {
        String text = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
        if (text.startsWith("﻿")) {
            text = text.substring(1);
        }
        // an empty file parses as JsonNull instead of failing, which is not an object either
        JsonElement root = JsonParser.parseString(text);
        if (!root.isJsonObject()) {
            throw new IOException("expected a json object");
        }
        return root.getAsJsonObject();
    }

    // sets every value in the file that differs from the new default, returns how many that was. no saving, no
    // renaming, so it can be tested on its own
    public static int migrate(Path file, Settings settings) throws IOException {
        return apply(parse(Files.readAllBytes(file)), settings);
    }

    private static int apply(JsonObject json, Settings settings) {
        int moved = 0;
        for (var entry : json.entrySet()) {
            String oldName = entry.getKey();
            if (oldName.isEmpty()) {
                // "" has no first letter to capitalize, skip it and keep going
                continue;
            }
            // replantCrops is the one that already existed in soprano and was reused instead of getting a twin
            String newName = oldName.equals("replantCrops")
                    ? "replantCrops"
                    : "alto" + Character.toUpperCase(oldName.charAt(0)) + oldName.substring(1);
            Settings.Setting<?> setting = settings.byLowerName.get(newName.toLowerCase(Locale.ROOT));
            if (setting == null) {
                continue;
            }
            if (!Objects.equals(setting.value, setting.defaultValue)) {
                // already changed in settings.txt, and that one wins over a file nobody has touched in months
                continue;
            }
            try {
                Object value = convert(setting, entry.getValue());
                if (value != null && !Objects.equals(value, setting.defaultValue)) {
                    set(setting, value);
                    moved++;
                }
            } catch (RuntimeException e) {
                // one bad value should not cost the user the rest of the file
                Debug.logWarning("Skipped " + oldName + " from " + OLD_FILE + ": " + e.getMessage());
            }
        }
        return moved;
    }

    private static Object convert(Settings.Setting<?> setting, JsonElement json) {
        if (setting.getName().equals("altoAreasToProtect")) {
            List<BlockRange> ranges = new ArrayList<>();
            for (JsonElement element : json.getAsJsonArray()) {
                JsonObject o = element.getAsJsonObject();
                BlockPos start = ConfigHelper.fromJson(o.get("start"), BlockPos.class);
                BlockPos end = ConfigHelper.fromJson(o.get("end"), BlockPos.class);
                Dimension dimension = o.has("dimension") && !o.get("dimension").isJsonNull()
                        ? Dimension.valueOf(o.get("dimension").getAsString())
                        : Dimension.OVERWORLD;
                ranges.add(new BlockRange(start, end, dimension));
            }
            return ranges;
        }
        return ConfigHelper.fromJson(json, setting.getType());
    }

    @SuppressWarnings("unchecked")
    private static <T> void set(Settings.Setting<T> setting, Object value) {
        setting.value = (T) value;
    }
}
