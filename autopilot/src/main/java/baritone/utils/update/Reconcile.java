/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.utils.update;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

// runs on launch, before any network. if we're still the old version and a staged .new is sitting next to us, the
// helper never got to finish (game killed, antivirus, a launcher that took the helper down with it). the .tries file
// counts how many launches that's happened on, so after the second miss the card stops promising and shows the
// manual steps instead. it's updater state, it never leaves the mods folder
public final class Reconcile {

    public static final String NEW = ".new";
    public static final String TRIES = ".tries";
    public static final String PART = ".part";
    public static final String OLD = ".old";

    static final int STUCK_AFTER = 2;

    // staged is null when there's nothing pending. finalJar is staged minus the .new, version is what it'll be
    public record Result(Path staged, Path finalJar, String version, boolean stuck) {

        static final Result NOTHING = new Result(null, null, null, false);
    }

    private Reconcile() {
    }

    public static Result run(Path selfJar, ModVersion running, String minecraftVersion) {
        Path dir = selfJar.getParent();
        if (dir == null || !Files.isDirectory(dir)) {
            return Result.NOTHING;
        }
        String self = selfJar.getFileName().toString();
        List<Path> files = list(dir);
        // the newest staged jar wins, the order a directory lists in means nothing
        Path best = null;
        ModVersion bestVersion = null;
        for (Path p : files) {
            if (isStaged(p.getFileName().toString())) {
                ModVersion v = worthFinishing(p, running, minecraftVersion);
                if (v != null && (bestVersion == null || v.newerThan(bestVersion))) {
                    best = p;
                    bestVersion = v;
                }
            }
        }
        for (Path p : files) {
            String name = p.getFileName().toString();
            if (p.equals(selfJar) || p.equals(best)) {
                continue;
            }
            if (isLeftover(name, self)) {
                deleteQuietly(p);
            } else if (isStaged(name)) {
                // older, the same, another mc line, unreadable, or just not the newest: it's not happening
                deleteQuietly(p);
                deleteQuietly(triesFor(p));
            }
        }
        // after the .new cleanup, so the tries of a deleted one count as orphans too
        for (Path p : files) {
            if (isOrphanTries(p)) {
                deleteQuietly(p);
            }
        }
        if (best == null) {
            return Result.NOTHING;
        }
        return new Result(best, finalFor(best), bestVersion.raw(), bumpTries(triesFor(best)));
    }

    // ours only. some other mod's .old is none of our business
    static boolean isLeftover(String name, String self) {
        if (name.equals(self + OLD) || name.equals(self + PART)) {
            return true;
        }
        return name.startsWith("soprano-") && (name.endsWith(".jar" + PART) || name.endsWith(".jar" + OLD));
    }

    // the helper deletes .tries after the .new, so a swap that died in between leaves one behind
    static boolean isOrphanTries(Path p) {
        String name = p.getFileName().toString();
        if (!name.startsWith("soprano-") || !name.endsWith(".jar" + NEW + TRIES)) {
            return false;
        }
        return !Files.exists(p.resolveSibling(name.substring(0, name.length() - TRIES.length())));
    }

    static boolean isStaged(String name) {
        return name.startsWith("soprano-") && name.endsWith(".jar" + NEW);
    }

    // the staged jar's version when it's a real upgrade for us, or a switch from nightly back to stable, null otherwise
    static ModVersion worthFinishing(Path staged, ModVersion running, String minecraftVersion) {
        Properties props = JarCheck.buildId(staged);
        if (props == null) {
            return null;
        }
        ModVersion v = ModVersion.parse(props.getProperty("version"));
        String mc = props.getProperty("minecraftVersion");
        boolean ok = v != null && v.sameChannel(running) && mc != null && mc.trim().equals(minecraftVersion)
                && (v.newerThan(running) || backToStable(v, running));
        return ok ? v : null;
    }

    // the switch to stable is allowed to go down (1.1.7-nightly.2 -> 1.1.6), so an unfinished one of those has to
    // survive the "older is junk" rule or it gets binned on the very launch that was meant to finish it
    static boolean backToStable(ModVersion staged, ModVersion running) {
        return !staged.isNightly() && running.isNightly() && !staged.equals(running);
    }

    public static Path triesFor(Path staged) {
        return staged.resolveSibling(staged.getFileName() + TRIES);
    }

    public static Path finalFor(Path staged) {
        String n = staged.getFileName().toString();
        return staged.resolveSibling(n.substring(0, n.length() - NEW.length()));
    }

    // a fresh download writes 1, each launch that finds it unfinished adds one. a missing or mangled file counts as
    // the first miss, a reset to zero would let a broken swap promise "next launch" forever
    static boolean bumpTries(Path tries) {
        int n = readTries(tries);
        writeTries(tries, n + 1);
        return n >= STUCK_AFTER;
    }

    static int readTries(Path tries) {
        try {
            return Math.max(1, Integer.parseInt(Files.readString(tries, StandardCharsets.UTF_8).trim()));
        } catch (IOException | NumberFormatException e) {
            return 1;
        }
    }

    public static void writeTries(Path tries, int n) {
        try {
            Files.writeString(tries, Integer.toString(n), StandardCharsets.UTF_8);
        } catch (IOException e) {
            // a read only mods folder can't install anything either, the swap will say so on its own
        }
    }

    private static List<Path> list(Path dir) {
        List<Path> out = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*")) {
            for (Path p : ds) {
                out.add(p);
            }
        } catch (IOException e) {
            // nothing listed is nothing reconciled, the next launch gets another go
        }
        return out;
    }

    static void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException e) {
            // still locked by something, next launch
        }
    }
}
