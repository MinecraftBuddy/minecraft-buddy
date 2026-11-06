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

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

// swaps the jar after the game is gone, because windows won't let anyone rename a jar the game has open.
// this runs as its own tiny jvm from ONE extracted .class file, so: java.* only, no lambdas, no inner or anonymous
// classes, no enums, no other soprano class. anything else would be a NoClassDefFoundError in a process nobody sees.
// proguard.pro has an explicit -keep on it so the name and every member survive the obfuscated builds
public final class SwapHelper {

    static final int DONE = 0;
    static final int GONE = 1;
    static final int RETRY = 2;
    static final int GAVE_UP = 3;

    private static final long TIMEOUT_MS = 60_000;
    private static final long INTERVAL_MS = 500;

    private SwapHelper() {
    }

    // <gamePid> <oldJar> <staged .new> <finalJar>
    public static void main(String[] args) {
        if (args.length != 4) {
            return;
        }
        try {
            waitForExit(Long.parseLong(args[0]));
            swap(Paths.get(args[1]), Paths.get(args[2]), Paths.get(args[3]), TIMEOUT_MS, INTERVAL_MS);
        } catch (RuntimeException e) {
            // no stdout, no log, nobody listening. the game's reconcile sees the .new next launch and tries again
        } finally {
            cleanUpOwnDir();
        }
    }

    static void waitForExit(long pid) {
        Optional<ProcessHandle> h = ProcessHandle.of(pid);
        if (!h.isPresent() || !h.get().isAlive()) {
            return;
        }
        try {
            h.get().onExit().get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (java.util.concurrent.ExecutionException e) {
            // can't wait on it, the retries below cover a game that's still closing anyway
        }
    }

    // keeps trying for timeoutMs. the old jar stays locked for a moment after the game exits, and antivirus or
    // onedrive like to grab freshly written jars too, so one failed move means nothing
    static int swap(Path old, Path staged, Path fin, long timeoutMs, long intervalMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (true) {
            int r = attempt(old, staged, fin);
            if (r != RETRY) {
                return r;
            }
            if (System.currentTimeMillis() >= deadline) {
                return GAVE_UP;
            }
            try {
                Thread.sleep(intervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return GAVE_UP;
            }
        }
    }

    static int attempt(Path old, Path staged, Path fin) {
        // gone means someone finished it already, maybe a second helper from a quick relaunch. that's a win too
        if (!Files.exists(staged)) {
            return GONE;
        }
        Path bak = old.resolveSibling(old.getFileName() + ".old");
        boolean moved = false;
        try {
            if (Files.exists(old)) {
                Files.move(old, bak, StandardCopyOption.REPLACE_EXISTING);
                moved = true;
            }
        } catch (NoSuchFileException e) {
            // vanished between exists and move, fine, nothing to back up
        } catch (IOException e) {
            return RETRY;
        }
        try {
            Files.move(staged, fin, StandardCopyOption.REPLACE_EXISTING);
        } catch (NoSuchFileException e) {
            putBack(moved, bak, old);
            return GONE;
        } catch (IOException e) {
            // never leave them with no jar at all, the old one goes back where it was
            putBack(moved, bak, old);
            return RETRY;
        }
        deleteQuietly(bak);
        deleteQuietly(staged.resolveSibling(staged.getFileName() + ".tries"));
        return DONE;
    }

    private static void putBack(boolean moved, Path bak, Path old) {
        if (!moved) {
            return;
        }
        try {
            Files.move(bak, old, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            // if even this fails the next attempt sees no old jar and just moves the new one in, which is the goal
        }
    }

    private static void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException e) {
            // a stray .old is cleaned up by the next launch
        }
    }

    // copies this class into a fresh temp dir and starts it there. running it with -cp <the old jar> would make the
    // helper hold the very jar it is trying to replace, which is a very funny way to never finish
    public static Process spawn(long gamePid, Path old, Path staged, Path fin) throws IOException {
        String resource = SwapHelper.class.getName().replace('.', '/') + ".class";
        Path dir = Files.createTempDirectory("soprano-swap");
        try {
            return start(dir, resource, gamePid, old, staged, fin);
        } catch (IOException | RuntimeException e) {
            // reconcile tries again every launch, one orphaned temp dir per failed launch would add up
            deleteTree(dir.toFile());
            throw e;
        }
    }

    private static Process start(Path dir, String resource, long gamePid, Path old, Path staged, Path fin) throws IOException {
        Path dest = dir.resolve(resource);
        Files.createDirectories(dest.getParent());
        try (InputStream in = SwapHelper.class.getResourceAsStream("/" + resource)) {
            if (in == null) {
                throw new IOException("can't read " + resource);
            }
            Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
        }
        List<String> cmd = new ArrayList<>();
        cmd.add(javaBinary());
        cmd.add("-Xmx32m");
        cmd.add("-cp");
        cmd.add(dir.toString());
        cmd.add(SwapHelper.class.getName());
        cmd.add(Long.toString(gamePid));
        cmd.add(old.toAbsolutePath().toString());
        cmd.add(staged.toAbsolutePath().toString());
        cmd.add(fin.toAbsolutePath().toString());
        ProcessBuilder pb = new ProcessBuilder(cmd);
        // never INHERIT. launchers wait for the game's stdout to close, and a helper holding it would make the game
        // look like it's still running. DISCARD can't be used for input, so stdin reads from the null device
        pb.redirectInput(ProcessBuilder.Redirect.from(new File(isWindows() ? "NUL" : "/dev/null")));
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        // cwd goes next to the temp dir, not in it. windows won't delete a folder some process is standing in, so
        // cleanUpOwnDir would leave an empty soprano-swap folder behind every update
        pb.directory(dir.getParent().toFile());
        return pb.start();
    }

    static String javaBinary() {
        boolean win = isWindows();
        Optional<String> cmd = ProcessHandle.current().info().command();
        if (cmd.isPresent()) {
            Path p = Paths.get(cmd.get());
            String name = p.getFileName() == null ? "" : p.getFileName().toString().toLowerCase(Locale.ROOT);
            if (name.startsWith("java")) {
                if (win) {
                    // javaw, or windows pops a console window for the helper's whole life
                    Path w = p.resolveSibling("javaw.exe");
                    if (Files.isRegularFile(w)) {
                        return w.toString();
                    }
                }
                return p.toString();
            }
        }
        Path bin = Paths.get(System.getProperty("java.home"), "bin");
        Path w = bin.resolve(win ? "javaw.exe" : "java");
        if (Files.isRegularFile(w)) {
            return w.toString();
        }
        return bin.resolve(win ? "java.exe" : "java").toString();
    }

    static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    }

    // tidy the temp dir we were copied into. only ever a soprano-swap* dir, never whatever cwd happens to be
    private static void cleanUpOwnDir() {
        try {
            Path root = Paths.get(SwapHelper.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (root.getFileName() == null || !root.getFileName().toString().startsWith("soprano-swap") || !Files.isDirectory(root)) {
                return;
            }
            deleteTree(root.toFile());
        } catch (Exception e) {
            // the os cleans temp eventually, a 10kb class file is not worth a crash
        }
    }

    private static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File k : kids) {
                deleteTree(k);
            }
        }
        f.delete();
    }
}
