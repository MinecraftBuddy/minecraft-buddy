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
import java.net.URI;
import java.net.URL;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.zip.ZipFile;

// finds the jar we were loaded from, so we know what to replace. fabric hands us a plain file: url, forge and neoforge
// wrap it in their own union/jar filesystems that don't map back to a path, so those fall through to a scan of mods/
public final class SelfJar {

    static final String MARKER = "baritone/utils/update/SwapHelper.class";
    // only the unoptimized jar keeps real names for the pathing internals, the api jar has them proguarded away
    static final String UNOPTIMIZED_MARKER = "baritone/pathing/calc/AStarPathFinder.class";

    // jar is null when install is off, reason says why in words a player can read
    public record Located(Path jar, Flavor flavor, String reason) {

        static Located no(String reason) {
            return new Located(null, null, reason);
        }
    }

    private SelfJar() {
    }

    public static Located locate(URL codeSource, Path gameDir, String version) {
        Path direct = fromCodeSource(codeSource);
        if (direct != null && isOurJar(direct, version)) {
            return withFlavor(direct);
        }
        if (direct != null && Files.isDirectory(direct)) {
            return Located.no("this is a dev run, there's no jar to update");
        }
        Path found = gameDir == null ? null : scanMods(gameDir.resolve("mods"), version);
        if (found != null) {
            return withFlavor(found);
        }
        return Located.no("couldn't find the soprano jar in the mods folder");
    }

    // file:/x/y.jar and jar:file:/x/y.jar!/ give a path, everything else (union:, a classes dir url in a weird
    // wrapper, garbage) gives null and the mods/ scan takes over
    static Path fromCodeSource(URL url) {
        if (url == null) {
            return null;
        }
        String s = url.toString();
        if (s.startsWith("jar:")) {
            int bang = s.indexOf("!/");
            s = bang < 0 ? s.substring(4) : s.substring(4, bang);
        }
        if (!s.startsWith("file:")) {
            return null;
        }
        try {
            return Path.of(new URI(s));
        } catch (Exception e) {
            return null;
        }
    }

    static boolean isOurJar(Path p, String version) {
        if (!Files.isRegularFile(p) || !p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")) {
            return false;
        }
        Properties props = JarCheck.buildId(p);
        return props != null && version.equals(trim(props.getProperty("version")));
    }

    // the jar in mods/ with our exact version and the swap helper inside. the helper check keeps an old soprano
    // that happens to share a version string with a dev build (both 1.1.6, say) from being mistaken for us
    static Path scanMods(Path mods, String version) {
        if (!Files.isDirectory(mods)) {
            return null;
        }
        List<Path> hits = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(mods, "*.jar")) {
            for (Path p : ds) {
                if (Files.isRegularFile(p) && hasMarkerAndVersion(p, version)) {
                    hits.add(p);
                }
            }
        } catch (IOException e) {
            return null;
        }
        // two copies of ourselves means the loader is about to complain anyway. don't guess which one to replace
        return hits.size() == 1 ? hits.get(0) : null;
    }

    static boolean hasMarkerAndVersion(Path p, String version) {
        try (ZipFile zip = new ZipFile(p.toFile())) {
            if (zip.getEntry(MARKER) == null) {
                return false;
            }
            Properties props = JarCheck.buildId(zip);
            return props != null && version.equals(trim(props.getProperty("version")));
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    static Located withFlavor(Path jar) {
        Flavor f = flavorOf(jar);
        if (f == null) {
            return new Located(null, null, "couldn't tell which loader " + jar.getFileName() + " is for");
        }
        return new Located(jar, f, null);
    }

    // the name says it unless someone renamed the file, then the metadata says the loader and the class list says
    // the variant. the metadata wins on loader even with a good name, it can't be renamed
    static Flavor flavorOf(Path jar) {
        Flavor named = Flavor.fromFileName(jar.getFileName().toString());
        String loader;
        boolean unoptimized;
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            loader = loaderOf(zip);
            unoptimized = zip.getEntry(UNOPTIMIZED_MARKER) != null;
        } catch (IOException | RuntimeException e) {
            return named;
        }
        if (loader == null) {
            return named;
        }
        if (named != null) {
            return new Flavor(named.variant(), loader);
        }
        return new Flavor(unoptimized ? "unoptimized" : "api", loader);
    }

    static String loaderOf(ZipFile zip) {
        // neoforge first, an older neoforge jar may carry a mods.toml next to its own file
        if (zip.getEntry(Flavor.metadataFile(Flavor.NEOFORGE)) != null) {
            return Flavor.NEOFORGE;
        }
        if (zip.getEntry(Flavor.metadataFile(Flavor.FORGE)) != null) {
            return Flavor.FORGE;
        }
        if (zip.getEntry(Flavor.metadataFile(Flavor.FABRIC)) != null) {
            return Flavor.FABRIC;
        }
        return null;
    }

    private static String trim(String s) {
        return s == null ? null : s.trim();
    }
}
