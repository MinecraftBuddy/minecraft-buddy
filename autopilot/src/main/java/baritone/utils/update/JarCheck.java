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
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

// a jar only goes anywhere near the mods folder after it proves it's the release we asked for. the checksum says the
// bytes are what github has, this says github has the right thing: a mixed up asset (wrong mc line, wrong loader)
// would otherwise get swapped in and crash on the next launch, with us no longer around to say sorry
public final class JarCheck {

    public static final String BUILD_ID = "adris/altoclef/build-id.properties";

    private JarCheck() {
    }

    // null when it's good, a short reason when it isn't
    public static String verify(Path jar, String loader, String version, String minecraftVersion, String channel) {
        String meta = Flavor.metadataFile(loader);
        if (meta == null) {
            return "unknown loader " + loader;
        }
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            if (zip.getEntry(meta) == null) {
                return "the download isn't a " + loader + " jar";
            }
            Properties props = buildId(zip);
            if (props == null) {
                return "the download has no build info";
            }
            return compare(props, version, minecraftVersion, channel);
        } catch (IOException e) {
            return "the download isn't a valid jar";
        }
    }

    static String compare(Properties props, String version, String minecraftVersion, String channel) {
        String v = props.getProperty("version");
        String mc = props.getProperty("minecraftVersion");
        // missing is a refusal, not a pass. an old jar that never had these fields can't vouch for itself
        if (v == null || mc == null) {
            return "the download has no version info";
        }
        if (!v.trim().equals(version)) {
            return "the download says it's " + v.trim() + ", not " + version;
        }
        if (!mc.trim().equals(minecraftVersion)) {
            return "the download is for minecraft " + mc.trim();
        }
        ModVersion parsed = ModVersion.parse(v);
        if (parsed == null || !parsed.channel().equals(channel)) {
            return "the download is from a different release channel";
        }
        return null;
    }

    // null when the jar has no build-id.properties at all
    public static Properties buildId(ZipFile zip) throws IOException {
        ZipEntry e = zip.getEntry(BUILD_ID);
        if (e == null) {
            return null;
        }
        try (InputStream in = zip.getInputStream(e)) {
            Properties p = new Properties();
            p.load(in);
            return p;
        }
    }

    // same thing for a jar on disk, null on anything that goes wrong
    public static Properties buildId(Path jar) {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            return buildId(zip);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    public static String sha256(Path file) throws IOException {
        MessageDigest md = newSha256();
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
        }
        return HexFormat.of().formatHex(md.digest());
    }

    public static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // every jvm has to ship sha-256, this is the spec's problem not ours
            throw new IllegalStateException(e);
        }
    }
}
