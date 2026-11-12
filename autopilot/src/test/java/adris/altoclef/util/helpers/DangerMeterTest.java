package adris.altoclef.util.helpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
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
        return DangerMeter.classify(start, r.t(), r.push(), r.risk(), r.hp());
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
        assertTrue(level + " risk " + r.risk(), level == Level.FLEE || level == Level.COVER);
        // three blazes at 10 hp lose the race: the risk is over 1
        assertTrue("risk " + r.risk(), r.risk() > 1);
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
        assertTrue(track.step(0, 4, 0, 2, 10));
        assertEquals(Level.FLEE, track.level());
        // all quiet half a second later: held
        assertFalse(track.step(10, 0, 0, 0, 20));
        assertEquals(Level.FLEE, track.level());
        // a second on: calm
        assertTrue(track.step(20, 0, 0, 0, 20));
        assertEquals(Level.CALM, track.level());
    }

    @Test
    public void aLevelIsLeftTwentyPercentPastItsBound() {
        // fight is left over risk 1 / 1.6 (R below 1.6), not at 0.5
        assertEquals(Level.FIGHT, DangerMeter.classify(Level.FIGHT, 3, 0, 1 / 1.8, 20));
        assertEquals(Level.COVER, DangerMeter.classify(Level.FIGHT, 3, 0, 1 / 1.5, 20));
        // cover goes back to fight only at risk 1 / 2.4 (R 2.4)
        assertEquals(Level.COVER, DangerMeter.classify(Level.COVER, 3, 0, 1 / 2.2, 20));
        assertEquals(Level.FIGHT, DangerMeter.classify(Level.COVER, 3, 0, 1 / 2.5, 20));
        // calm is left at T 1.2, entered from fight below 0.8
        assertEquals(Level.CALM, DangerMeter.classify(Level.CALM, 1.1, 0, 0.2, 20));
        assertEquals(Level.FIGHT, DangerMeter.classify(Level.FIGHT, 0.9, 0, 0.2, 20));
        // the margin: hp minus the loss at 4 or less is flee
        assertEquals(Level.FLEE, DangerMeter.classify(Level.FIGHT, 3, 0, 0.2, 7));
        // nothing on us is risk 0, and no way to win (no damage of ours) is risk inf
        assertEquals(0, DangerMeter.none(20).risk(), 1e-9);
        assertEquals(Level.FLEE, DangerMeter.classify(Level.FIGHT, 3, 0, Double.POSITIVE_INFINITY, 20));
    }

    // the levels as they were with R = D / K, kept here to hold the turned risk to them
    private static Level oldClassify(Level current, double t, double push, double r, double hp) {
        double calmBand = current == Level.CALM ? 1.2 : 0.8;
        if (t < calmBand && push < calmBand) return Level.CALM;
        double fleeR = current == Level.FLEE ? 1.2 : current == Level.COVER ? 0.8 : 1;
        double margin = 4 * (current == Level.FLEE ? 1.2 : 1);
        if (r < fleeR || hp - (t + push) <= margin) return Level.FLEE;
        double coverR = 2 * (current == Level.FIGHT ? 0.8 : current == Level.COVER || current == Level.FLEE ? 1.2 : 1);
        if (r < coverR) return Level.COVER;
        return Level.FIGHT;
    }

    @Test
    public void theTurnedRiskGivesTheOldLevels() {
        // a sweep of T, D and K from every level: risk K / D against R D / K, the old R inf as risk 0
        java.util.Random random = new java.util.Random(116);
        for (int i = 0; i < 200_000; i++) {
            Level from = Level.values()[random.nextInt(4)];
            double hp = 1 + random.nextDouble() * 19;
            double t = random.nextInt(5) == 0 ? 0 : random.nextDouble() * 8;
            double push = random.nextInt(3) == 0 ? 0 : random.nextDouble() * 3;
            double k = random.nextInt(6) == 0 ? 0 : random.nextDouble() * 30;
            double loss = t + push;
            double d = loss > 0 ? hp / (loss / DangerMeter.HORIZON) : Double.POSITIVE_INFINITY;
            double r = loss > 0 && k > 0 ? d / k : Double.POSITIVE_INFINITY;
            double risk = loss > 0 && k > 0 ? k / d : 0;
            assertEquals("from " + from + " T " + t + " push " + push + " R " + r, oldClassify(from, t, push, r, hp),
                    DangerMeter.classify(from, t, push, risk, hp));
        }
        // and on the bounds themselves
        for (Level from : Level.values()) {
            for (double r : new double[]{0.8, 1, 1.2, 1.6, 2, 2.4}) {
                assertEquals(from + " R " + r, oldClassify(from, 3, 0, r, 20), DangerMeter.classify(from, 3, 0, 1 / r, 20));
            }
        }
    }

    @Test
    public void theRiskIsTimeToKillOverTimeToDie() {
        Reading r = DangerMeter.read(us(20, false), List.of(mob("zombie", 1, 0, 1, true, true)), List.of());
        assertEquals(r.k() / r.d(), r.risk(), 1e-12);
        // a lone zombie next to a buddy in iron with a sword: the buddy wins the race easily
        assertTrue("risk " + r.risk(), r.risk() < 0.5);
        assertEquals(Level.FIGHT, levelFrom(Level.CALM, r));
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
        assertTrue(line, line.contains(" hp/3s, push 0.0, risk "));
        assertTrue(line, line.endsWith("top zombie 1.0 100%"));
        assertEquals("danger: hit 2.5 from blaze, level fight, T 0.0, risk 0.0", DangerMeter.hitLine(2.5, "blaze", Level.FIGHT, DangerMeter.none(20)));
        assertEquals("danger: at death, level calm, T 0.0, push 0.0, risk 0.0, top none", DangerMeter.deathLine(Level.CALM, DangerMeter.none(0)));
    }

    @Test
    public void aBowCountsForAFlyerOutOfReachWithALine() {
        // a blaze 10 off with a line to us: without a bow, its hp over the sword plus the walk; with one, over the bow alone
        Mob blaze = new Mob("blaze", MobThreat.forMeter("blaze", 1), 10, 3, 0, 1, true, true, 20);
        Us sword = new Us(20, 9.6, 15, 0, 2, 0, false);
        Us bow = new Us(20, 9.6, 15, 0, 2, 0, false, 4.0);
        double d = Math.sqrt(10 * 10 + 3 * 3);
        assertEquals(20 / 9.6 + (d - DangerMeter.OUR_REACH) / DangerMeter.OUR_SPEED, DangerMeter.killTime(blaze, sword, d, 20), 1e-9);
        assertEquals(20 / 4.0, DangerMeter.killTime(blaze, bow, d, 20), 1e-9);
        // no line, or in reach, or a walker: the sword as before
        Mob hidden = new Mob("blaze", MobThreat.forMeter("blaze", 1), 10, 3, 0, 1, true, false, 20);
        assertEquals(DangerMeter.killTime(hidden, sword, d, 20), DangerMeter.killTime(hidden, bow, d, 20), 1e-9);
        Mob hoglin = mob("hoglin", 10, 0, 1, true, true);
        assertEquals(DangerMeter.killTime(hoglin, sword, 10, 40), DangerMeter.killTime(hoglin, bow, 10, 40), 1e-9);
        assertEquals(DangerMeter.killTime(blaze, sword, 2.5, 20), DangerMeter.killTime(blaze, bow, 2.5, 20), 1e-9);
    }

    // the flee search's first smoke: under fire resistance blazes 2.4 to 2.9 blocks off set flee eight times with no hp
    // lost. a blaze hovers where it is and comes no closer: its bite counts within its reach (2), not as a mob on its way
    @Test
    public void aBlazesBiteCountsOnlyWithinItsReach() {
        Us us = us(20, true);
        assertEquals(0, DangerMeter.read(us, List.of(mob("blaze", 2.4, 0, 1, true, true)), List.of()).t(), 1e-9);
        assertEquals(0, DangerMeter.read(us, List.of(mob("blaze", 2.9, 0, 1, true, true)), List.of()).t(), 1e-9);
        // within its reach the whole horizon of biting
        assertEquals(3.12 * DangerMeter.HORIZON, DangerMeter.read(us, List.of(mob("blaze", 1.9, 0, 1, true, true)), List.of()).t(), 1e-6);
        // two of them 2.5 off under the potion are calm, where they read as flee at 8 hp
        Reading two = DangerMeter.read(us(8, true), List.of(mob("blaze", 2.5, 0, 1, true, true), mob("blaze", 0, 2.5, 1, true, true)), List.of());
        assertEquals(Level.CALM, levelFrom(Level.CALM, two));
        // a mob that chases still counts from the moment it can be on us: a zombie 2.9 off
        assertTrue(DangerMeter.read(us, List.of(mob("zombie", 2.9, 0, 1, true, true)), List.of()).t() > 1);
        // and without the potion the blaze's fireballs count at any distance in sight, as they did
        assertTrue(DangerMeter.read(us(20, false), List.of(mob("blaze", 2.9, 0, 1, true, true)), List.of()).t() > 1);
    }

    // ---- the task's goal weighed in (Tuning risk_flee_goal): the mobs a task fights on its own terms count as the next one

    private static Mob own(String kind, double dx, double dz) {
        MobThreat row = MobThreat.forMeter(kind, 1);
        return new Mob(kind, row, dx, 0, dz, 1, true, true, row.hp(), true);
    }

    private static List<Mob> fiveBlazes() {
        return List.of(own("blaze", 9, 0), own("blaze", 0, 9), own("blaze", -9, 0), own("blaze", 0, -9), own("blaze", 7, 7));
    }

    // five blazes with a line at the spawner the rods task came for. all of them dead before we are is a race lost at full
    // health, and the buddy ran from the place it has to be. counted as the next blaze it goes, and flees once it is hurt
    @Test
    public void theTasksOwnCountAsTheNextOne() {
        Reading meter = DangerMeter.read(us(20, false), fiveBlazes(), List.of());
        assertTrue(meter.risk() > 1);
        assertEquals(Level.FLEE, levelFrom(Level.CALM, meter));

        Reading goal = DangerMeter.read(us(20, false), fiveBlazes(), List.of(), true);
        // what they do to us counts in full
        assertEquals(meter.t(), goal.t(), 1e-9);
        assertEquals(meter.d(), goal.d(), 1e-9);
        // the race is the nearest blaze's: its hp over the sword plus the walk to it
        assertEquals(20 / 9.6 + (9 - DangerMeter.OUR_REACH) / DangerMeter.OUR_SPEED, goal.k(), 1e-9);
        assertEquals(Level.FIGHT, levelFrom(Level.CALM, goal));

        // at 10 hp the loss within the horizon leaves under the margin: flee, whatever the goal
        Reading hurt = DangerMeter.read(us(10, false), fiveBlazes(), List.of(), true);
        assertEquals(Level.FLEE, levelFrom(Level.FIGHT, hurt));
    }

    // a wither skeleton among the blazes is no goal of the rods task: it counts in the race as it did
    @Test
    public void aMobThatIsNotTheTasksCountsInFull() {
        Mob skeleton = mob("wither_skeleton", 4, 0, 1, true, true);
        double alone = DangerMeter.read(us(20, false), List.of(skeleton), List.of()).k();
        assertTrue(alone > 0);
        List<Mob> mobs = new java.util.ArrayList<>(fiveBlazes());
        mobs.add(skeleton);
        Reading goal = DangerMeter.read(us(20, false), mobs, List.of(), true);
        assertEquals(alone + 20 / 9.6 + (9 - DangerMeter.OUR_REACH) / DangerMeter.OUR_SPEED, goal.k(), 1e-9);
        assertEquals(DangerMeter.read(us(20, false), mobs, List.of()).t(), goal.t(), 1e-9);
    }

    // with no mob the task's own the reading is the meter's, and so is one read without the goal whatever the mobs say
    @Test
    public void withoutTheTasksOwnTheReadingIsTheMeters() {
        List<Mob> blazes = List.of(mob("blaze", 9, 0, 1, true, true), mob("blaze", 0, 9, 1, true, true), mob("blaze", -9, 0, 1, true, true));
        Reading meter = DangerMeter.read(us(12, false), blazes, List.of());
        Reading goal = DangerMeter.read(us(12, false), blazes, List.of(), true);
        assertEquals(meter.k(), goal.k(), 1e-9);
        assertEquals(meter.risk(), goal.risk(), 1e-9);
        assertEquals(DangerMeter.read(us(20, false), fiveBlazes(), List.of()).k(), DangerMeter.read(us(20, false), fiveBlazes(), List.of(), false).k(), 1e-9);
    }

    @Test
    public void theGoalLine() {
        Reading meter = DangerMeter.read(us(20, false), fiveBlazes(), List.of());
        Reading goal = DangerMeter.read(us(20, false), fiveBlazes(), List.of(), true);
        assertEquals("danger goal: fight where the meter says flee, 5 of the task's own counted as the next one: risk 0.4 for 2.0, "
                + "K 3.5 s for 17.6 s, hp 20.0, top blaze 9.0 20%, blaze 9.0 20%, blaze 9.0 20%",
                DangerMeter.goalLine(Level.FIGHT, goal, Level.FLEE, meter, 5));
    }

    // S137, one system: the hp the next piece needs is what a flee waits for before the way back. at that hp the same
    // danger reads under flee again, with the flee level's band
    @Test
    public void theHpTheNextPieceNeeds() {
        Reading goal = DangerMeter.read(us(20, false), fiveBlazes(), List.of(), true);
        double need = DangerMeter.need(goal);
        double piece = goal.k() * goal.loss() / DangerMeter.HORIZON;
        assertEquals(Math.max(goal.loss(), piece) + DangerMeter.MARGIN * (1 + DangerMeter.BAND), need, 1e-9);
        // the piece is the next blaze, not all five: the whole race would ask for more than the buddy has
        assertTrue(need < 20);
        assertTrue(DangerMeter.need(DangerMeter.read(us(20, false), fiveBlazes(), List.of())) > need);
        // at it the level leaves flee; at the margin alone (the loss within the horizon over it by a hair) it does too,
        // but the piece takes longer than the horizon
        Reading at = DangerMeter.read(us(need, false), fiveBlazes(), List.of(), true);
        assertNotEquals(Level.FLEE, DangerMeter.classify(Level.FLEE, at.t(), at.push(), at.risk(), at.hp()));
        Reading low = DangerMeter.read(us(goal.loss() + DangerMeter.MARGIN, false), fiveBlazes(), List.of(), true);
        assertEquals(Level.FLEE, DangerMeter.classify(Level.FLEE, low.t(), low.push(), low.risk(), low.hp()));
        // nothing on the buddy: the margin
        assertEquals(DangerMeter.MARGIN * (1 + DangerMeter.BAND), DangerMeter.need(DangerMeter.none(20)), 1e-9);
    }

    // (S137, the repair after smoke6) the burn still to come is hp already spent: the time to die and the margin count from
    // what is left after it, the lines say it, and with none the reading is as before
    @Test
    public void theBurnStillToComeCountsAgainstTheHp() {
        List<Mob> one = List.of(mob("wither_skeleton", 4, 0, 1, true, true));
        Reading plain = DangerMeter.read(us(12, false), one, List.of());
        Us burning = new Us(12, 9.6, 15, 0, 2, 0, false, 0, 5);
        Reading burnt = DangerMeter.read(burning, one, List.of());
        assertEquals(plain.t(), burnt.t(), 1e-9);
        assertEquals(12, burnt.hp(), 0);
        assertEquals(7, burnt.left(), 1e-9);
        assertEquals(plain.d() * 7 / 12, burnt.d(), 1e-9);
        assertTrue(burnt.risk() > plain.risk());
        // the margin by the hp left: a loss the buddy outlives at 12 is the last stand at 12 with 5 still to burn
        DangerMeter.Track track = new DangerMeter.Track();
        track.step(100, new Reading(3, 0, 10, 1, 0.1, 12, List.of()));
        assertEquals(Level.FIGHT, track.level());
        DangerMeter.Track hot = new DangerMeter.Track();
        hot.step(100, new Reading(3, 0, 10, 1, 0.1, 12, List.of(), 5));
        assertEquals(Level.FLEE, hot.level());
        assertTrue(DangerMeter.levelLine(Level.FLEE, burnt).contains(", hp 12.0, burn 5.0, top "));
        assertFalse(DangerMeter.levelLine(Level.FIGHT, plain).contains("burn"));
        // nothing on the buddy: the burn alone is no danger to run from (calm), only hp to heal
        assertEquals(0, DangerMeter.read(burning, List.of(), List.of()).risk(), 0);
    }

    // (S137, the repair after smoke6) the last ditch in a fight in hand: its target's share of the loss counts only up to its
    // time to kill inside the horizon. one wither skeleton 2.5 s from dead at T 12: 10 hp of it, not 12, and 15.5 hp is no
    // last ditch any more (smoke6 read it as one). a target past the horizon, or one the meter has no time for, counts whole
    @Test
    public void aFightsTargetHurtsOnlyTillItIsDead() {
        assertEquals(10, DangerMeter.ditchLoss(12, 12, 2.5), 1e-9);
        assertEquals(13, DangerMeter.ditchLoss(15, 12, 2.5), 1e-9);
        assertTrue(15.5 - DangerMeter.ditchLoss(12, 12, 2.5) > DangerMeter.MARGIN * (1 + DangerMeter.BAND));
        assertFalse(15.5 - 12 > DangerMeter.MARGIN * (1 + DangerMeter.BAND));
        assertEquals(12, DangerMeter.ditchLoss(12, 12, 4), 1e-9);
        assertEquals(12, DangerMeter.ditchLoss(12, 12, Double.NaN), 1e-9);
        assertEquals(12, DangerMeter.ditchLoss(12, 0, 1), 1e-9);
        assertEquals(0, DangerMeter.ditchLoss(12, 12, 0), 1e-9);
    }
}
