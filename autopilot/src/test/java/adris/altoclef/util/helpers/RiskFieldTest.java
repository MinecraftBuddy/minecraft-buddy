package adris.altoclef.util.helpers;

import baritone.pathing.movement.CliffAvoidance.Cell;
import baritone.pathing.movement.CliffAvoidance.Cells;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class RiskFieldTest {
    // open everywhere but the cells set solid, and the cells on fire: no cell to walk through (solid, as the search's cells
    // have fire) and nothing a shot stops at
    private static final class World implements Cells {
        final Set<Long> solid = new HashSet<>();
        final Set<Long> fire = new HashSet<>();

        World fire(int x, int y0, int y1, int z0, int z1) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    fire.add(RiskField.pack(x, y, z));
                }
            }
            return this;
        }

        @Override
        public boolean blocksSight(int x, int y, int z) {
            return solid.contains(RiskField.pack(x, y, z));
        }

        World wall(int x, int y0, int y1, int z0, int z1) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    solid.add(RiskField.pack(x, y, z));
                }
            }
            return this;
        }

        @Override
        public Cell at(int x, int y, int z) {
            long key = RiskField.pack(x, y, z);
            return solid.contains(key) || fire.contains(key) ? Cell.SOLID : Cell.OPEN;
        }
    }

    private static final MobThreat ZOMBIE = MobThreat.of("zombie", 1);
    private static final MobThreat SKELETON = MobThreat.of("skeleton", 1);
    private static final MobThreat CREEPER = MobThreat.of("creeper", 1);
    private static final MobThreat BLAZE = MobThreat.of("blaze", 1);

    private static RiskField.Source mob(int id, MobThreat row, double x, double z, double want) {
        return new RiskField.Source(id, row, x, 64, z, want, 1, true);
    }

    private static RiskField.Pricer pricer(Cells world, double offset, boolean fireRes, RiskField.Source... mobs) {
        RiskField.Version v = new RiskField.Version(1, 0, null, fireRes, List.of(mobs));
        return new RiskField.Pricer(v, world, offset, 60, 1000, s -> false);
    }

    @Test
    public void meleeCostsOnlyWhereItCanBeByTheTimeWeAre() {
        // a zombie 4.5 blocks off (feet to the cell's centre): 3.07 blocks past its reach at 2.3 a second, 26.7 ticks
        RiskField.Pricer p = pricer(new World(), 0, false, mob(1, ZOMBIE, 10, 0.5, 1));
        assertEquals(0, p.hp(5, 64, 0, 20, 5, null), 1e-9);
        assertEquals(1.38 * 5 / 20, p.hp(5, 64, 0, 30, 5, null), 1e-9);
        // within its reach it costs at once
        assertEquals(1.38 * 5 / 20, p.hp(9, 64, 0, 0, 5, null), 1e-9);
    }

    @Test
    public void meleeStopsGrowingAtTheHorizon() {
        // 34.5 blocks off: 287 ticks to get there, past the 60 tick horizon however late we arrive
        RiskField.Pricer p = pricer(new World(), 0, false, mob(1, ZOMBIE, 40, 0.5, 1));
        assertEquals(0, p.hp(5, 64, 0, 1000, 5, null), 1e-9);
        // 7.5 blocks off: 52.8 ticks, within the horizon
        assertTrue(p.hp(32, 64, 0, 1000, 5, null) > 0);
    }

    @Test
    public void aSearchThatPlansAheadArrivesLater() {
        RiskField.Pricer now = pricer(new World(), 0, false, mob(1, ZOMBIE, 10, 0.5, 1));
        RiskField.Pricer ahead = pricer(new World(), 30, false, mob(1, ZOMBIE, 10, 0.5, 1));
        assertEquals(0, now.hp(5, 64, 0, 0, 5, null), 1e-9);
        assertTrue(ahead.hp(5, 64, 0, 0, 5, null) > 0);
    }

    @Test
    public void wantScalesItAndNoWayOnFootStopsMelee() {
        RiskField.Pricer half = pricer(new World(), 0, false, mob(1, ZOMBIE, 10, 0.5, 0.5));
        assertEquals(0.5 * 1.38 * 5 / 20, half.hp(9, 64, 0, 0, 5, null), 1e-9);
        RiskField.Source stuck = new RiskField.Source(1, ZOMBIE, 10, 64, 0.5, 1, 1, false);
        assertEquals(0, pricer(new World(), 0, false, stuck).hp(9, 64, 0, 0, 5, null), 1e-9);
    }

    @Test
    public void aCreeperBlastsOnce() {
        RiskField.Pricer p = pricer(new World(), 0, false, mob(1, CREEPER, 10, 0.5, 1));
        assertEquals(13 * 20 / 20.0, p.hp(9, 64, 0, 0, 20, null), 1e-9);
        assertEquals(CREEPER.hit(), p.hp(9, 64, 0, 0, 40, null), 1e-9);
    }

    @Test
    public void aShooterNeedsRangeAndSight() {
        RiskField.Pricer open = pricer(new World(), 0, false, mob(1, SKELETON, 0.5, 0.5, 1));
        double d = Math.sqrt(8 * 8 + (1.74 - 1.62) * (1.74 - 1.62));
        assertEquals(0.64 * Math.sqrt(4 / d) * 5 / 20, open.hp(8, 64, 0, 0, 5, null), 1e-9);
        // out of its 16 blocks
        assertEquals(0, open.hp(20, 64, 0, 0, 5, null), 1e-9);
        // behind a wall
        RiskField.Pricer walled = pricer(new World().wall(4, 60, 70, -2, 2), 0, false, mob(1, SKELETON, 0.5, 0.5, 1));
        assertEquals(0, walled.hp(8, 64, 0, 0, 5, null), 1e-9);
        assertEquals(1, walled.rays);
        // asked again, the cell's answer is kept
        walled.hp(8, 64, 0, 40, 5, null);
        assertEquals(1, walled.rays);
    }

    @Test
    public void pastTheRayBudgetACellCountsAsSeen() {
        RiskField.Version v = new RiskField.Version(1, 0, null, false, List.of(mob(1, SKELETON, 0.5, 0.5, 1)));
        RiskField.Pricer none = new RiskField.Pricer(v, new World().wall(4, 60, 70, -2, 2), 0, 60, 0, s -> false);
        assertTrue(none.hp(8, 64, 0, 0, 5, null) > 0);
        assertEquals(0, none.rays);
    }

    @Test
    public void fireResistanceTakesTheBlazesShotsAndBurn() {
        RiskField.Pricer p = pricer(new World(), 0, true, mob(1, BLAZE, 0.5, 0.5, 1));
        assertEquals(0, p.hp(10, 64, 0, 0, 5, null), 1e-9);
        assertTrue(pricer(new World(), 0, false, mob(1, BLAZE, 0.5, 0.5, 1)).hp(10, 64, 0, 0, 5, null) > 0);
    }

    @Test
    public void eachMobsShareIsCountedAndASkippedOneCostsNothing() {
        RiskField.Version v = new RiskField.Version(1, 0, null, false, List.of(mob(1, ZOMBIE, 10, 0.5, 1), mob(2, ZOMBIE, -10, 0.5, 1)));
        RiskField.Pricer p = new RiskField.Pricer(v, new World(), 0, 60, 1000, s -> false);
        double[] each = new double[p.size()];
        p.hp(9, 64, 0, 0, 5, each);
        assertTrue(each[0] > 0);
        assertEquals(0, each[1], 1e-9);
        RiskField.Pricer skip = new RiskField.Pricer(v, new World(), 0, 60, 1000, s -> s.id() == 1);
        assertEquals(1, skip.size());
        assertEquals(0, skip.hp(9, 64, 0, 0, 5, null), 1e-9);
    }

    @Test
    public void aNewVersionOnlyWhenTheMobsChange() {
        List<RiskField.Source> one = List.of(mob(1, ZOMBIE, 10, 0.5, 1));
        assertFalse(RiskField.differs(one, List.of(mob(1, ZOMBIE, 10.8, 0.5, 1)), RiskField.MOVE));
        assertTrue(RiskField.differs(one, List.of(mob(1, ZOMBIE, 11.5, 0.5, 1)), RiskField.MOVE));
        assertTrue(RiskField.differs(one, List.of(mob(1, ZOMBIE, 10, 0.5, 0.5)), RiskField.MOVE));
        assertTrue(RiskField.differs(one, List.of(mob(2, ZOMBIE, 10, 0.5, 1)), RiskField.MOVE));
        assertTrue(RiskField.differs(one, List.of(), RiskField.MOVE));

        Object world = new Object();
        RiskField.offer(100, world, false, one);
        long id = RiskField.current().id();
        assertFalse(RiskField.offer(105, world, false, List.of(mob(1, ZOMBIE, 10.5, 0.5, 1))));
        assertEquals(id, RiskField.current().id());
        assertTrue(RiskField.offer(110, world, false, List.of(mob(1, ZOMBIE, 12, 0.5, 1))));
        assertNotEquals(id, RiskField.current().id());
        // another world is another field
        assertTrue(RiskField.offer(115, new Object(), false, List.of(mob(1, ZOMBIE, 12, 0.5, 1))));
        RiskField.clear(120);
        assertTrue(RiskField.current().sources().isEmpty());
    }

    @Test
    public void theLineSkipsItsEndsAndStopsAtSolid() {
        World w = new World();
        // the start and end cells themselves are never looked at
        w.solid.add(RiskField.pack(0, 0, 0));
        w.solid.add(RiskField.pack(5, 0, 3));
        assertTrue(RiskField.clear(w, 0.5, 0.5, 0.5, 5.5, 0.5, 3.5));
        w.solid.add(RiskField.pack(3, 0, 2));
        assertFalse(RiskField.clear(w, 0.5, 0.5, 0.5, 5.5, 0.5, 3.5));
        // straight up, and backwards
        w.solid.add(RiskField.pack(0, 4, 0));
        assertFalse(RiskField.clear(w, 0.5, 0.5, 0.5, 0.5, 6.5, 0.5));
        assertTrue(RiskField.clear(w, -0.5, 6.5, -0.5, -0.5, 0.5, -0.5));
    }

    // the flee search's first smoke (11 Nov 2026): blazes set the fortress walls alight, and the search's cells read fire as
    // solid, so a blaze 4 blocks off that kept hitting the buddy was priced at nothing on the buddy's own cell
    @Test
    public void fireOnTheLineIsNoCoverFromAShot() {
        RiskField.Source blaze = mob(1, BLAZE, 0.5, 0.5, 1);
        double open = pricer(new World(), 0, false, blaze).hp(5, 64, 0, 0, 20, null);
        assertTrue(open > 0.5);
        // a sheet of fire between them at eye height and below: the same price
        assertEquals(open, pricer(new World().fire(3, 64, 67, -2, 2), 0, false, blaze).hp(5, 64, 0, 0, 20, null), 1e-9);
        // a wall there hides the cell
        assertEquals(0, pricer(new World().wall(3, 64, 67, -2, 2), 0, false, blaze).hp(5, 64, 0, 0, 20, null), 1e-9);
        // cells that say no more than solid keep the old reading: solid stops the line
        Cells plain = (x, y, z) -> x == 3 ? Cell.SOLID : Cell.OPEN;
        assertEquals(0, pricer(plain, 0, false, blaze).hp(5, 64, 0, 0, 20, null), 1e-9);
    }

    // a blaze holds its place: under fire resistance, where its fireballs do nothing, only a cell within its bite's reach (2)
    // costs anything, however long the buddy takes to get there
    @Test
    public void aBlazesBiteCostsOnlyWithinItsReach() {
        RiskField.Pricer p = pricer(new World(), 0, true, mob(1, BLAZE, 0.5, 0.5, 1));
        // the cell's centre one block off, and the one at its reach: bitten, 3.12 a second
        assertEquals(3.12, p.hp(1, 64, 0, 0, 20, null), 1e-9);
        assertEquals(3.12, p.hp(2, 64, 0, 0, 20, null), 1e-9);
        // 3 and 4 off: not, at the horizon either (a mob at 0.5 a second would have come 1.5 closer by then)
        assertEquals(0, p.hp(3, 64, 0, 60, 20, null), 1e-9);
        assertEquals(0, p.hp(4, 64, 0, 60, 20, null), 1e-9);
        // a zombie as far off does come
        assertTrue(pricer(new World(), 0, true, mob(2, ZOMBIE, 0.5, 0.5, 1)).hp(3, 64, 0, 60, 20, null) > 0);
    }
    // the repair after smoke9: a blaze in its bite follows a step: a cell a block past its reach costs once the walk gets there
    // after the blaze could (a zombie's pace), a cell farther never. smoke9 r4 fled 1 to 2 blocks to cells priced 0
    @Test
    public void aBlazeInItsBiteFollowsAStep() {
        RiskField.Pricer p = pricer(new World(), 0, true, mob(1, MobThreat.biting(BLAZE), 0.5, 0.5, 1));
        assertEquals(3.12, p.hp(2, 64, 0, 0, 20, null), 1e-9);
        // 3 off: not at once (1 / 2.3 s, 8.7 ticks), at a second yes
        assertEquals(0, p.hp(3, 64, 0, 0, 20, null), 1e-9);
        assertEquals(3.12, p.hp(3, 64, 0, 20, 20, null), 1e-9);
        // 4 off: never
        assertEquals(0, p.hp(4, 64, 0, 60, 20, null), 1e-9);
    }
}
