package baritone.utils.update;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SwapHelperTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Path file(String name, String content) throws Exception {
        Path p = tmp.getRoot().toPath().resolve(name);
        Files.writeString(p, content);
        return p;
    }

    @Test
    public void normalSwap() throws Exception {
        Path old = file("soprano-api-fabric-1.1.6.jar", "old");
        Path staged = file("soprano-api-fabric-1.1.7.jar.new", "new");
        Path tries = file("soprano-api-fabric-1.1.7.jar.new.tries", "1");
        Path fin = tmp.getRoot().toPath().resolve("soprano-api-fabric-1.1.7.jar");
        assertEquals(SwapHelper.DONE, SwapHelper.swap(old, staged, fin, 1000, 10));
        assertFalse(Files.exists(old));
        assertFalse(Files.exists(staged));
        assertFalse(Files.exists(tries));
        assertFalse(Files.exists(old.resolveSibling(old.getFileName() + ".old")));
        assertEquals("new", Files.readString(fin));
    }

    @Test
    public void stagedGoneIsAQuietWin() throws Exception {
        // a second helper from a quick relaunch got there first
        Path old = file("soprano-api-fabric-1.1.6.jar", "old");
        Path staged = tmp.getRoot().toPath().resolve("soprano-api-fabric-1.1.7.jar.new");
        Path fin = tmp.getRoot().toPath().resolve("soprano-api-fabric-1.1.7.jar");
        assertEquals(SwapHelper.GONE, SwapHelper.swap(old, staged, fin, 1000, 10));
        assertEquals("old", Files.readString(old));
        assertFalse(Files.exists(fin));
    }

    @Test
    public void finalAlreadyThereGetsReplaced() throws Exception {
        Path old = file("soprano-api-fabric-1.1.6.jar", "old");
        Path staged = file("soprano-api-fabric-1.1.7.jar.new", "new");
        Path fin = file("soprano-api-fabric-1.1.7.jar", "someone downloaded this by hand");
        assertEquals(SwapHelper.DONE, SwapHelper.swap(old, staged, fin, 1000, 10));
        assertEquals("new", Files.readString(fin));
        assertFalse(Files.exists(old));
    }

    @Test
    public void sameNameForOldAndFinal() throws Exception {
        // a renamed jar could end up with the final name, the backup dance has to cope
        Path old = file("soprano-api-fabric-1.1.7.jar", "old");
        Path staged = file("soprano-api-fabric-1.1.7.jar.new", "new");
        assertEquals(SwapHelper.DONE, SwapHelper.swap(old, staged, old, 1000, 10));
        assertEquals("new", Files.readString(old));
        assertFalse(Files.exists(staged));
    }

    @Test
    public void oldAlreadyGoneStillMovesTheNewOneIn() throws Exception {
        Path old = tmp.getRoot().toPath().resolve("soprano-api-fabric-1.1.6.jar");
        Path staged = file("soprano-api-fabric-1.1.7.jar.new", "new");
        Path fin = tmp.getRoot().toPath().resolve("soprano-api-fabric-1.1.7.jar");
        assertEquals(SwapHelper.DONE, SwapHelper.swap(old, staged, fin, 1000, 10));
        assertEquals("new", Files.readString(fin));
    }

    @Test
    public void aFailedSecondMoveRollsTheOldJarBack() throws Exception {
        Path old = file("soprano-api-fabric-1.1.6.jar", "old");
        Path staged = file("soprano-api-fabric-1.1.7.jar.new", "new");
        // the final spot is a non empty directory, so moving onto it fails on every os, REPLACE_EXISTING or not
        Path fin = Files.createDirectory(tmp.getRoot().toPath().resolve("soprano-api-fabric-1.1.7.jar"));
        Files.writeString(fin.resolve("blocker"), "x");
        assertEquals(SwapHelper.GAVE_UP, SwapHelper.swap(old, staged, fin, 50, 10));
        assertEquals("old", Files.readString(old));
        assertEquals("new", Files.readString(staged));
        assertFalse(Files.exists(old.resolveSibling(old.getFileName() + ".old")));
        assertTrue(Files.isDirectory(fin));
    }

    @Test
    public void aDeadPidDoesNotWait() {
        long start = System.currentTimeMillis();
        SwapHelper.waitForExit(Long.MAX_VALUE);
        assertTrue(System.currentTimeMillis() - start < 5000);
    }
}
