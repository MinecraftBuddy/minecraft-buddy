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
import java.util.List;
import java.util.Set;
import org.junit.Test;

// (witch_cover, witch_melee_reach) test heel's witches: a witch's hit where the dug-in cover holds is the cover, not the run out of
// its range (Why.LOBBER) that ended clear at 12 with the witch following; a witch in reach with a sword or an axe at hand and no
// other shooter's line is a fight, and in any hand once the run is cornered there (a climb out of a hole with the witch at 0 to
// 1 m for 24 s, no swing)
public class CombatCommitWitchTest {

    private static final long NEVER = CombatCommit.NEVER;
    private static final long NOW = 1000;
    private static final int WITCH = 9;
    private static final int SKELETON = 6;
    private static final int ZOMBIE = 10;

    // a witch that hit us sinceHit ago with a line; reach: the gap to its box (within reach at 3 or less)
    private static Foe witch(double distance, double reach, long sinceHit) {
        return new Foe(WITCH, distance, true, false, sinceHit, Kind.LOBBER, reach, true, NEVER);
    }

    private static Foe skeleton(double distance) {
        return new Foe(SKELETON, distance, true, false, NEVER, Kind.NORMAL, Double.POSITIVE_INFINITY, true, NEVER);
    }

    private static Foe zombie(double distance) {
        return new Foe(ZOMBIE, distance, false, false, NEVER, Kind.NORMAL, distance - 0.6);
    }

    private static Tick tick(long now, float hp, boolean armed, Foe... foes) {
        return new Tick(now, hp, 0, 0, List.of(foes), null, Way.NONE, armed, false);
    }

    // the brain's facts: the cover takes the buddy or not, the ground digs or not, lava near or not; witches: the witch's id with
    // witch_cover on, none with it off
    private static CombatCommit.Skel facts(boolean cover, boolean dig, boolean lava, Set<Integer> witches) {
        return new CombatCommit.Skel(Set.of(), Set.of(), true, false, 0, cover, SkelCover.CLOSE_HP, SkelCover.RANGE, dig, lava, true,
                witches);
    }

    // witch_no_melee on as the brain hands it in; witch_melee_reach with a blade or not
    private static CombatCommit machine(CombatCommit.Skel facts, boolean reach, boolean blade) {
        CombatCommit c = new CombatCommit();
        c.witchNoMelee(true);
        c.witchMeleeFacts(reach, blade);
        c.skelFacts(facts);
        return c;
    }

    @Test
    public void aWitchsHitWhereTheCoverHoldsIsTheCoverNotTheRun() {
        CombatCommit c = machine(facts(true, true, false, Set.of(WITCH)), true, false);
        assertEquals(Event.NONE, c.step(tick(NOW, 20, true, witch(8, 7.5, 20))));
        assertEquals(Mode.NONE, c.mode());
        assertEquals("witch cover instead of a run [LOBBER]", c.takeNote());

        // witch_cover off (no witches handed in): the run out of its range, as before
        CombatCommit off = machine(facts(true, true, false, Set.of()), true, false);
        assertEquals(Event.RUN_START, off.step(tick(NOW, 20, true, witch(8, 7.5, 20))));
        assertEquals(Why.LOBBER, off.why());
    }

    @Test
    public void noCoverToBeHadKeepsTheRun() {
        // the cover does not take the buddy (in water, nothing to dig or place)
        CombatCommit noCover = machine(facts(false, true, false, Set.of(WITCH)), true, false);
        assertEquals(Event.RUN_START, noCover.step(tick(NOW, 20, true, witch(8, 7.5, 20))));
        assertEquals(Why.LOBBER, noCover.why());
        // lava within a knock
        CombatCommit lava = machine(facts(true, true, true, Set.of(WITCH)), true, false);
        assertEquals(Event.RUN_START, lava.step(tick(NOW, 20, true, witch(8, 7.5, 20))));
        // a zombie about where the ground will not dig
        CombatCommit hard = machine(facts(true, false, false, Set.of(WITCH)), true, false);
        assertEquals(Event.RUN_START, hard.step(tick(NOW, 20, true, witch(8, 7.5, 20), zombie(6))));
        // where it digs, the zombie is kept off by the shut top too
        CombatCommit digs = machine(facts(true, true, false, Set.of(WITCH)), true, false);
        assertEquals(Event.NONE, digs.step(tick(NOW, 20, true, witch(8, 7.5, 20), zombie(6))));
    }

    // hurt with the witch close: the low hp run is the cover too, and says it is the witch's
    @Test
    public void aLowHpRunFromAWitchIsTheCover() {
        CombatCommit c = machine(facts(true, true, false, Set.of(WITCH)), true, false);
        assertEquals(Event.NONE, c.step(tick(NOW, 6, true, witch(6, 5.5, 20))));
        assertEquals(Mode.NONE, c.mode());
        assertEquals("witch cover instead of a run [LOW_HP]", c.takeNote());
    }

    @Test
    public void aWitchInReachWithABladeAndNoOtherLineIsAFight() {
        CombatCommit c = machine(facts(true, true, false, Set.of(WITCH)), true, true);
        assertEquals(Event.FIGHT_START, c.step(tick(NOW, 20, true, witch(1.5, 1.0, 20))));
        assertEquals(WITCH, c.targetId());
        assertTrue(c.takeNote().startsWith("witch_melee_reach: a witch in reach 1.5 blocks off"));
    }

    @Test
    public void aSkeletonsLineOrNoBladeKeepsTheWitchOutOfMelee() {
        // a skeleton with a line 12 off: the 13 Nov fight, no melee; the cover (both kinds the cover's) or the run
        CombatCommit shot = machine(facts(true, true, false, Set.of(WITCH)), true, true);
        assertNotEquals(Event.FIGHT_START, shot.step(tick(NOW, 20, true, witch(1.5, 1.0, 20), skeleton(12))));
        assertFalse(shot.mode() == Mode.FIGHT);
        // no sword or axe
        CombatCommit bare = machine(null, true, false);
        assertEquals(Event.RUN_START, bare.step(tick(NOW, 20, true, witch(1.5, 1.0, 20))));
        assertEquals(Why.LOBBER, bare.why());
        // out of reach
        CombatCommit far = machine(null, true, true);
        assertEquals(Event.RUN_START, far.step(tick(NOW, 20, true, witch(5, 4.5, 20))));
        // the key off
        CombatCommit off = machine(null, false, true);
        assertEquals(Event.RUN_START, off.step(tick(NOW, 20, true, witch(1.5, 1.0, 20))));
        assertEquals(Why.LOBBER, off.why());
    }

    // a run from the witch that goes nowhere with it on top of the buddy: the corner's fight on it, bare-handed too
    private static boolean cornerFightsTheWitch(boolean reach) {
        CombatCommit c = machine(null, reach, false);
        boolean fought = false;
        for (long now = NOW; now < NOW + 300 && !fought; now++) {
            Event e = c.step(tick(now, 20, false, witch(1.0, 0.5, 3)));
            if (e == Event.RUN_TO_FIGHT && c.targetId() == WITCH) fought = true;
        }
        return fought;
    }

    @Test
    public void aStuckRunFightsAWitchInReachInAnyHand() {
        assertTrue(cornerFightsTheWitch(true));
        assertFalse(cornerFightsTheWitch(false));
    }

    // the brain's aura asks the same rule: a blade and no other line, or stuck
    @Test
    public void theAuraMaySwingAtAWitchInReachByTheSameRule() {
        CombatCommit c = machine(null, true, true);
        Foe w = witch(1.5, 1.0, 20);
        assertTrue(c.witchMelee(w, List.of(w), false));
        assertFalse(c.witchMelee(w, List.of(w, skeleton(10)), false));
        assertTrue(c.witchMelee(w, List.of(w, skeleton(10)), true));
        assertFalse(c.witchMelee(witch(5, 4.5, 20), List.of(w), true));
        CombatCommit unarmed = machine(null, true, false);
        assertFalse(unarmed.witchMelee(w, List.of(w), false));
        assertTrue(unarmed.witchMelee(w, List.of(w), true));
        assertFalse(machine(null, false, true).witchMelee(w, List.of(w), true));
    }
}
