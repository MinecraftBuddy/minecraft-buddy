package adris.altoclef.util.helpers;

import static org.junit.Assert.assertEquals;

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

// (mounted_no_run) test heel: a night start with an empty hand ran 93 blocks uphill from a zombie on a zombie horse, four spear hits
// from 3 blocks off on the way and two after it. a mounted melee mob is never run from on foot: the cover where it holds (the brain
// wants it without a sword or an axe, or under the fight's hp), a run under way ends there, a fight's bail goes into it; with a
// blade and the hp the hit is fought as before; with no cover to be had the run as before
public class CombatCommitMountTest {

    private static final long NEVER = CombatCommit.NEVER;
    private static final long NOW = 1000;
    private static final int RIDER = 10;
    private static final int SPIDER = 11;

    // the zombie on its horse, sinceHit ago (its spear reaches from past the three block contact)
    private static Foe rider(double distance, long sinceHit) {
        return new Foe(RIDER, distance, false, false, sinceHit, Kind.NORMAL, distance - 0.6);
    }

    private static Foe spider(double distance, long sinceHit) {
        return new Foe(SPIDER, distance, false, false, sinceHit, Kind.NORMAL, distance - 0.6);
    }

    private static Tick tick(long now, float hp, boolean armed, Foe... foes) {
        return new Tick(now, hp, 0, 0, List.of(foes), null, Way.NONE, armed, false);
    }

    // a tick a run is walking, half a block a tick so the stuck rule has nothing to say
    private static Tick walking(long now, float hp, Foe... foes) {
        return new Tick(now, hp, (now - NOW) * 0.5, 0, List.of(foes), null, Way.NONE, false, true);
    }

    // the brain's facts: the cover takes the buddy or not (with the key on it wants it for a rider), the ground digs or not
    private static CombatCommit.Skel facts(boolean cover, boolean dig) {
        return new CombatCommit.Skel(Set.of(), Set.of(), true, false, 0, cover, SkelCover.CLOSE_HP, SkelCover.RANGE, dig, false, true,
                Set.of());
    }

    private static CombatCommit machine(boolean on, boolean blade, CombatCommit.Skel facts) {
        CombatCommit c = new CombatCommit();
        c.mountFacts(on, Set.of(RIDER), blade);
        c.skelFacts(facts);
        return c;
    }

    @Test
    public void aLowHpRunFromARiderIsTheCover() {
        CombatCommit c = machine(true, false, facts(true, true));
        assertEquals(Event.NONE, c.step(tick(NOW, 7, false, rider(3.3, 20))));
        assertEquals(Mode.NONE, c.mode());
        assertEquals("mount cover instead of a run [LOW_HP]", c.takeNote());

        // the key off: the brain wants no cover for it, the run as before
        CombatCommit off = machine(false, false, facts(false, true));
        assertEquals(Event.RUN_START, off.step(tick(NOW, 7, false, rider(3.3, 20))));
        assertEquals(Why.LOW_HP, off.why());
    }

    @Test
    public void unarmedARidersHitIsTheCoverNotARun() {
        CombatCommit c = machine(true, false, facts(true, true));
        assertEquals(Event.NONE, c.step(tick(NOW, 18, false, rider(2.5, 2))));
        assertEquals(Mode.NONE, c.mode());
        assertEquals("mount cover instead of a run [UNARMED]", c.takeNote());
    }

    // the overruns key off too: the rider's own rule still takes the cover in place of the run
    @Test
    public void theRidersRuleTakesTheCoverWithoutTheOverRunsKey() {
        CombatCommit c = new CombatCommit();
        c.mountFacts(true, Set.of(RIDER), false);
        c.skelFacts(new CombatCommit.Skel(Set.of(), Set.of(), true, false, 0, true, SkelCover.CLOSE_HP, SkelCover.RANGE, true, false,
                false, Set.of()));
        assertEquals(Event.NONE, c.step(tick(NOW, 7, false, rider(3.3, 20))));
        assertEquals("mounted_no_run: foe #" + RIDER + " on a mount, the cover in place of a run on foot [LOW_HP]", c.takeNote());
    }

    @Test
    public void groundThatWillNotDigKeepsTheRun() {
        CombatCommit c = machine(true, false, facts(true, false));
        assertEquals(Event.RUN_START, c.step(tick(NOW, 7, false, rider(3.3, 20))));
        assertEquals(Why.LOW_HP, c.why());
        assertEquals("mounted_no_run: foe #" + RIDER + " on a mount, no cover to be had here: the run [LOW_HP]", c.takeNote());
    }

    // a spider's hit began the run, then the rider came: the run ends where the buddy stands, into the cover
    @Test
    public void aRunUnderWayEndsInTheCoverWhenARiderComes() {
        CombatCommit c = machine(true, false, facts(false, true));
        assertEquals(Event.RUN_START, c.step(walking(NOW, 18, spider(2, 2))));
        assertEquals(Why.UNARMED, c.why());
        // the rider still 20 off: the run goes on
        assertEquals(Event.NONE, c.step(walking(NOW + 2, 18, spider(4, 4))));
        assertEquals(Mode.RUN, c.mode());
        // within 8, and the brain's cover takes the buddy for it now
        c.skelFacts(facts(true, true));
        assertEquals(Event.RUN_TO_COVER, c.step(walking(NOW + 4, 18, spider(6, 6), rider(6, NEVER))));
        assertEquals(Mode.NONE, c.mode());
    }

    @Test
    public void aRunUnderWayGoesOnWithNoCoverToBeHad() {
        CombatCommit c = machine(true, false, facts(false, false));
        assertEquals(Event.RUN_START, c.step(walking(NOW, 18, spider(2, 2))));
        c.skelFacts(facts(true, false));
        assertEquals(Event.NONE, c.step(walking(NOW + 4, 18, spider(6, 6), rider(6, NEVER))));
        assertEquals(Mode.RUN, c.mode());
    }

    // with a sword or an axe and 14 hp or more the rider's hit is fought (the brain wants no cover then); its bail under the hp
    // line goes into the cover, not a run
    @Test
    public void aBladeAndTheHpFightTheRiderAndItsBailIsTheCover() {
        CombatCommit c = machine(true, true, facts(false, true));
        assertEquals(Event.FIGHT_START, c.step(tick(NOW, 18, true, rider(2.5, 2))));
        assertEquals(RIDER, c.targetId());
        c.skelFacts(facts(true, true));
        assertEquals(Event.FIGHT_TO_COVER, c.step(new Tick(NOW + 1, 6, 0, 0, List.of(rider(2.5, 1)), rider(2.5, 1), Way.NONE, true, false)));
        assertEquals(Mode.NONE, c.mode());
    }

    // a pickaxe, or a blade under the fight's hp: the rider's hit is the cover, not the fight
    @Test
    public void aPickaxeAgainstARiderIsTheCover() {
        CombatCommit c = machine(true, false, facts(true, true));
        assertEquals(Event.NONE, c.step(tick(NOW, 12, true, rider(2.5, 2))));
        assertEquals(Mode.NONE, c.mode());
        assertEquals("mount cover instead of a fight with foe #" + RIDER, c.takeNote());

        CombatCommit off = machine(false, false, facts(false, true));
        assertEquals(Event.FIGHT_START, off.step(tick(NOW, 12, true, rider(2.5, 2))));
    }

    // a zombie on foot is no rider: its run is the old one
    @Test
    public void aZombieOnFootKeepsTheRun() {
        CombatCommit c = machine(true, false, facts(false, true));
        Foe walker = new Foe(12, 3.3, false, false, 20, Kind.NORMAL, 2.7);
        assertEquals(Event.RUN_START, c.step(tick(NOW, 7, false, walker)));
        assertEquals(Why.LOW_HP, c.why());
    }
}
