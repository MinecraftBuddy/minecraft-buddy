package baritone.utils.schematic.library;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

// <save>/altoclef/schematic-placements.json, one placement per library path, plus the build choices (BuildChoices) for
// the files somebody made any. same load/save shape as RunStateStore: plain json a human can read, tmp file then move,
// and nothing here throws. only takes a Path so tests use a temp dir
public final class PlacementStore {

    public static final String FILE_NAME = "schematic-placements.json";

    private static final int SCHEMA = 1;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // what's on disk. plain fields and enum names, no minecraft types, so gson needs no adapters
    private static final class FileData {
        int schema = SCHEMA;
        Map<String, Entry> placements = new LinkedHashMap<>();
        // null when nobody chose anything, gson leaves it out and the file looks the way it always did. an older
        // soprano reads past it (and drops it on its next save, which only costs the checkboxes)
        Map<String, Choice> choices;
    }

    private static final class Choice {
        List<String> excluded;
        // "yes" / "no", left out while nobody asked
        String nether;
    }

    // both halves of the file, read in one go
    public record Saved(Map<String, SchematicPlacement> placements, Map<String, BuildChoices> choices) {
    }

    private static final class Entry {
        // boxed so a missing one is null, not a silent 0
        Integer x;
        Integer y;
        Integer z;
        String rotation;
        String mirror;
        String dimension;
    }

    private PlacementStore() {
    }

    // every usable entry whose file still exists. a broken file is moved aside and reads as empty, a broken entry (a
    // typo'd number, a missing coordinate, a turn we don't know) is dropped on its own. exists gets the relative path,
    // so a renamed or deleted schematic stops haunting the file. null = the file is there but couldn't be read right
    // now (locked by a virus scanner, say): the caller must not save over it, that would wipe every other placement
    public static Map<String, SchematicPlacement> load(Path file, Predicate<String> exists) {
        Saved saved = loadAll(file, exists);
        return saved == null ? null : saved.placements();
    }

    // load, and the choices too. same rules: a gone file loses its choices, null = couldn't read it right now
    public static Saved loadAll(Path file, Predicate<String> exists) {
        try {
            return read(file, exists);
        } catch (RuntimeException e) {
            // that's us failing to understand the file (gson on mangled field names), not the file being broken. null
            // blocks saving over it, and no moveAside: renaming a good file to .bad would be everyone's placements gone
            return null;
        }
    }

    private static Saved read(Path file, Predicate<String> exists) {
        Map<String, SchematicPlacement> out = new LinkedHashMap<>();
        Map<String, BuildChoices> choices = new LinkedHashMap<>();
        Saved empty = new Saved(out, choices);
        if (file == null || !Files.exists(file)) {
            return empty;
        }
        if (!Files.isRegularFile(file)) {
            // a folder (or something stranger) squatting on the name. not ours to delete, and a save couldn't land anyway
            return null;
        }
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (CharacterCodingException e) {
            // it read fine, it's just not text. that's corrupt, not locked
            moveAside(file);
            return empty;
        } catch (IOException e) {
            System.out.println("schematic placements: could not read " + file + " (" + e.getMessage() + ")");
            return null;
        }
        JsonObject root = rootOf(text);
        if (root == null) {
            moveAside(file);
            return empty;
        }
        for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("placements").entrySet()) {
            SchematicPlacement p = parse(e.getValue());
            if (p != null && safeTest(exists, e.getKey())) {
                out.put(e.getKey(), p);
            }
        }
        // a broken choices block costs the checkboxes and nothing else, the placements are the part that matters
        JsonElement chosen = root.get("choices");
        if (chosen != null && chosen.isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : chosen.getAsJsonObject().entrySet()) {
                BuildChoices c = parseChoice(e.getValue());
                if (c != null && !c.isEmpty() && safeTest(exists, e.getKey())) {
                    choices.put(e.getKey(), c);
                }
            }
        }
        return empty;
    }

    // the whole object, or null for a file that's corrupt, empty, has no placements, or is from a newer soprano
    // (which might know things we'd throw away on the next save)
    private static JsonObject rootOf(String text) {
        try {
            JsonElement root = JsonParser.parseString(text);
            if (!root.isJsonObject()) {
                return null;
            }
            JsonObject obj = root.getAsJsonObject();
            JsonElement schema = obj.get("schema");
            if (schema != null && (!schema.isJsonPrimitive() || schema.getAsInt() > SCHEMA)) {
                return null;
            }
            JsonElement placements = obj.get("placements");
            return placements != null && placements.isJsonObject() ? obj : null;
        } catch (JsonParseException | IllegalStateException | NumberFormatException | UnsupportedOperationException e) {
            return null;
        }
    }

    private static boolean safeTest(Predicate<String> exists, String path) {
        try {
            return exists.test(path);
        } catch (RuntimeException e) {
            // a path windows can't even make sense of is as gone as a deleted one
            return false;
        }
    }

    private static SchematicPlacement parse(JsonElement json) {
        Entry e;
        try {
            e = GSON.fromJson(json, Entry.class);
        } catch (JsonParseException | IllegalStateException | NumberFormatException e2) {
            return null;
        }
        if (e == null || e.x == null || e.y == null || e.z == null || e.dimension == null || e.dimension.isEmpty()) {
            return null;
        }
        Rotation rotation = enumOr(Rotation.class, e.rotation, Rotation.NONE);
        Mirror mirror = enumOr(Mirror.class, e.mirror, Mirror.NONE);
        if (rotation == null || mirror == null) {
            return null;
        }
        return new SchematicPlacement(new BlockPos(e.x, e.y, e.z), rotation, mirror, e.dimension);
    }

    // only the strings of the list, a hand typed 3 or null in there is somebody's typo and not an item
    private static BuildChoices parseChoice(JsonElement json) {
        if (!json.isJsonObject()) {
            return null;
        }
        JsonElement answer = json.getAsJsonObject().get("nether");
        Boolean nether = null;
        if (answer != null && answer.isJsonPrimitive() && answer.getAsJsonPrimitive().isString()) {
            // anything else is a hand edit we don't follow, and "not asked" is the safe reading of it
            nether = switch (answer.getAsString()) {
                case "yes" -> Boolean.TRUE;
                case "no" -> Boolean.FALSE;
                default -> null;
            };
        }
        JsonElement list = json.getAsJsonObject().get("excluded");
        Set<String> excluded = new LinkedHashSet<>();
        if (list != null && list.isJsonArray()) {
            for (JsonElement item : list.getAsJsonArray()) {
                if (item.isJsonPrimitive() && item.getAsJsonPrimitive().isString() && !item.getAsString().isEmpty()) {
                    excluded.add(item.getAsString());
                }
            }
        }
        return new BuildChoices(excluded, nether);
    }

    // missing = the default, a name we don't know = null (the entry is dropped rather than built the wrong way round)
    private static <T extends Enum<T>> T enumOr(Class<T> type, String name, T missing) {
        if (name == null) {
            return missing;
        }
        try {
            return Enum.valueOf(type, name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static String toJson(Map<String, SchematicPlacement> placements) {
        return toJson(placements, Map.of());
    }

    public static String toJson(Map<String, SchematicPlacement> placements, Map<String, BuildChoices> choices) {
        FileData data = new FileData();
        choices.forEach((path, c) -> {
            if (c.isEmpty()) {
                return;
            }
            if (data.choices == null) {
                data.choices = new LinkedHashMap<>();
            }
            Choice e = new Choice();
            e.excluded = new ArrayList<>(c.excluded());
            e.nether = c.nether() == null ? null : c.nether() ? "yes" : "no";
            data.choices.put(path, e);
        });
        placements.forEach((path, p) -> {
            Entry e = new Entry();
            e.x = p.origin().getX();
            e.y = p.origin().getY();
            e.z = p.origin().getZ();
            e.rotation = p.rotation().name();
            e.mirror = p.mirror().name();
            e.dimension = p.dimension();
            data.placements.put(path, e);
        });
        return GSON.toJson(data);
    }

    // placements only. whatever choices the file had are gone after this one, so the game side always saves both
    public static boolean save(Path file, Map<String, SchematicPlacement> placements) {
        return save(file, placements, Map.of());
    }

    // tmp file then move, a crash halfway leaves the old file instead of half a new one
    public static boolean save(Path file, Map<String, SchematicPlacement> placements, Map<String, BuildChoices> choices) {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(tmp, toJson(placements, choices), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException | RuntimeException e) {
            // the runtime half is gson choking on our own classes (a release jar with the field names mangled, say).
            // the placements still live in memory, a failed save must not take the build click down with it
            System.out.println("schematic placements: could not save " + file + " (" + e + ")");
            return false;
        }
    }

    private static void moveAside(Path file) {
        Path bad = file.resolveSibling(file.getFileName() + ".bad");
        try {
            Files.move(file, bad, StandardCopyOption.REPLACE_EXISTING);
            System.out.println("schematic placements: " + file + " is unusable, moved it to " + bad.getFileName());
        } catch (IOException e) {
            System.out.println("schematic placements: " + file + " is unusable and could not be moved aside (" + e.getMessage() + ")");
        }
    }
}
