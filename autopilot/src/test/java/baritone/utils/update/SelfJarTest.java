package baritone.utils.update;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

public class SelfJarTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void jarNames() {
        assertEquals(new Flavor("api", "fabric"), Flavor.fromFileName("soprano-api-fabric-1.1.6.jar"));
        assertEquals(new Flavor("api", "neoforge"), Flavor.fromFileName("soprano-api-neoforge-1.1.6.jar"));
        assertEquals(new Flavor("api", "forge"), Flavor.fromFileName("soprano-api-forge-1.1.6.jar"));
        assertEquals(new Flavor("unoptimized", "fabric"), Flavor.fromFileName("soprano-unoptimized-fabric-1.1.6-anyversion.jar"));
        assertNull(Flavor.fromFileName("soprano.jar"));
        assertNull(Flavor.fromFileName("baritone-api-fabric-1.1.6.jar"));
        assertNull(Flavor.fromFileName("soprano-api-quilt-1.1.6.jar"));
        assertEquals("soprano-api-fabric-1.1.7-anyversion.jar", new Flavor("api", "fabric").assetName("1.1.7-anyversion"));
    }

    @Test
    public void codeSourceUrls() throws Exception {
        Path jar = tmp.newFile("soprano-api-fabric-1.1.6.jar").toPath();
        URL file = jar.toUri().toURL();
        assertEquals(jar, SelfJar.fromCodeSource(file));
        assertEquals(jar, SelfJar.fromCodeSource(new URL("jar:" + file + "!/")));
        assertEquals(jar, SelfJar.fromCodeSource(new URL("jar:" + file + "!/baritone/utils/update/")));
        // forge and neoforge hand out their own schemes, those give up and let the mods/ scan look
        assertNull(SelfJar.fromCodeSource(null));
        assertNull(SelfJar.fromCodeSource(new URL("http://example.com/soprano.jar")));
    }

    @Test
    public void codeSourceWithSpacesInThePath() throws Exception {
        Path dir = tmp.newFolder("my mods").toPath();
        Path jar = Files.createFile(dir.resolve("soprano api.jar"));
        assertEquals(jar, SelfJar.fromCodeSource(jar.toUri().toURL()));
    }

    @Test
    public void theCodeSourceJarIsUsedWhenItIsUs() throws Exception {
        Path jar = TestJars.jar(tmp.getRoot().toPath(), "soprano-api-fabric-1.1.6.jar", TestJars.soprano("fabric", "1.1.6", "26.3"));
        SelfJar.Located l = SelfJar.locate(jar.toUri().toURL(), null, "1.1.6");
        assertEquals(jar, l.jar());
        assertEquals(new Flavor("api", "fabric"), l.flavor());
        assertNull(l.reason());
    }

    @Test
    public void aDevRunCantInstall() throws Exception {
        Path classes = tmp.newFolder("classes").toPath();
        SelfJar.Located l = SelfJar.locate(classes.toUri().toURL(), tmp.getRoot().toPath(), "1.1.6");
        assertNull(l.jar());
        assertNotNull(l.reason());
    }

    @Test
    public void theModsScanFindsUsAndOnlyUs() throws Exception {
        Path game = tmp.getRoot().toPath();
        Path mods = Files.createDirectories(game.resolve("mods"));
        TestJars.jar(mods, "sodium.jar", Map.of("fabric.mod.json", "{}"));
        // same version string, but no swap helper inside: an older soprano or something else, not us
        Map<String, String> noMarker = TestJars.soprano("fabric", "1.1.6", "26.3");
        noMarker.remove(SelfJar.MARKER);
        TestJars.jar(mods, "soprano-api-fabric-old.jar", noMarker);
        TestJars.jar(mods, "soprano-api-fabric-1.1.5.jar", TestJars.soprano("fabric", "1.1.5", "26.3"));
        Path us = TestJars.jar(mods, "soprano-api-neoforge-1.1.6.jar", TestJars.soprano("neoforge", "1.1.6", "26.3"));
        SelfJar.Located l = SelfJar.locate(URI.create("jrt:/java.base").toURL(), game, "1.1.6");
        assertEquals(us, l.jar());
        assertEquals(new Flavor("api", "neoforge"), l.flavor());
    }

    @Test
    public void twoCopiesOfUsIsNotAGuess() throws Exception {
        Path game = tmp.getRoot().toPath();
        Path mods = Files.createDirectories(game.resolve("mods"));
        TestJars.jar(mods, "a.jar", TestJars.soprano("fabric", "1.1.6", "26.3"));
        TestJars.jar(mods, "b.jar", TestJars.soprano("fabric", "1.1.6", "26.3"));
        assertNull(SelfJar.locate(null, game, "1.1.6").jar());
    }

    @Test
    public void aRenamedJarGetsItsFlavorFromInside() throws Exception {
        Path dir = tmp.getRoot().toPath();
        Path forge = TestJars.jar(dir, "my-pathing-mod.jar", TestJars.soprano("forge", "1.1.6", "26.3"));
        assertEquals(new Flavor("api", "forge"), SelfJar.flavorOf(forge));
        Map<String, String> unopt = TestJars.soprano("fabric", "1.1.6", "26.3");
        unopt.put(SelfJar.UNOPTIMIZED_MARKER, "cafebabe");
        Path u = TestJars.jar(dir, "soprano.jar", unopt);
        assertEquals(new Flavor("unoptimized", "fabric"), SelfJar.flavorOf(u));
        // the name lies about the loader, the metadata doesn't
        Path liar = TestJars.jar(dir, "soprano-api-fabric-1.1.6.jar", TestJars.soprano("neoforge", "1.1.6", "26.3"));
        assertEquals(new Flavor("api", "neoforge"), SelfJar.flavorOf(liar));
    }
}
