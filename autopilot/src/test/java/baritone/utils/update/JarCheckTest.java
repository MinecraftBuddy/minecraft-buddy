package baritone.utils.update;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

public class JarCheckTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Path jar(Map<String, String> entries) throws Exception {
        return TestJars.jar(tmp.getRoot().toPath(), "soprano-api-fabric-1.1.7.jar.part", entries);
    }

    @Test
    public void theRightJarPasses() throws Exception {
        assertNull(JarCheck.verify(jar(TestJars.soprano("fabric", "1.1.7", "26.3")), "fabric", "1.1.7", "26.3", ""));
        assertNull(JarCheck.verify(jar(TestJars.soprano("fabric", "1.1.7-anyversion", "26.3")), "fabric", "1.1.7-anyversion", "26.3", "anyversion"));
    }

    @Test
    public void wrongMinecraft() throws Exception {
        assertNotNull(JarCheck.verify(jar(TestJars.soprano("fabric", "1.1.7", "1.21.11")), "fabric", "1.1.7", "26.3", ""));
    }

    @Test
    public void wrongVersion() throws Exception {
        assertNotNull(JarCheck.verify(jar(TestJars.soprano("fabric", "1.1.6", "26.3")), "fabric", "1.1.7", "26.3", ""));
    }

    @Test
    public void wrongChannel() throws Exception {
        // version matches the release but we're on base, an anyversion jar does not belong here
        assertNotNull(JarCheck.verify(jar(TestJars.soprano("fabric", "1.1.7-anyversion", "26.3")), "fabric", "1.1.7-anyversion", "26.3", ""));
    }

    @Test
    public void missingFieldsAreARefusal() throws Exception {
        assertNotNull(JarCheck.verify(jar(TestJars.soprano("fabric", null, "26.3")), "fabric", "1.1.7", "26.3", ""));
        assertNotNull(JarCheck.verify(jar(TestJars.soprano("fabric", "1.1.7", null)), "fabric", "1.1.7", "26.3", ""));
        Map<String, String> noProps = TestJars.soprano("fabric", "1.1.7", "26.3");
        noProps.remove(JarCheck.BUILD_ID);
        assertNotNull(JarCheck.verify(jar(noProps), "fabric", "1.1.7", "26.3", ""));
    }

    @Test
    public void missingOrWrongMetadata() throws Exception {
        Map<String, String> none = TestJars.soprano("fabric", "1.1.7", "26.3");
        none.remove("fabric.mod.json");
        assertNotNull(JarCheck.verify(jar(none), "fabric", "1.1.7", "26.3", ""));
        // a forge jar under a fabric name
        assertNotNull(JarCheck.verify(jar(TestJars.soprano("forge", "1.1.7", "26.3")), "fabric", "1.1.7", "26.3", ""));
        assertNull(JarCheck.verify(jar(TestJars.soprano("neoforge", "1.1.7", "26.3")), "neoforge", "1.1.7", "26.3", ""));
    }

    @Test
    public void notAZipAtAll() throws Exception {
        Path p = tmp.newFile("garbage.jar.part").toPath();
        Files.writeString(p, "<html>captive portal says hi</html>");
        assertNotNull(JarCheck.verify(p, "fabric", "1.1.7", "26.3", ""));
    }

    @Test
    public void sha256OfAFile() throws Exception {
        Path p = tmp.newFile("abc").toPath();
        Files.write(p, "abc".getBytes(StandardCharsets.UTF_8));
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", JarCheck.sha256(p));
    }

    @Test
    public void nightlyJarsVerify() throws Exception {
        assertNull(JarCheck.verify(jar(TestJars.soprano("fabric", "1.1.7-nightly.1", "26.3")), "fabric", "1.1.7-nightly.1", "26.3", ""));
        assertNull(JarCheck.verify(jar(TestJars.soprano("fabric", "1.1.7-nightly.1-anyversion", "26.3")), "fabric",
                "1.1.7-nightly.1-anyversion", "26.3", "anyversion"));
        // the stable jar under a nightly release (or the other way round) is the wrong jar
        assertNotNull(JarCheck.verify(jar(TestJars.soprano("fabric", "1.1.7", "26.3")), "fabric", "1.1.7-nightly.1", "26.3", ""));
        assertNotNull(JarCheck.verify(jar(TestJars.soprano("fabric", "1.1.7-nightly.1-anyversion", "26.3")), "fabric",
                "1.1.7-nightly.1-anyversion", "26.3", ""));
    }
}
