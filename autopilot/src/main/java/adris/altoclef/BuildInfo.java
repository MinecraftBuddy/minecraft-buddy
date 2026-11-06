package adris.altoclef;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

// which jar wrote this log. gradle expands build-id.properties at build time, this reads it back once. a missing or
// broken file is "unknown" and never an exception, a log helper must not be the thing that breaks startup
public final class BuildInfo {

    private static final String RESOURCE = "/adris/altoclef/build-id.properties";
    public static final String UNKNOWN = "unknown";

    private static String id;
    private static String built;
    private static String version;
    private static String minecraftVersion;

    private BuildInfo() {
    }

    public static synchronized String id() {
        if (id == null) {
            id = read(RESOURCE, "buildId");
        }
        return id;
    }

    public static synchronized String built() {
        if (built == null) {
            built = read(RESOURCE, "buildTime");
        }
        return built;
    }

    // "1.1.6", "1.1.6-anyversion" or "1.1.7-nightly.1", whatever -Prelease_version said. the updater compares against this
    public static synchronized String version() {
        if (version == null) {
            version = read(RESOURCE, "version");
        }
        return version;
    }

    // the mc line this jar was built for, not whatever version a viafabricplus server pretends to be
    public static synchronized String minecraftVersion() {
        if (minecraftVersion == null) {
            minecraftVersion = read(RESOURCE, "minecraftVersion");
        }
        return minecraftVersion;
    }

    // "build 1a2b3c4 built 2026-10-10 01:02:03"
    public static String line() {
        return "build " + id() + " built " + built();
    }

    static String read(String resource, String key) {
        try (InputStream in = BuildInfo.class.getResourceAsStream(resource)) {
            if (in == null) {
                return UNKNOWN;
            }
            Properties props = new Properties();
            props.load(in);
            return clean(props.getProperty(key));
        } catch (IOException | RuntimeException e) {
            return UNKNOWN;
        }
    }

    // a resource that never went through gradle still has the ${...} placeholder in it
    static String clean(String value) {
        if (value == null) {
            return UNKNOWN;
        }
        String v = value.trim();
        return v.isEmpty() || v.contains("${") ? UNKNOWN : v;
    }
}
