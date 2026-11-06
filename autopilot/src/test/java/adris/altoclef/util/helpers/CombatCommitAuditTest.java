package adris.altoclef.util.helpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import adris.altoclef.util.helpers.CombatCommit.Event;
import adris.altoclef.util.helpers.CombatCommit.Foe;
import adris.altoclef.util.helpers.CombatCommit.Kind;
import adris.altoclef.util.helpers.CombatCommit.Mode;
import adris.altoclef.util.helpers.CombatCommit.Tick;
import adris.altoclef.util.helpers.CombatCommit.Way;
import adris.altoclef.util.helpers.CombatCommit.Why;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.junit.BeforeClass;
import org.junit.Test;

// round 109: the combat audit follow-ups, one test per rule
public class CombatCommitAuditTest {

    private static final long NEVER = CombatCommit.NEVER;
    private static final long NOW = 1000;
    private static final double FEET = 64;

    @BeforeClass
    public static void bootstrap() {
        ItemFixture.boot();
    }

    private static Foe witherAt(int id, double distance, double y) {
        return new Foe(id, distance, false, false, NEVER, Kind.NORMAL, Double.POSITIVE_INFINITY, true, NEVER, false, null, false, 0, y, 0,
                false, false, false, true);
    }

    private static Foe enderman(int id, double distance, long sinceHit) {
        return new Foe(id, distance, false, false, sinceHit, Kind.ENDERMAN);
    }

    private static Tick fightTick(long now, float hp, List<Foe> foes, Foe target, boolean lingering) {
        return new Tick(now, hp, 0, 0, foes, target, Way.NONE, true, false, false, RecoverHold.Body.NONE, false, 4.5f, false, lingering);
    }

    private static CombatCommit witherFight(Foe w, boolean lingering) {
        CombatCommit c = new CombatCommit();
        assertEquals(Event.FIGHT_START, c.step(fightTick(0, 20, List.of(w), null, lingering)));
        return c;
    }

    // ---- 1. the lone wither bail counts the wither effect

    @Test
    public void aLoneWitherFightBailsNearTenWhileTheEffectIsOn() {
        Foe w = witherAt(1, 3, 0);
        CombatCommit c = witherFight(w, true);
        assertEquals(Event.FIGHT_TO_RUN, c.step(fightTick(1, 9.5f, List.of(w), w, true)));
        assertEquals(Why.LOW_HP, c.why());
    }

    @Test
    public void aLoneWitherFightWithoutTheEffectStaysAtEightAndAHalf() {
        Foe w = witherAt(1, 3, 0);
        CombatCommit c = witherFight(w, false);
        assertEquals(Event.NONE, c.step(fightTick(1, 8.5f, List.of(w), w, false)));
        assertEquals(Mode.FIGHT, c.mode());
    }

    // ---- 3. an enderman never pulls us off a wither fight

    @Test
    public void anEndermanThatHitUsDoesNotTakeTheTargetFromAWither() {
        Foe w = witherAt(1, 3, 0);
        CombatCommit c = witherFight(w, false);
        Foe e = enderman(5, 3, 0);
        for (long now = 1; now < 20; now++) {
            assertNotEquals("tick " + now, Event.FIGHT_NEXT, c.step(fightTick(now, 20, List.of(w, e), w, false)));
        }
        assertEquals(1, c.targetId());
    }

    @Test
    public void anEndermanAboutDoesNotHideALowHpRunFromAWitherPack() {
        Foe e = enderman(5, 6, NEVER);
        List<Foe> foes = List.of(witherAt(1, 3, 0), witherAt(2, 5, 0), e);
        CombatCommit c = new CombatCommit();
        assertEquals(Event.RUN_START, c.step(new Tick(0, 6, 0, 0, foes, null, Way.NONE, true)));
        assertEquals(Why.LOW_HP, c.why());
    }

    // ---- 2 and 5. the perch

    private static Foe piglin(int id, double distance, long sinceHit) {
        return new Foe(id, distance, false, false, sinceHit, Kind.NORMAL, Double.POSITIVE_INFINITY, false, NEVER, true);
    }

    private static Foe creeper(int id, double distance) {
        return new Foe(id, distance, false, true, NEVER);
    }

    private static Map<Integer, Double> heights() {
        Map<Integer, Double> out = new HashMap<>();
        for (int id = 1; id <= 3; id++) out.put(id, 1.95);
        return out;
    }

    private static Foe[] pack(double d) {
        return new Foe[]{piglin(1, d, NEVER), piglin(2, d + 0.4, NEVER), piglin(3, d + 0.8, NEVER)};
    }

    private static Tick tick(long now, Foe... foes) {
        return new Tick(now, 20, 0.5, 0.5, List.of(foes), null);
    }

    private static PerchRules.Facts on(double feetY, int near6, int near4, boolean creeper4) {
        return new PerchRules.Facts(true, feetY, 0, 8, 6, true, 0, PerchRules.Facts.NEVER, false, heights(), near6, near4, false, creeper4);
    }

    private static CombatCommit perchedOnTop() {
        CombatCommit c = new CombatCommit();
        c.perchFacts(on(FEET, -1, -1, false));
        assertEquals(Event.RUN_START, c.step(tick(NOW - 1, pack(2))));
        assertEquals(Event.PERCH_START, c.step(tick(NOW, pack(4))));
        c.perchFacts(on(FEET + 3, 1, 1, false));
        assertEquals(Event.NONE, c.step(tick(NOW + 1, pack(4))));
        return c;
    }

    @Test
    public void aPerchWithSomebodyStuckAtTheFootStillEndsAtTheTotalLimit() {
        CombatCommit c = perchedOnTop();
        long end = NOW + PerchRules.CAP_TICKS * 2;
        for (long now = NOW + 2; now < end; now += 5) {
            assertEquals("tick " + now, Event.NONE, c.step(tick(now, pack(4))));
        }
        assertEquals(Event.PERCH_END, c.step(tick(end, pack(4))));
        assertTrue(c.takePerchNote().startsWith("perch fail: total cap"));
    }

    @Test
    public void aCreeperSixOutEndsAPerchButSevenDoesNot() {
        CombatCommit c = perchedOnTop();
        assertEquals(Event.NONE, c.step(tick(NOW + 5, creeper(9, 7), piglin(1, 4, NEVER))));
        assertEquals(Event.PERCH_END, c.step(tick(NOW + 6, creeper(9, 5.5), piglin(1, 4, NEVER))));
        assertTrue(c.takePerchNote().startsWith("perch fail: a creeper"));
    }

    @Test
    public void theCreeperFactEndsAPerchToo() {
        CombatCommit c = perchedOnTop();
        c.perchFacts(on(FEET + 3, 1, 1, true));
        assertEquals(Event.PERCH_END, c.step(tick(NOW + 5, pack(4))));
    }

    // ---- 4. a swap that fails lets the swing go on

    @Test
    public void aFailedAxeEquipFallsBackToThePlainPickAndThenToSwinging() {
        Item sword = Items.IRON_SWORD;
        Item axe = Items.IRON_AXE;
        // axe refused, the plain pick is what is already in hand: nothing moved, so melee goes on
        assertFalse(WeaponPick.swapHand(i -> false, axe, sword, true, () -> sword));
        // axe refused, a different plain pick takes
        assertTrue(WeaponPick.swapHand(i -> i == Items.DIAMOND_SWORD, axe, sword, true, () -> Items.DIAMOND_SWORD));
        // a plain pick that fails too
        assertFalse(WeaponPick.swapHand(i -> false, Items.DIAMOND_SWORD, sword, false, () -> sword));
        assertTrue(WeaponPick.swapHand(i -> true, axe, sword, true, () -> sword));
    }

    @Test
    public void aFailedPlainEquipDoesNotAskForTheFallback() {
        AtomicInteger asked = new AtomicInteger();
        assertFalse(WeaponPick.swapHand(i -> false, Items.DIAMOND_SWORD, Items.IRON_SWORD, false, () -> {
            asked.incrementAndGet();
            return Items.IRON_SWORD;
        }));
        assertEquals(0, asked.get());
    }

    @Test
    public void aResetWithNoWayOutSwingsAfterTheTimeoutButNeverOnTheFirstStuckTick() {
        long since = 500;
        assertFalse(SwordSwing.resetGaveUp(since, since));
        assertFalse(SwordSwing.resetGaveUp(since + SwordSwing.RESET_STUCK_TICKS - 1, since));
        assertTrue(SwordSwing.resetGaveUp(since + SwordSwing.RESET_STUCK_TICKS, since));
        assertFalse(SwordSwing.resetGaveUp(since + 1000, -1));
    }
}
