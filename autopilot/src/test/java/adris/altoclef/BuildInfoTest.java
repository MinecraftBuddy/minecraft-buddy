package adris.altoclef;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class BuildInfoTest {

    @Test
    public void aMissingResourceIsUnknownAndNeverThrows() {
        assertEquals(BuildInfo.UNKNOWN, BuildInfo.read("/adris/altoclef/does-not-exist.properties", "buildId"));
    }

    @Test
    public void aMissingKeyIsUnknown() {
        assertEquals(BuildInfo.UNKNOWN, BuildInfo.read("/adris/altoclef/build-id.properties", "nope"));
    }

    @Test
    public void anUnexpandedPlaceholderIsUnknown() {
        assertEquals(BuildInfo.UNKNOWN, BuildInfo.clean("${buildId}"));
        assertEquals(BuildInfo.UNKNOWN, BuildInfo.clean("  "));
        assertEquals(BuildInfo.UNKNOWN, BuildInfo.clean(null));
        assertEquals("abc123-dirty", BuildInfo.clean(" abc123-dirty "));
    }

    @Test
    public void gradleActuallyExpandedTheFile() throws Exception {
        // the cleaned values would read "unknown" for an unexpanded file too, so look at the raw text
        try (java.io.InputStream in = BuildInfo.class.getResourceAsStream("/adris/altoclef/build-id.properties")) {
            assertNotNull(in);
            String raw = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(raw, raw.contains("buildId="));
            assertTrue(raw, raw.contains("version="));
            assertTrue(raw, raw.contains("minecraftVersion="));
            assertFalse(raw, raw.contains("${"));
        }
    }

    @Test
    public void theGradleExpandedFileIsReadBack() {
        // processResources runs before the tests, so this is the real build's id and never the raw placeholder
        assertNotNull(BuildInfo.id());
        assertFalse(BuildInfo.id().contains("${"));
        assertFalse(BuildInfo.built().contains("${"));
        assertFalse(BuildInfo.id().isEmpty());
        assertTrue(BuildInfo.line().startsWith("build " + BuildInfo.id() + " built "));
    }

    @Test
    public void theVersionAndMinecraftLineAreFilledIn() {
        // the updater refuses to do anything with an unknown version, so a silently broken expand would turn it off
        assertNotEquals(BuildInfo.UNKNOWN, BuildInfo.version());
        assertNotEquals(BuildInfo.UNKNOWN, BuildInfo.minecraftVersion());
        assertFalse(BuildInfo.version().contains("${"));
        assertFalse(BuildInfo.minecraftVersion().contains("${"));
    }
}
