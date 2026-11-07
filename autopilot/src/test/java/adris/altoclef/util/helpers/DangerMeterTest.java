package adris.altoclef.util.helpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import adris.altoclef.util.helpers.DangerMeter.Hazard;
import adris.altoclef.util.helpers.DangerMeter.Level;
import adris.altoclef.util.helpers.DangerMeter.Mob;
import adris.altoclef.util.helpers.DangerMeter.Reading;
import adris.altoclef.util.helpers.DangerMeter.Us;
import java.util.List;
import org.junit.Test;

// the shadow danger meter on made-up scenes: who counts, the fire resistance rule, the push term, the levels and their hold
public class DangerMeterTest {
    // full iron on Normal with an iron sword (6 damage, 1.6 a second)
    private static Us us(double hp, boolean fireRes) {
        return new Us(hp, 9.6, 15, 0, 2, 0, fireRes);
    }

    private static Mob mob(String kind, double dx, double dz, double want, boolean way, boolean sees) {
        MobThreat row = MobThreat.forMeter(kind, 1);
        return new Mob(kind, row, dx, 0, dz, want, way, sees, row != null ? row.hp() : 20);
    }

    private static Level levelFrom(Level start, Reading r) {
        return DangerMeter.classify(start, r.t(), r.push(), r.r(), r.hp());
    }

    @Test
    public void theRowsAreForIronOnNormal() {
        // the rows are through armour 15 on Normal, so that buddy scales them by 1
        assertEquals(1.0, DangerMeter.scale(MobThreat.forMeter("zombified_piglin", 1), us(20, false)), 1e-3);
        assertEquals(1.0, DangerMeter.scale(MobThreat.forMeter("piglin_brute", 1), us(20, false)), 1e-3);
        // bare skin takes more, Hard more again
        Us bare = new Us(20, 9.6, 0, 0, 2, 0, false);
        assertEquals(8.0 / 4.48, DangerMeter.scale(MobThreat.forMeter("zombified_piglin", 1), bare), 0.01);
        Us hard = new Us(20, 9.6, 0, 0, 3, 0, false);
        assertEquals(12.0 / 4.48, DangerMeter.scale(MobThreat.forMeter("zombified_piglin", 1), hard), 0.01);
    }

    @Test
    public void aLoneZombieFarOffIsCalm() {
        Reading r = DangerMeter.read(us(20, false), List.of(mob("zombie", 20, 0, 1, true, true)), List.of());
        assertEquals(0, r.t(), 1e-9);
        assertEquals(0, r.push(), 1e-9);
        assertEquals(Level.CALM, levelFrom(Level.CALM, r));
        // the same zombie next to us counts
        Reading close = DangerMeter.read(us(20, false), List.of(mob("zombie", 1, 0, 1, true, true)), List.of());
        assertTrue(close.t() > 1);
    }

    @Test
    public void aMeleeMobWithoutAWayCountsNothing() {
        Reading r = DangerMeter.read(us(20, false), List.of(mob("wither_skeleton", 1, 0, 1, false, true)), List.of());
        assertEquals(0, r.t(), 1e-9);
    }

    @Test
    public void threeBlazesWithSightAtTenHpIsFleeOrCover() {
        List<Mob> blazes = List.of(mob("blaze", 10, 0, 1, true, true), mob("blaze", 0, 10, 1, true, true), mob("blaze", -7, 7, 1, true, true));
        Reading r = DangerMeter.read(us(10, false), blazes, List.of());
        assertTrue("T " + r.t(), r.t() > 1);
        Level level = levelFrom(Level.CALM, r);
        assertTrue(level + " R " + r.r(), level == Level.FLEE || level == Level.COVER);
        assertEquals(3, r.top().size());
        assertEquals("blaze", r.top().get(0).kind());
    }

    @Test
    public void fireResistanceMakesBlazeFireballsCountZero() {
        List<Mob> blazes = List.of(mob("blaze", 10, 0, 1, true, true), mob("blaze", 0, 10, 1, true, true));
        assertTrue(DangerMeter.read(us(20, false), blazes, List.of()).t() > 0);
        Reading r = DangerMeter.read(us(20, true), blazes, List.of());
        assertEquals(0, r.t(), 1e-9);
        assertEquals(Level.CALM, levelFrom(Level.CALM, r));
        // its bite still counts when it hangs next to us
        Reading bite = DangerMeter.read(us(20, true), List.of(mob("blaze", 1, 0, 1, true, true)), List.of());
        assertTrue(bite.t() > 0);
        // and the fire hazards are gone with it
        assertEquals(0, DangerMeter.hazardCost("lava", 0, 20, true), 1e-9);
        assertEquals(0, DangerMeter.hazardCost("magma_block", 0, 20, true), 1e-9);
        assertEquals(0, DangerMeter.hazardCost("drop", DangerMeter.LAVA_DROP, 20, true), 1e-9);
        assertEquals(20, DangerMeter.hazardCost("lava", 0, 20, false), 1e-9);
        assertEquals(3, DangerMeter.hazardCost("wither_rose", 0, 20, true), 1e-9);
    }

    @Test
    public void aCalmZombifiedPiglinNextToLavaGivesPushRiskButNoThreat() {
        double want = DangerMeter.wantsToHurt(false, true, true, true, 1.5, 35);
        assertEquals(0, want, 1e-9);
        List<Hazard> lava = List.of(new Hazard("lava", -1.5, -0.5, 0.5, 0));
        Reading r = DangerMeter.read(us(20, false), List.of(mob("zombified_piglin", 1.5, 0, want, true, true)), lava);
        assertEquals(0, r.t(), 1e-9);
        assertTrue("push " + r.push(), r.push() > 0);
        assertEquals("zombified_piglin", r.top().get(0).kind());
        // no hazard, no push
        Reading safe = DangerMeter.read(us(20, false), List.of(mob("zombified_piglin", 1.5, 0, want, true, true)), List.of());
        assertEquals(0, safe.push(), 1e-9);
        // a passive body counts the same way, and only through the hazard
        Reading strider = DangerMeter.read(us(20, false), List.of(mob("strider", 1, 0, 0, true, true)), lava);
        assertEquals(0, strider.t(), 1e-9);
        assertTrue(strider.push() > 0);
    }

    @Test
    public void anAngryZombifiedPiglinCountsAsHostile() {
        double want = DangerMeter.wantsToHurt(true, true, false, true, 1.5, 35);
        assertEquals(1, want, 1e-9);
        List<Hazard> lava = List.of(new Hazard("lava", -1.5, -0.5, 0.5, 0));
        Reading r = DangerMeter.read(us(20, false), List.of(mob("zombified_piglin", 1.5, 0, want, true, true)), lava);
        assertTrue(r.t() > 1);
        // its knock towards the lava behind us is worse than a calm one's nudge
        Reading calm = DangerMeter.read(us(20, false), List.of(mob("zombified_piglin", 1.5, 0, 0, true, true)), lava);
        assertTrue(r.push() > calm.push());
    }

    @Test
    public void wantsToHurt() {
        // a hostile kind that sees us within its notice range: likely soon
        assertEquals(0.5, DangerMeter.wantsToHurt(false, true, false, true, 10, 16), 1e-9);
        assertEquals(0, DangerMeter.wantsToHurt(false, true, false, false, 10, 16), 1e-9);
        assertEquals(0, DangerMeter.wantsToHurt(false, true, false, true, 20, 16), 1e-9);
        // a passive kind never, unless it is after us (a provoked wolf)
        assertEquals(0, DangerMeter.wantsToHurt(false, false, false, true, 2, 16), 1e-9);
        assertEquals(1, DangerMeter.wantsToHurt(true, false, false, true, 2, 16), 1e-9);
    }

    @Test
    public void theHysteresisHoldsALevelOneSecond() {
        DangerMeter.Track track = new DangerMeter.Track();
        // three blazes at 10 hp: out of calm at once
        assertTrue(track.step(0, 4, 0, 0.5, 10));
        assertEquals(Level.FLEE, track.level());
        // all quiet half a second later: held
        assertFalse(track.step(10, 0, 0, Double.POSITIVE_INFINITY, 20));
        assertEquals(Level.FLEE, track.level());
        // a second on: calm
        assertTrue(track.step(20, 0, 0, Double.POSITIVE_INFINITY, 20));
        assertEquals(Level.CALM, track.level());
    }

    @Test
    public void aLevelIsLeftTwentyPercentPastItsBound() {
        // fight is left below R 1.6, not at 2
        assertEquals(Level.FIGHT, DangerMeter.classify(Level.FIGHT, 3, 0, 1.8, 20));
        assertEquals(Level.COVER, DangerMeter.classify(Level.FIGHT, 3, 0, 1.5, 20));
        // cover goes back to fight only at R 2.4
        assertEquals(Level.COVER, DangerMeter.classify(Level.COVER, 3, 0, 2.2, 20));
        assertEquals(Level.FIGHT, DangerMeter.classify(Level.COVER, 3, 0, 2.5, 20));
        // calm is left at T 1.2, entered from fight below 0.8
        assertEquals(Level.CALM, DangerMeter.classify(Level.CALM, 1.1, 0, 5, 20));
        assertEquals(Level.FIGHT, DangerMeter.classify(Level.FIGHT, 0.9, 0, 5, 20));
        // the margin: hp minus the loss at 4 or less is flee
        assertEquals(Level.FLEE, DangerMeter.classify(Level.FIGHT, 3, 0, 5, 7));
    }

    @Test
    public void patienceCountsTheLongestSpanWithoutProgress() {
        DangerMeter.Patience p = new DangerMeter.Patience();
        assertNull(p.step(0, Level.CALM, 0, 20, false));
        assertNull(p.step(100, Level.COVER, 4, 14, false));
        assertNull(p.step(200, Level.FLEE, 4, 12, false));
        // a kill at 300: 200 ticks without progress so far
        assertNull(p.step(300, Level.COVER, 4, 12, true));
        assertNull(p.step(350, Level.COVER, 4, 12, false));
        DangerMeter.Patience.Ended ended = p.step(360, Level.FIGHT, 4, 12, false);
        assertNotNull(ended);
        assertEquals(Level.FLEE, ended.worst());
        assertEquals(200, ended.ticks());
        assertEquals("danger: flee for 10.0 s without progress", DangerMeter.patienceLine(ended));
    }

    @Test
    public void theLinesReadTheSameInEveryLocale() {
        Reading r = DangerMeter.read(us(20, false), List.of(mob("zombie", 1, 0, 1, true, true)), List.of());
        String line = DangerMeter.levelLine(Level.FIGHT, r);
        assertTrue(line, line.startsWith("danger: fight, T "));
        assertTrue(line, line.contains(" hp/3s, push 0.0, R "));
        assertTrue(line, line.endsWith("top zombie 1.0 100%"));
        assertEquals("danger: hit 2.5 from blaze, level fight, T 0.0, R inf", DangerMeter.hitLine(2.5, "blaze", Level.FIGHT, DangerMeter.none(20)));
        assertEquals("danger: at death, level calm, T 0.0, push 0.0, R inf, top none", DangerMeter.deathLine(Level.CALM, DangerMeter.none(0)));
    }
}
