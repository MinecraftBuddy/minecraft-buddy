package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.tasks.speedrun.gamer.DetourRules.Inputs;
import adris.altoclef.tasks.speedrun.gamer.DetourRules.Step;
import adris.altoclef.tasks.speedrun.gamer.config.OverworldConfig;
import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

// how much coal is enough (the plan's smelts, not a flat 24), and that the detour only goes for coal it can see
public class CoalNeedTest {
    private static final OverworldConfig CFG = new OverworldConfig();

    private static Inputs holding(int coal, int need) {
        return new Inputs(true, true, coal, need, false, false, false, false, false);
    }

    private static DetourRules.Ore ore(boolean start, boolean keep) {
        return new DetourRules.Ore(() -> start, () -> keep, () -> false, () -> false);
    }

    // ---- the need

    @Test
    public void ironOwedAndMeatAreSmeltsAndACoalIsEight() {
        // 24 owed, 3 in the bag, 5 cooking = 16 ingots to smelt, plus 8 meat = 24 smelts = 3 coal, plus the margin
        assertEquals(3 + DetourSpec.NEED_MARGIN, DetourSpec.coalNeed(24, 3, 5, 8, 0));
    }

    @Test
    public void aPartCoalRoundsUp() {
        assertEquals(2 + DetourSpec.NEED_MARGIN, DetourSpec.coalNeed(9, 0, 0, 0, 0));
        assertEquals(1 + DetourSpec.NEED_MARGIN, DetourSpec.coalNeed(8, 0, 0, 0, 0));
    }

    // a split smelt: a coal does not go in two furnaces, every load is whole coal on its own
    @Test
    public void aSplitSmeltRoundsEveryLoad() {
        // 37 alone is 5 coal, 13/12/12 is 2 + 2 + 2
        assertEquals(5 + DetourSpec.NEED_MARGIN, DetourSpec.coalNeed(37, 0, 0, 0, 0));
        assertEquals(5 + DetourSpec.NEED_MARGIN, DetourSpec.coalNeed(37, 0, 0, 0, 0, 1));
        assertEquals(6 + DetourSpec.NEED_MARGIN, DetourSpec.coalNeed(37, 0, 0, 0, 0, 3));
        // 16 in two is 8 + 8, no rounding lost
        assertEquals(2 + DetourSpec.NEED_MARGIN, DetourSpec.coalNeed(16, 0, 0, 0, 0, 2));
        // what is held or cooking comes off before the split, the rest goes in the loads left
        // (24 left in two loads of 12: 2 + 2)
        assertEquals(4 + DetourSpec.NEED_MARGIN, DetourSpec.coalNeed(37, 0, 13, 0, 0, 2));
        // the meat is its own smelts on top, and wood still covers some
        assertEquals(7 + DetourSpec.NEED_MARGIN, DetourSpec.coalNeed(37, 0, 0, 8, 0, 3));
        assertEquals(4 + DetourSpec.NEED_MARGIN, DetourSpec.coalNeed(37, 0, 0, 0, 16, 3));
        // nothing owed is nothing to round
        assertEquals(DetourSpec.NEED_MARGIN, DetourSpec.coalNeed(10, 10, 0, 0, 0, 3));
    }

    @Test
    public void ingotsPendingInAFurnaceAreNotOwed() {
        assertEquals(DetourSpec.NEED_MARGIN, DetourSpec.coalNeed(10, 2, 8, 0, 0));
        // more held and cooking than owed is no smelts, not negative ones eating the meat
        assertEquals(1 + DetourSpec.NEED_MARGIN, DetourSpec.coalNeed(3, 5, 5, 8, 0));
    }

    @Test
    public void woodWeWouldBurnAnywayCountsAsFuel() {
        assertEquals(2 + DetourSpec.NEED_MARGIN, DetourSpec.coalNeed(24, 0, 0, 0, 12));
        assertEquals(DetourSpec.NEED_MARGIN, DetourSpec.coalNeed(24, 0, 0, 0, 30));
    }

    @Test
    public void nothingLeftToSmeltStillWantsTheMargin() {
        assertEquals(DetourSpec.NEED_MARGIN, DetourSpec.coalNeed(0, 0, 0, 0, 0));
    }

    @Test
    public void theCeilingHoldsWhateverThePlanSays() {
        assertEquals(DetourSpec.NEED_CEILING, DetourSpec.coalNeed(300, 0, 0, 64, 0));
        assertEquals(16, DetourSpec.NEED_CEILING);
    }

    // ---- the rules stop at the need

    @Test
    public void holdingTheNeedMeansNoDetour() {
        assertEquals(Step.IDLE, new DetourRules().tick(1000, holding(5, 5), DetourSpec.COAL.limits(CFG), ore(true, true)));
        assertEquals(Step.START, new DetourRules().tick(1000, holding(4, 5), DetourSpec.COAL.limits(CFG), ore(true, true)));
    }

    @Test
    public void theCeilingIsTheStopForAHugePlan() {
        int need = DetourSpec.coalNeed(300, 0, 0, 0, 0);
        assertEquals(Step.IDLE, new DetourRules().tick(1000, holding(16, need), DetourSpec.COAL.limits(CFG), ore(true, true)));
        assertEquals(Step.START, new DetourRules().tick(1000, holding(15, need), DetourSpec.COAL.limits(CFG), ore(true, true)));
    }

    @Test
    public void reachingTheNeedMidDetourEndsIt() {
        DetourRules rules = new DetourRules();
        assertEquals(Step.START, rules.tick(1000, holding(2, 5), DetourSpec.COAL.limits(CFG), ore(true, true)));
        assertEquals(Step.KEEP, rules.tick(1001, holding(4, 5), DetourSpec.COAL.limits(CFG), ore(true, true)));
        assertEquals(Step.DONE, rules.tick(1002, holding(5, 5), DetourSpec.COAL.limits(CFG), ore(true, true)));
        assertFalse(rules.running());
    }

    @Test
    public void aLowerConfigCapStillCaps() {
        OverworldConfig cfg = new OverworldConfig();
        cfg.coalSideCap = 3;
        assertTrue(DetourRules.enough(holding(3, 5), DetourSpec.COAL.limits(cfg)));
        assertFalse(DetourRules.enough(holding(3, 5), DetourSpec.COAL.limits(CFG)));
    }

    // ---- what we can see

    // stone where we say, air everywhere else
    private static final class Rock implements DetourSight.Cells {
        final Set<Long> solid = new HashSet<>();

        static long key(int x, int y, int z) {
            return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
        }

        Rock stone(int x, int y, int z) {
            solid.add(key(x, y, z));
            return this;
        }

        Rock box(int x0, int y0, int z0, int x1, int y1, int z1) {
            for (int x = x0; x <= x1; x++) {
                for (int y = y0; y <= y1; y++) {
                    for (int z = z0; z <= z1; z++) {
                        stone(x, y, z);
                    }
                }
            }
            return this;
        }

        Rock dig(int x, int y, int z) {
            solid.remove(key(x, y, z));
            return this;
        }

        @Override
        public boolean air(int x, int y, int z) {
            return !solid.contains(key(x, y, z));
        }

        @Override
        public boolean blocksView(int x, int y, int z) {
            return solid.contains(key(x, y, z));
        }
    }

    // eyes of a player standing at (0, 0, 0): feet cell 0, eyes 1.62 up
    private static final double EX = 0.5;
    private static final double EY = 1.62;
    private static final double EZ = 0.5;

    @Test
    public void oreInAnOpenWallFacingUsIsVisible() {
        // a cave wall at x = 4, the ore in it at eye height
        Rock w = new Rock().box(4, -2, -3, 6, 4, 3);
        assertTrue(DetourSight.visible(w, EX, EY, EZ, 4, 1, 0));
    }

    @Test
    public void oreInsideTheRockHasNoAirFace() {
        Rock w = new Rock().box(4, -2, -3, 6, 4, 3);
        assertFalse(DetourSight.visible(w, EX, EY, EZ, 5, 1, 0));
    }

    @Test
    public void anAirFaceBehindAWallIsNotInSight() {
        // the ore's open face looks into a pocket on our side, but a wall stands between us and the pocket
        Rock w = new Rock().box(4, -2, -3, 6, 4, 3).box(2, -2, -3, 2, 4, 3).box(3, -2, -3, 3, 4, 3).dig(3, 1, 0);
        assertFalse(DetourSight.visible(w, EX, EY, EZ, 4, 1, 0));
    }

    @Test
    public void anAirFacePointingAwayFromUsIsNotInSight() {
        // a cave on the far side of the vein: the only open face is the back one
        Rock w = new Rock().box(2, -2, -3, 6, 4, 3).dig(5, 1, 0);
        assertFalse(DetourSight.visible(w, EX, EY, EZ, 4, 1, 0));
    }

    @Test
    public void tooFarIsNotADetourEvenInPlainSight() {
        Rock w = new Rock().box(10, -2, -3, 12, 4, 3);
        assertFalse(DetourSight.visible(w, EX, EY, EZ, 10, 1, 0));
    }

    @Test
    public void startsOnVisibleCoalButNotOnHidden() {
        Rock w = new Rock().box(4, -2, -3, 6, 4, 3);
        boolean visible = DetourSight.visible(w, EX, EY, EZ, 4, 1, 0);
        boolean hidden = DetourSight.visible(w, EX, EY, EZ, 5, 1, 0);
        assertEquals(Step.START, new DetourRules().tick(1000, holding(0, 5), DetourSpec.COAL.limits(CFG), ore(visible, visible)));
        assertEquals(Step.IDLE, new DetourRules().tick(1000, holding(0, 5), DetourSpec.COAL.limits(CFG), ore(hidden, hidden)));
    }

    @Test
    public void theDetourEndsWhenOnlyHiddenCoalIsLeft() {
        // mined the visible ore at x = 4 (it is air now), the one left at x = 6 sits behind x = 5 stone with no face of its own
        Rock w = new Rock().box(4, -2, -3, 7, 4, 3).dig(4, 1, 0);
        assertFalse(DetourSight.visible(w, EX, EY, EZ, 6, 1, 0));
        DetourRules rules = new DetourRules();
        assertEquals(Step.START, rules.tick(1000, holding(0, 5), DetourSpec.COAL.limits(CFG), ore(true, true)));
        DetourRules.Ore left = new DetourRules.Ore(() -> false, () -> DetourSight.visible(w, EX, EY, EZ, 6, 1, 0), () -> false, () -> false);
        assertEquals(Step.SETTLE, rules.tick(1001, holding(1, 5), DetourSpec.COAL.limits(CFG), left));
        assertEquals(Step.DONE, rules.tick(1001 + DetourRules.DROP_GRACE_TICKS, holding(1, 5), DetourSpec.COAL.limits(CFG), left));
    }

    @Test
    public void theOreBehindTheMinedOneIsFairOnceItIsExposed() {
        // the vein carries on straight behind: mining x = 4 opens a face on x = 5 that looks right at us
        Rock w = new Rock().box(4, -2, -3, 7, 4, 3).dig(4, 1, 0);
        assertTrue(DetourSight.visible(w, EX, EY, EZ, 5, 1, 0));
    }
}
