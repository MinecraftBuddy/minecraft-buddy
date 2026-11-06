package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.util.helpers.ConfigHelper;
import baritone.cache.WorldData;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;

// where RunState lives and how it gets there. the load/save/delete core only takes a Path so a test can use a temp dir,
// the world lookup at the bottom is the only part that needs a game
public final class RunStateStore {
    // its own gson on purpose: plain fields, no item/blockpos adapters, enums as names, sets as arrays. RunState has no
    // minecraft types so the file stays readable by a human
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final String FALLBACK_NAME = "gamer-nocache.json";

    // what load found. resumed = there was a usable file for this world, fresh = we start over (and say why in the log)
    public record Loaded(RunState state, boolean resumed) {
    }

    private RunStateStore() {
    }

    // never returns null and never throws. a file that does not belong here is ignored or moved aside, not trusted
    public static Loaded load(Path file, String fingerprint) {
        if (!Files.isRegularFile(file)) {
            return fresh(fingerprint);
        }
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            Debug.logInternal("gamer: could not read " + file + " (" + e.getMessage() + "), starting fresh");
            return fresh(fingerprint);
        }
        RunState parsed = parse(text);
        if (parsed == null) {
            moveAside(file, "it is corrupt");
            return fresh(fingerprint);
        }
        if (parsed.schema > RunState.SCHEMA) {
            moveAside(file, "it is from a newer schema (" + parsed.schema + ")");
            return fresh(fingerprint);
        }
        if (!fingerprint.equals(parsed.fingerprint)) {
            if (isSharedFallback(file)) {
                // every world without a cache folder writes here, so the next autosave would eat that world's run
                moveAside(file, "it belongs to another world (\"" + parsed.fingerprint + "\" vs \"" + fingerprint + "\")");
                return fresh(fingerprint);
            }
            Debug.logInternal("gamer: " + file + " belongs to another world (\"" + parsed.fingerprint + "\" vs \"" + fingerprint + "\"), ignoring it");
            return fresh(fingerprint);
        }
        return new Loaded(parsed, true);
    }

    private static Loaded fresh(String fingerprint) {
        RunState s = new RunState();
        s.fingerprint = fingerprint;
        return new Loaded(s, false);
    }

    // null = not a usable RunState. gson hands back null for an empty file, and an unknown phase name becomes a null
    // phase, both are as good as corrupt
    static RunState parse(String text) {
        try {
            RunState s = GSON.fromJson(text, RunState.class);
            if (s == null || s.phase == null) {
                return null;
            }
            return sanitize(s);
        } catch (JsonParseException | IllegalStateException e) {
            return null;
        }
    }

    // a hand edited "visitedCells": null must not become an NPE three hours later
    private static RunState sanitize(RunState s) {
        if (s.fingerprint == null) {
            s.fingerprint = "";
        }
        if (s.stuckReason == null) {
            s.stuckReason = "";
        }
        if (s.portalMethod == null) {
            s.portalMethod = "CAST";
        }
        if (s.phaseAttempts == null) {
            s.phaseAttempts = new HashMap<>();
        }
        if (s.regressCounts == null) {
            s.regressCounts = new HashMap<>();
        }
        if (s.endDrops == null) {
            s.endDrops = new HashMap<>();
        }
        if (s.deaths == null) {
            s.deaths = new ArrayList<>();
        }
        if (s.deferredCells == null) {
            s.deferredCells = new HashMap<>();
        }
        if (s.visitedCells == null) {
            s.visitedCells = new LinkedHashSet<>();
        }
        if (s.bastionCells == null) {
            s.bastionCells = new LinkedHashSet<>();
        }
        if (s.fortressCells == null) {
            s.fortressCells = new LinkedHashSet<>();
        }
        if (s.roomChunksVisited == null) {
            s.roomChunksVisited = new LinkedHashSet<>();
        }
        if (s.fortress == null) {
            s.fortress = new ArrayList<>();
        }
        if (s.bastion == null) {
            s.bastion = new ArrayList<>();
        }
        if (s.strongholdRays == null) {
            s.strongholdRays = new ArrayList<>();
        }
        // a file from before the table list existed: nothing placed, so nothing to pick back up
        if (s.placedTables == null) {
            s.placedTables = new ArrayList<>();
        }
        if (s.placedFurnaces == null) {
            s.placedFurnaces = new ArrayList<>();
        }
        // same for smokers: absent key is a fresh list from the field initializer, an explicit null is a hand edit
        if (s.placedSmokers == null) {
            s.placedSmokers = new ArrayList<>();
        }
        // a save from before stations remembered their dimension: all overworld (absent key keeps the field's empty map)
        if (s.placedDimension == null) {
            s.placedDimension = new java.util.HashMap<>();
        }
        // files from before the village loot: nothing visited yet, nothing of ours to tell apart
        if (s.villageChestsTried == null) {
            s.villageChestsTried = new ArrayList<>();
        }
        if (s.placedJobBlocks == null) {
            s.placedJobBlocks = new ArrayList<>();
        }
        if (s.villageBedsTried == null) {
            s.villageBedsTried = new ArrayList<>();
        }
        // a file from before smelting ran in the background: nothing cooking
        if (s.furnaceJobs == null) {
            s.furnaceJobs = new ArrayList<>();
        }
        s.furnaceJobs.removeIf(job -> job == null || job.pos == null);
        // a trip with no pile to go to (a hand edit) is not a trip
        if (s.netherTrip != null && s.netherTrip.pile == null) {
            s.netherTrip = null;
        }
        return s;
    }

    private static void moveAside(Path file, String why) {
        Path bad = file.resolveSibling(file.getFileName() + ".bad");
        try {
            Files.move(file, bad, StandardCopyOption.REPLACE_EXISTING);
            Debug.logInternal("gamer: " + file + " is unusable (" + why + "), moved it to " + bad.getFileName() + " and starting fresh");
        } catch (IOException e) {
            Debug.logInternal("gamer: " + file + " is unusable (" + why + ") and could not be moved aside (" + e.getMessage() + ")");
        }
    }

    public static String toJson(RunState state) {
        return GSON.toJson(state);
    }

    // tmp file then move, a crash in the middle leaves the old file instead of half of a new one
    public static boolean save(Path file, RunState state) {
        return write(file, toJson(state));
    }

    public static boolean write(Path file, String json) {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(tmp, json, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException e) {
            Debug.logInternal("gamer: could not save " + file + " (" + e.getMessage() + ")");
            return false;
        }
    }

    // #gamer reset
    public static boolean delete(Path file) {
        try {
            return Files.deleteIfExists(file);
        } catch (IOException e) {
            Debug.logInternal("gamer: could not delete " + file + " (" + e.getMessage() + ")");
            return false;
        }
    }

    // ---- the part that needs a game

    // <worldDir>/altoclef/gamer.json. worldDir is two levels up from baritone's per dimension folder: singleplayer that is
    // <save>/baritone, multiplayer <gameDir>/baritone/<ip>. no world data (replay, or soprano has not loaded it yet) falls
    // back to a file next to the configs, which every world shares, the fingerprint keeps them apart
    public static Path resolvePath(AltoClef mod) {
        try {
            WorldData world = mod.getClientBaritone().getWorldProvider().getCurrentWorld();
            if (world != null) {
                Path dir = world.directory.getParent().getParent();
                if (dir != null) {
                    return dir.resolve("altoclef").resolve("gamer.json");
                }
            }
        } catch (RuntimeException e) {
            Debug.logInternal("gamer: world folder lookup failed (" + e + ")");
        }
        Debug.logInternal("gamer: no world cache folder here (replay?), keeping the run state in the shared config folder");
        return ConfigHelper.getConfigFolder().resolve(FALLBACK_NAME);
    }

    // true for the shared file resolvePath falls back to when there is no world folder
    public static boolean isSharedFallback(Path file) {
        return file.getFileName().toString().equals(FALLBACK_NAME);
    }

    // "<level name>|<server ip>". a singleplayer world has a name and no ip, a server the other way round, anything we
    // cannot read is an empty string so the fingerprint is still stable between runs
    public static String fingerprint() {
        String level = "";
        String ip = "";
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null) {
                if (mc.getSingleplayerServer() != null) {
                    level = String.valueOf(mc.getSingleplayerServer().getWorldData().getLevelName());
                }
                ServerData server = mc.getCurrentServer();
                if (server != null && server.ip != null) {
                    ip = server.ip;
                }
            }
        } catch (RuntimeException e) {
            Debug.logInternal("gamer: fingerprint lookup failed (" + e + ")");
        }
        return level + "|" + ip;
    }
}
