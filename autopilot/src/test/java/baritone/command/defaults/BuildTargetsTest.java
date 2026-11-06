package baritone.command.defaults;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class BuildTargetsTest {

    private static final List<String> PLACED = List.of("castle.litematic", "villages/farm.schematic", "tower.schem",
        "tower.litematic", "Hut.nbt");

    @Test
    public void typedPathsBecomeTheGuiKeys() {
        assertEquals("castle.litematic", BuildTargets.libraryKey("castle.litematic"));
        assertEquals("villages/farm", BuildTargets.libraryKey("villages\\farm"));
        assertEquals("villages/farm", BuildTargets.libraryKey("./villages//farm"));
    }

    @Test
    public void outsideTheFolderHasNoKey() {
        assertNull(BuildTargets.libraryKey("../elsewhere/castle.schem"));
        assertNull(BuildTargets.libraryKey("villages/../../castle"));
        assertNull(BuildTargets.libraryKey("/home/user/castle.schem"));
        assertNull(BuildTargets.libraryKey("\\castle"));
        assertNull(BuildTargets.libraryKey("C:\\schematics\\castle.schem"));
        assertNull(BuildTargets.libraryKey("c:castle"));
        assertNull(BuildTargets.libraryKey("."));
        assertNull(BuildTargets.libraryKey(""));
    }

    @Test
    public void theExactPathWins() {
        assertEquals(List.of("tower.schem"), BuildTargets.matches(PLACED, "tower.schem"));
        assertEquals(List.of("villages/farm.schematic"), BuildTargets.matches(PLACED, "villages/farm.schematic"));
    }

    @Test
    public void noExtensionMatchesByName() {
        assertEquals(List.of("castle.litematic"), BuildTargets.matches(PLACED, "castle"));
        assertEquals(List.of("villages/farm.schematic"), BuildTargets.matches(PLACED, "villages/farm"));
        // two of them, the command lists both and asks for the extension
        assertEquals(List.of("tower.schem", "tower.litematic"), BuildTargets.matches(PLACED, "tower"));
        // the name lives in its folder, a bare farm isn't villages/farm
        assertEquals(List.of(), BuildTargets.matches(PLACED, "farm"));
    }

    @Test
    public void anExtensionTypedIsNotAStem() {
        assertEquals(List.of(), BuildTargets.matches(List.of("tower.schem.litematic"), "tower.schem"));
        assertEquals(List.of(), BuildTargets.matches(PLACED, "castle.schem"));
    }

    @Test
    public void caseOnlyMattersWhileItMatches() {
        assertEquals(List.of("Hut.nbt"), BuildTargets.matches(PLACED, "hut"));
        assertEquals(List.of("castle.litematic"), BuildTargets.matches(PLACED, "CASTLE.litematic"));
        // spelled right, the other spelling doesn't join in
        assertEquals(List.of("a.schem"), BuildTargets.matches(List.of("a.schem", "A.schem"), "a.schem"));
    }
}
