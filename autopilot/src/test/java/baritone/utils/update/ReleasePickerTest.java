package baritone.utils.update;

import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

// against a trimmed copy of the real releases api answer: 1.2.0 on 1.21.11, 1.1.6 on 26.3, 1.0.3 on 1.21.4 and
// 1.1.6-anyversion on base 26.3
public class ReleasePickerTest {

    private static final Flavor API_FABRIC = new Flavor("api", "fabric");

    private static String fixture() throws IOException {
        try (InputStream in = ReleasePickerTest.class.getResourceAsStream("/baritone/utils/update/releases.json")) {
            assertNotNull(in);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static ReleasePicker.Pick pick(String json, String running, String mc, Flavor f) {
        return ReleasePicker.pick(json, ModVersion.parse(running), mc, f);
    }

    @Test
    public void baseOn263PicksOnly263Base() throws IOException {
        // 1.2.0 is a bigger number but it's the 1.21.11 line, and the anyversion one is a different channel
        ReleasePicker.Pick p = pick(fixture(), "1.1.5", "26.3", API_FABRIC);
        assertNotNull(p);
        assertEquals("soprano-v1.1.6", p.tag());
        assertEquals("1.1.6", p.version().raw());
        assertNotNull(p.asset());
        assertEquals("soprano-api-fabric-1.1.6.jar", p.asset().name());
        assertEquals(64, p.asset().sha256().length());
        assertTrue(p.asset().size() > 0);
        assertTrue(p.asset().url().startsWith("https://github.com/"));
        assertNull(p.notInstallable());
    }

    @Test
    public void anyversionPicksOnlyAnyversion() throws IOException {
        ReleasePicker.Pick p = pick(fixture(), "1.1.5-anyversion", "26.3", API_FABRIC);
        assertNotNull(p);
        assertEquals("soprano-v1.1.6-anyversion", p.tag());
        assertEquals("soprano-api-fabric-1.1.6-anyversion.jar", p.asset().name());
    }

    @Test
    public void the12011LineIsNeverPickedFor263() throws IOException {
        // on 1.1.6 already, the only bigger number around is 1.2.0 for 1.21.11
        assertNull(pick(fixture(), "1.1.6", "26.3", API_FABRIC));
        assertNull(pick(fixture(), "1.1.6-anyversion", "26.3", API_FABRIC));
    }

    @Test
    public void otherLinesFindTheirOwn() throws IOException {
        assertEquals("soprano-v1.2.0", pick(fixture(), "1.1.9", "1.21.11", API_FABRIC).tag());
        assertEquals("soprano-v1.0.3", pick(fixture(), "1.0.2", "1.21.4", API_FABRIC).tag());
        assertNull(pick(fixture(), "1.0.3", "1.21.4", API_FABRIC));
    }

    @Test
    public void theDotInTheMcVersionIsLiteral() throws IOException {
        String json = fixture().replace("(26.3)", "(26x3)").replace("(base 26.3)", "(base 26x3)");
        assertNull(pick(json, "1.1.5", "26.3", API_FABRIC));
        assertNull(pick(json, "1.1.5-anyversion", "26.3", API_FABRIC));
    }

    @Test
    public void draftsAreSkipped() throws IOException {
        String json = fixture().replaceFirst("(\"tag_name\": \"soprano-v1\\.1\\.6\",\\s*\"name\": \"[^\"]*\",\\s*\"draft\": )false", "$1true");
        assertNull(pick(json, "1.1.5", "26.3", API_FABRIC));
    }

    @Test
    public void aMissingDigestMeansNotInstallable() throws IOException {
        String json = fixture().replaceAll("\"digest\": \"sha256:[0-9a-f]+\"", "\"digest\": null");
        ReleasePicker.Pick p = pick(json, "1.1.5", "26.3", API_FABRIC);
        assertNotNull(p);
        assertNull(p.asset());
        assertNotNull(p.notInstallable());
    }

    @Test
    public void eachFlavorGetsItsOwnAsset() throws IOException {
        String json = fixture();
        assertEquals("soprano-api-forge-1.1.6.jar", pick(json, "1.1.5", "26.3", new Flavor("api", "forge")).asset().name());
        assertEquals("soprano-api-neoforge-1.1.6.jar", pick(json, "1.1.5", "26.3", new Flavor("api", "neoforge")).asset().name());
        assertEquals("soprano-unoptimized-fabric-1.1.6.jar", pick(json, "1.1.5", "26.3", new Flavor("unoptimized", "fabric")).asset().name());
        // anyversion is fabric only, a forge player there gets the page link and no install
        ReleasePicker.Pick p = pick(json, "1.1.5-anyversion", "26.3", new Flavor("api", "forge"));
        assertNotNull(p);
        assertNull(p.asset());
        assertNotNull(p.notInstallable());
        // no flavor (dev run) still shows the release, just without an install
        ReleasePicker.Pick dev = pick(json, "1.1.5", "26.3", null);
        assertNotNull(dev);
        assertNull(dev.asset());
    }

    @Test
    public void anAssetOffTheGithubHostsIsNotInstallable() throws IOException {
        String json = fixture().replace("https://github.com/AverWasTaken", "https://evil.example/AverWasTaken");
        ReleasePicker.Pick p = pick(json, "1.1.5", "26.3", API_FABRIC);
        assertNotNull(p);
        assertNull(p.asset());
    }

    @Test(expected = IllegalStateException.class)
    public void notAListIsAnError() {
        ReleasePicker.pick("{\"message\": \"API rate limit exceeded\"}", ModVersion.parse("1.1.5"), "26.3", API_FABRIC);
    }

    @Test
    public void digestParsing() {
        assertNull(ReleasePicker.sha256(null));
        assertNull(ReleasePicker.sha256("md5:abc"));
        assertNull(ReleasePicker.sha256("sha256:abc"));
        String hex = "29ce088e334eceae5f7d2e51b189e9175659878b8b2a44e2f6add9c29b58d706";
        assertEquals(hex, ReleasePicker.sha256("sha256:" + hex.toUpperCase()));
    }

    // ----- stable <-> nightly

    private static ReleasePicker.Result check(String running, String mc, Flavor f) throws IOException {
        return ReleasePicker.check(fixture(), ModVersion.parse(running), mc, f);
    }

    private static String tagOf(ReleasePicker.Pick p) {
        return p == null ? null : p.tag();
    }

    @Test
    public void stableNeverSeesANightlyAsAnUpdate() throws IOException {
        ReleasePicker.Result r = check("1.1.6", "26.3", API_FABRIC);
        assertNull(r.pick());
        // the newest nightly is the way over, not an update. nightly.3 is a draft
        assertEquals("soprano-v1.1.7-nightly.2", tagOf(r.switchTarget()));
        assertEquals("soprano-api-fabric-1.1.7-nightly.2.jar", r.switchTarget().asset().name());
        // an older stable still gets its stable update, and still has the switch on the side
        ReleasePicker.Result older = check("1.1.5", "26.3", API_FABRIC);
        assertEquals("soprano-v1.1.6", tagOf(older.pick()));
        assertEquals("soprano-v1.1.7-nightly.2", tagOf(older.switchTarget()));
    }

    // the fixture a version on: stable 1.1.8, nightlies 1.1.9-nightly.1 and .2, so there is a stable new enough to switch to
    private static String later() throws IOException {
        return fixture().replace("1.1.7-nightly", "1.1.9-nightly").replace("1.1.6", "1.1.8");
    }

    @Test
    public void nightlySeesNewerNightlies() throws IOException {
        ReleasePicker.Result r = ReleasePicker.check(later(), ModVersion.parse("1.1.9-nightly.1"), "26.3", API_FABRIC);
        assertEquals("soprano-v1.1.9-nightly.2", tagOf(r.pick()));
        // back to stable is allowed to go down a version
        assertEquals("soprano-v1.1.8", tagOf(r.switchTarget()));
        assertEquals("soprano-api-fabric-1.1.8.jar", r.switchTarget().asset().name());
        ReleasePicker.Result newest = ReleasePicker.check(later(), ModVersion.parse("1.1.9-nightly.2"), "26.3", API_FABRIC);
        assertNull(newest.pick());
        assertEquals("soprano-v1.1.8", tagOf(newest.switchTarget()));
    }

    @Test
    public void noSwitchBackToAStableWithoutAnUpdater() throws IOException {
        // 1.1.6 never wrote its version into the jar, JarCheck would refuse it after the download
        ReleasePicker.Result r = check("1.1.7-nightly.1", "26.3", API_FABRIC);
        assertEquals("soprano-v1.1.7-nightly.2", tagOf(r.pick()));
        assertNull(r.switchTarget());
        assertNull(check("1.1.7-nightly.2-anyversion", "26.3", API_FABRIC).switchTarget());
    }

    @Test
    public void toNightlyOnlyWhenItIsAStepForward() throws IOException {
        // once 1.1.7 is out the 1.1.7 nightlies are behind it, no button
        ReleasePicker.Result r = check("1.1.7", "26.3", API_FABRIC);
        assertNull(r.pick());
        assertNull(r.switchTarget());
    }

    @Test
    public void aSwitchTargetHasToBeInstallable() throws IOException {
        // nightly.2 has no forge jar. nightly.1 does, but we don't go back to an older one for it
        ReleasePicker.Result forge = check("1.1.6", "26.3", new Flavor("api", "forge"));
        assertNull(forge.pick());
        assertNull(forge.switchTarget());
        // no flavor (dev run) can't install anything, so no switch either
        assertNull(check("1.1.6", "26.3", null).switchTarget());
        String noDigests = fixture().replaceAll("\"digest\": \"sha256:[0-9a-f]+\"", "\"digest\": null");
        assertNull(ReleasePicker.check(noDigests, ModVersion.parse("1.1.6"), "26.3", API_FABRIC).switchTarget());
    }

    @Test
    public void noOtherTrackNoTarget() throws IOException {
        // 1.21.4 only ever had stables
        ReleasePicker.Result r = check("1.0.3", "1.21.4", API_FABRIC);
        assertNull(r.pick());
        assertNull(r.switchTarget());
        // and 1.21.11's nightly stays on 1.21.11. 1.2.0 never wrote its version into the jar, so no way back to it
        assertEquals("soprano-v1.2.1-nightly.1", tagOf(check("1.2.0", "1.21.11", API_FABRIC).switchTarget()));
        assertNull(check("1.2.1-nightly.1", "1.21.11", API_FABRIC).switchTarget());
    }

    @Test
    public void anyversionKeepsToItself() throws IOException {
        ReleasePicker.Result stable = check("1.1.6-anyversion", "26.3", API_FABRIC);
        assertNull(stable.pick());
        assertEquals("soprano-v1.1.7-nightly.2-anyversion", tagOf(stable.switchTarget()));
        assertEquals("soprano-api-fabric-1.1.7-nightly.2-anyversion.jar", stable.switchTarget().asset().name());
        ReleasePicker.Result nightly = ReleasePicker.check(later(), ModVersion.parse("1.1.9-nightly.1-anyversion"), "26.3", API_FABRIC);
        assertEquals("soprano-v1.1.9-nightly.2-anyversion", tagOf(nightly.pick()));
        assertEquals("soprano-v1.1.8-anyversion", tagOf(nightly.switchTarget()));
        assertEquals("soprano-api-fabric-1.1.8-anyversion.jar", nightly.switchTarget().asset().name());
        // and the base nightlies never show up for anyversion or the other way round
        assertNull(check("1.1.7-nightly.2-anyversion", "26.3", API_FABRIC).pick());
        assertEquals("soprano-v1.1.7-nightly.2", tagOf(check("1.1.7-nightly.1", "26.3", API_FABRIC).pick()));
    }

    @Test
    public void nightlyJarNamesReadBack() {
        Flavor f = Flavor.fromFileName("soprano-api-fabric-1.1.7-nightly.1-anyversion.jar");
        assertNotNull(f);
        assertEquals("api", f.variant());
        assertEquals("fabric", f.loader());
        assertEquals("soprano-unoptimized-neoforge-1.1.7-nightly.2.jar", new Flavor("unoptimized", "neoforge").assetName("1.1.7-nightly.2"));
        assertEquals("neoforge", Flavor.fromFileName("soprano-api-neoforge-1.1.7-nightly.2.jar").loader());
    }
}
