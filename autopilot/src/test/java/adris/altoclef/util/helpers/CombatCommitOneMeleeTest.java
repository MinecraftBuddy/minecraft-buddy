package adris.altoclef.util.helpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import adris.altoclef.util.helpers.CombatCommit.Event;
import adris.altoclef.util.helpers.CombatCommit.Foe;
import adris.altoclef.util.helpers.CombatCommit.Kind;
import adris.altoclef.util.helpers.CombatCommit.Mode;
import adris.altoclef.util.helpers.CombatCommit.Tick;
import adris.altoclef.util.helpers.CombatCommit.Way;
import adris.altoclef.util.helpers.CombatCommit.Why;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

// Tuning one_melee_fight (off by default; test vlucht): exactly one plain melee mob on the buddy, a sword or an axe in the bag,
// on the ground, hp at max(4, 2.5 x its worst hit) or more is its fight, not the low hp run; a second melee mob, a creeper,
// two skeletons with a line, no blade, off the ground or the hp under the line keep the run. with the switch off the run is
// the run it always was
public class CombatCommitOneMeleeTest {

    private static final long NEVER = CombatCommit.NEVER;
    private static final long NOW = 1000;
    private static final int ZOMBIE = 10;
    private Path tuning;
    private String tuningWas;

    @Before
    public void on() throws Exception {
        tuningWas = System.getProperty("buddy.tuning");
        tuning = Files.createTempFile("tuning", ".properties");
        System.setProperty("buddy.tuning", tuning.toString());
        set(true);
    }

    @After
    public void off() throws Exception {
        Files.writeString(tuning, "");
        Tuning.now("one_melee_fight", 0);
        Files.deleteIfExists(tuning);
        if (tuningWas == null) {
            System.clearProperty("buddy.tuning");
        } else {
            System.setProperty("buddy.tuning", tuningWas);
        }
        Tuning.now("one_melee_fight", 0);
    }

    private void set(boolean on) throws Exception {
        Files.writeString(tuning, "one_melee_fight=" + (on ? 1 : 0) + "\n");
        Tuning.now("one_melee_fight", 0);
    }

    // a zombie in contact that hit us sinceHit ago
    private static Foe zombie(int id, double distance, long sinceHit) {
        return new Foe(id, distance, false, false, sinceHit, Kind.NORMAL, distance - 0.6);
    }

    private static Foe creeper(double distance) {
        return new Foe(20, distance, false, true, NEVER, Kind.NORMAL);
    }

    // a skeleton with a line that hit us sinceHit ago
    private static Foe skeleton(int id, double distance, long sinceHit) {
        return new Foe(id, distance, true, false, sinceHit, Kind.NORMAL, Double.POSITIVE_INFINITY, true, NEVER);
    }

    private static Tick tick(long now, float hp, Foe... foes) {
        return new Tick(now, hp, 0, 0, List.of(foes), null, Way.NONE, true);
    }

    private static Tick fightTick(long now, float hp, Foe target, Foe... foes) {
        return new Tick(now, hp, 0, 0, List.of(foes), target, Way.NONE, true);
    }

    private static CombatCommit machine(boolean ground, boolean blade) {
        CombatCommit c = new CombatCommit();
        c.fleeFacts(ground, blade, false, SkelCover.HP_LINE);
        return c;
    }

    // a zombie in contact, then its hit of 3: hp 12 to 9, the next hit 3 puts it under the flee line (9 - 3 <= 8). the others
    // come with the hit
    private static Event hitTo9(CombatCommit c, Foe... others) {
        c.step(tick(NOW, 12, zombie(ZOMBIE, 1.5, NEVER)));
        return c.step(tick(NOW + 1, 9, concat(zombie(ZOMBIE, 1.5, 0), others)));
    }

    private static Foe[] concat(Foe first, Foe... rest) {
        Foe[] all = new Foe[rest.length + 1];
        all[0] = first;
        System.arraycopy(rest, 0, all, 1, rest.length);
        return all;
    }

    @Test
    public void oneZombieOnTheBuddyIsFoughtNotRunFrom() {
        CombatCommit c = machine(true, true);
        assertEquals(Event.FIGHT_START, hitTo9(c));
        assertEquals(Mode.FIGHT, c.mode());
        assertEquals(ZOMBIE, c.targetId());
        assertEquals(3f, c.worstHit(ZOMBIE), 1e-4);
        assertTrue(c.takeNote().startsWith("one_melee_fight: foe #10 on the buddy"));
    }

    @Test
    public void withTheSwitchOffItIsTheRun() throws Exception {
        set(false);
        CombatCommit c = machine(true, true);
        assertEquals(Event.RUN_START, hitTo9(c));
        assertEquals(Why.LOW_HP, c.why());
    }

    @Test
    public void underTheHoldLineItIsTheRun() {
        CombatCommit c = machine(true, true);
        c.step(tick(NOW, 12, zombie(ZOMBIE, 1.5, NEVER)));
        // a hit of 5: the line is 12.5, hp 7 under it
        assertEquals(Event.RUN_START, c.step(tick(NOW + 1, 7, zombie(ZOMBIE, 1.5, 0))));
        assertEquals(Why.LOW_HP, c.why());
    }

    @Test
    public void aSecondMeleeMobACreeperOrTwoSkeletonsKeepTheRun() {
        assertEquals(Event.RUN_START, hitTo9(machine(true, true), zombie(11, 6, NEVER)));
        assertEquals(Event.RUN_START, hitTo9(machine(true, true), creeper(7)));
        assertEquals(Event.RUN_START, hitTo9(machine(true, true), skeleton(30, 12, 60), skeleton(31, 14, 60)));
        // one skeleton with a line is still the shape (it is not shooting now: no shooter fight either)
        assertEquals(Event.FIGHT_START, hitTo9(machine(true, true), skeleton(30, 12, NEVER)));
    }

    @Test
    public void noBladeOrOffTheGroundKeepsTheRun() {
        assertEquals(Event.RUN_START, hitTo9(machine(true, false)));
        assertEquals(Event.RUN_START, hitTo9(machine(false, true)));
    }

    // the fight against it: the bail at 8 waits for the hold's line (7.5 for a hit of 3), under it the run
    @Test
    public void theFightsBailWaitsForTheHoldLine() {
        CombatCommit c = machine(true, true);
        assertEquals(Event.FIGHT_START, c.step(tick(NOW, 14, zombie(ZOMBIE, 1.5, 0))));
        Foe z = zombie(ZOMBIE, 1.5, 0);
        // (a tick apart, so each drop of 3 is watched as its hit)
        assertEquals(Event.NONE, c.step(fightTick(NOW + 1, 11, z, z)));
        assertEquals(Event.NONE, c.step(fightTick(NOW + 2, 8, z, z)));
        assertEquals(Mode.FIGHT, c.mode());
        assertEquals(Event.FIGHT_TO_RUN, c.step(fightTick(NOW + 3, 5, z, z)));
        assertEquals(Why.LOW_HP, c.why());
    }

    @Test
    public void theFightsBailAtTheOldLineWithTheSwitchOff() throws Exception {
        set(false);
        CombatCommit c = machine(true, true);
        assertEquals(Event.FIGHT_START, c.step(tick(NOW, 14, zombie(ZOMBIE, 1.5, 0))));
        Foe z = zombie(ZOMBIE, 1.5, 0);
        assertEquals(Event.NONE, c.step(fightTick(NOW + 1, 11, z, z)));
        assertEquals(Event.FIGHT_TO_RUN, c.step(fightTick(NOW + 2, 8, z, z)));
    }

    // the pure rule
    @Test
    public void theHoldAsPlainNumbers() {
        Foe z = zombie(ZOMBIE, 2, 10);
        assertNotNull(CombatCommit.meleeHold(List.of(z), 10, 3, Map.of(), 0, true, true, false));
        // the line: max(4, 2.5 x the worst hit), the remembered hit the bigger
        assertNull(CombatCommit.meleeHold(List.of(z), 10, 3, Map.of(ZOMBIE, 4.5f), 0, true, true, false));
        assertNotNull(CombatCommit.meleeHold(List.of(z), 4, 0, Map.of(), 0, true, true, false));
        assertNull(CombatCommit.meleeHold(List.of(z), 3.9f, 0, Map.of(), 0, true, true, false));
        // not on the buddy: walking up, no hit yet
        assertNull(CombatCommit.meleeHold(List.of(zombie(ZOMBIE, 6, NEVER)), 10, 0, Map.of(), 0, true, true, false));
        // two skeletons with a line by the cover's count
        assertNull(CombatCommit.meleeHold(List.of(z), 10, 0, Map.of(), 2, true, true, false));
        // wither, poison or fire ticking on the buddy
        assertNull(CombatCommit.meleeHold(List.of(z), 10, 0, Map.of(), 0, true, true, true));
        // a mob in the water, a cube that splits, a witch landing its throws
        assertNull(CombatCommit.meleeHold(List.of(z.withWet(true)), 10, 0, Map.of(), 0, true, true, false));
        assertNull(CombatCommit.meleeHold(List.of(z.withSplit("slime", true, 0, 0, 0)), 10, 0, Map.of(), 0, true, true, false));
        Foe witch = new Foe(40, 9, true, false, 20, Kind.LOBBER, Double.POSITIVE_INFINITY, true, NEVER);
        assertNull(CombatCommit.meleeHold(List.of(z, witch), 10, 0, Map.of(), 0, true, true, false));
        assertEquals(7.5f, CombatCommit.meleeHoldLine(3), 1e-4);
        assertNotNull(CombatCommit.meleeHold(List.of(z, creeper(9)), 10, 0, Map.of(), 0, true, true, false));
    }
}
