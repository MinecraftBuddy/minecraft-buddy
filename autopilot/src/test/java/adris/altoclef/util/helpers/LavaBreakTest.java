package adris.altoclef.util.helpers;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

// no block broken that lets lava into its cell (dig_lava_check): LavaBreak.refuse
public class LavaBreakTest {

    private enum C {STONE, AIR, LAVA, WATER}

    // a world of stone with the cells set here
    private static final class World implements LavaBreak.Cells {
        private final Map<String, C> cells = new HashMap<>();

        World set(int x, int y, int z, C c) {
            cells.put(x + "," + y + "," + z, c);
            return this;
        }

        private C at(int x, int y, int z) {
            return cells.getOrDefault(x + "," + y + "," + z, C.STONE);
        }

        @Override
        public boolean lava(int x, int y, int z) {
            return at(x, y, z) == C.LAVA;
        }

        @Override
        public boolean solid(int x, int y, int z) {
            return at(x, y, z) == C.STONE;
        }

        @Override
        public boolean open(int x, int y, int z) {
            return at(x, y, z) == C.AIR;
        }
    }

    @Test
    public void stoneWithNoLavaByItBreaks() {
        assertFalse(LavaBreak.refuse(new World(), 0, 0, 0));
        // lava two cells off behind stone stays where it is
        assertFalse(LavaBreak.refuse(new World().set(2, 0, 0, C.LAVA), 0, 0, 0));
        // water beside: not lava
        assertFalse(LavaBreak.refuse(new World().set(1, 0, 0, C.WATER), 0, 0, 0));
    }

    @Test
    public void lavaBesideOverOrUnderRefuses() {
        assertTrue(LavaBreak.refuse(new World().set(1, 0, 0, C.LAVA), 0, 0, 0));
        assertTrue(LavaBreak.refuse(new World().set(-1, 0, 0, C.LAVA), 0, 0, 0));
        assertTrue(LavaBreak.refuse(new World().set(0, 0, 1, C.LAVA), 0, 0, 0));
        assertTrue(LavaBreak.refuse(new World().set(0, 0, -1, C.LAVA), 0, 0, 0));
        assertTrue(LavaBreak.refuse(new World().set(0, 1, 0, C.LAVA), 0, 0, 0));
        // the dug-in cover's dig: the block under the feet with lava under it
        assertTrue(LavaBreak.refuse(new World().set(0, -1, 0, C.LAVA), 0, 0, 0));
    }

    @Test
    public void anOpenCellBesideWithLavaByItRefuses() {
        // a pool just scooped: its cell open beside the block, the pool's lava beside that cell, back within a lava tick
        assertTrue(LavaBreak.refuse(new World().set(1, 0, 0, C.AIR).set(2, 0, 0, C.LAVA), 0, 0, 0));
        assertTrue(LavaBreak.refuse(new World().set(1, 0, 0, C.AIR).set(1, 0, 1, C.LAVA), 0, 0, 0));
        // lava over the open cell pours down into it
        assertTrue(LavaBreak.refuse(new World().set(1, 0, 0, C.AIR).set(1, 1, 0, C.LAVA), 0, 0, 0));
        // lava under the open cell stays under it
        assertFalse(LavaBreak.refuse(new World().set(1, 0, 0, C.AIR).set(1, -1, 0, C.LAVA), 0, 0, 0));
        // water in the cell beside: the lava meets it there, not in the hole
        assertFalse(LavaBreak.refuse(new World().set(1, 0, 0, C.WATER).set(2, 0, 0, C.LAVA), 0, 0, 0));
    }

    // sand fallen on the buddy with lava beside it: dug out all the same (WorldSurvivalChain)
    @Test
    public void aBlockTheBodyIsInIsNeverRefused() {
        World w = new World().set(1, 0, 0, C.LAVA);
        assertTrue(LavaBreak.refuse(w, 0, 0, 0, false));
        assertFalse(LavaBreak.refuse(w, 0, 0, 0, true));
    }

    @Test
    public void aCellWithNothingToBreakIsNeverRefused() {
        assertFalse(LavaBreak.refuse(new World().set(0, 0, 0, C.AIR).set(1, 0, 0, C.LAVA), 0, 0, 0));
        assertFalse(LavaBreak.refuse(new World().set(0, 0, 0, C.LAVA).set(1, 0, 0, C.LAVA), 0, 0, 0));
    }
}
