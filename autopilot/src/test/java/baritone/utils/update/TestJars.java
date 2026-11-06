package baritone.utils.update;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

// tiny fake soprano jars for the tests. a real one is 4 MB of proguard, these are a properties file and a vibe
final class TestJars {

    private TestJars() {
    }

    static Path jar(Path dir, String name, Map<String, String> entries) throws IOException {
        Path p = dir.resolve(name);
        try (OutputStream o = Files.newOutputStream(p); ZipOutputStream z = new ZipOutputStream(o)) {
            for (Map.Entry<String, String> e : entries.entrySet()) {
                z.putNextEntry(new ZipEntry(e.getKey()));
                z.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                z.closeEntry();
            }
        }
        return p;
    }

    static String props(String version, String mc) {
        StringBuilder sb = new StringBuilder("buildId=abc123\n");
        if (version != null) {
            sb.append("version=").append(version).append('\n');
        }
        if (mc != null) {
            sb.append("minecraftVersion=").append(mc).append('\n');
        }
        return sb.toString();
    }

    // a fabric jar with build info and the swap helper marker, like a real release
    static Map<String, String> soprano(String loader, String version, String mc) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(Flavor.metadataFile(loader), "{}");
        m.put(JarCheck.BUILD_ID, props(version, mc));
        m.put(SelfJar.MARKER, "cafebabe");
        return m;
    }
}
