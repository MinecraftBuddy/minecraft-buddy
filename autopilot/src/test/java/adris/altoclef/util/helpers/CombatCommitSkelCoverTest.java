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

// (skel_cover_over_runs) test heel: in 10 of 23 skeleton deaths a low hp, crowd or unarmed run took the wheel from the cover and the
// arrows landed in the run. the dug-in cover takes the run's place with melee mobs about where the ground digs; a creeper close,
// lava near, a witch, or ground that will not dig keeps the run; a run under the line that takes an arrow digs in where it stands
public class CombatCommitSkelCoverTest {

    private static final long NEVER = CombatCommit.NEVER;
    private static final long NOW = 1000;
    private static final int SKELETON = 6;
    private static final int SECOND = 7;
    private static final int ZOMBIE = 10;

    // a skeleton with a line that hit us sinceHit ago
    private static Foe skeleton(double distance, long sinceHit) {
        return new Foe(SKELETON, distance, true, false, sinceHit, Kind.NORMAL, Double.POSITIVE_INFINITY, true, NEVER);
    }

    // a second skeleton with a line, its last arrow 30 ticks ago (two of them: no one-shooter fight to fall back on)
    private static Foe second(double distance) {
        return new Foe(SECOND, distance, true, false, 30, Kind.NORMAL, Double.POSITIVE_INFINITY, true, NEVER);
    }

    private static Foe zombie(int id, double distance, long sinceHit) {
        return new Foe(id, distance, false, false, sinceHit, Kind.NORMAL, distance - 0.6);
    }

    private static Tick tick(long now, float hp, boolean armed, Foe... foes) {
        return new Tick(now, hp, 0, 0, List.of(foes), null, Way.NONE, armed, false);
    }

    // a tick a run is walking, half a block a tick so the stuck rule has nothing to say
    private static Tick walking(long now, float hp, Foe... foes) {
        return new Tick(now, hp, (now - NOW) * 0.5, 0, List.of(foes), null, Way.NONE, true, true);
    }

    // the brain's facts: the cover takes the buddy, the ground digs or not, lava near or not, the key on or off
    private static CombatCommit.Skel facts(boolean cover, boolean dig, boolean lava, boolean overRuns) {
        return new CombatCommit.Skel(Set.of(SKELETON, SECOND), Set.of(SKELETON, SECOND), true, false, 0, cover, SkelCover.CLOSE_HP,
                SkelCover.RANGE, dig, lava, overRuns);
    }

    private static CombatCommit with(CombatCommit.Skel facts) {
        CombatCommit c = new CombatCommit();
        c.skelFacts(facts);
        return c;
    }

    // the old rule: a zombie close was the run whatever the cover said (CombatCommitPinnedTest)
    @Test
    public void aLowHpRunWithAZombieAboutIsTheDugInCover() {
        Foe[] scene = {skeleton(6, 20), second(12), zombie(ZOMBIE, 5, NEVER)};
        CombatCommit c = with(facts(true, true, false, true));
        assertEquals(Event.NONE, c.step(tick(NOW, 5, true, scene)));
        assertEquals(Mode.NONE, c.mode());
        assertEquals("skeleton cover instead of a run [LOW_HP]", c.takeNote());

        CombatCommit off = with(facts(true, true, false, false));
        assertEquals(Event.RUN_START, off.step(tick(NOW, 5, true, scene)));
        assertEquals(Why.LOW_HP, off.why());
    }

    @Test
    public void groundThatWillNotDigKeepsTheRunWithAZombieAbout() {
        CombatCommit c = with(facts(true, false, false, true));
        assertEquals(Event.RUN_START, c.step(tick(NOW, 5, true, skeleton(6, 20), second(12), zombie(ZOMBIE, 5, NEVER))));
        assertEquals(Why.LOW_HP, c.why());
    }

    // skeletons alone: the cover from the bag will do, the ground need not dig
    @Test
    public void skeletonsAloneNeedNoGroundToDig() {
        assertEquals(Event.NONE, with(facts(true, false, false, true)).step(tick(NOW, 5, true, skeleton(6, 20), second(12))));
    }

    @Test
    public void aCreeperWithinSixKeepsTheRunOneFurtherDoesNot() {
        Foe close = new Foe(11, 5, false, true, NEVER);
        CombatCommit c = with(facts(true, true, false, true));
        assertEquals(Event.RUN_START, c.step(tick(NOW, 5, true, skeleton(6, 20), second(12), zombie(ZOMBIE, 5, NEVER), close)));
        Foe far = new Foe(11, 10, false, true, NEVER);
        assertEquals(Event.NONE, with(facts(true, true, false, true)).step(tick(NOW, 5, true, skeleton(6, 20), second(12), zombie(ZOMBIE, 5, NEVER), far)));
    }

    @Test
    public void lavaNearKeepsTheRun() {
        CombatCommit c = with(facts(true, true, true, true));
        assertEquals(Event.RUN_START, c.step(tick(NOW, 5, true, skeleton(6, 20), second(12), zombie(ZOMBIE, 5, NEVER))));
    }

    @Test
    public void aWitchAboutKeepsTheRun() {
        Foe witch = new Foe(12, 7, true, false, NEVER, Kind.LOBBER);
        CombatCommit c = with(facts(true, true, false, true));
        assertEquals(Event.RUN_START, c.step(tick(NOW, 5, true, skeleton(6, 20), second(12), zombie(ZOMBIE, 5, NEVER), witch)));
    }

    // the cover not on offer (no block, no ground, a cover that failed lately): the run as before
    @Test
    public void noCoverOnOfferKeepsTheRun() {
        CombatCommit c = with(facts(false, true, false, true));
        assertEquals(Event.RUN_START, c.step(tick(NOW, 5, true, skeleton(6, 20), second(12), zombie(ZOMBIE, 5, NEVER))));
    }

    // three zombies on us under a skeleton's arrows: the crowd run was the one the arrows landed in
    @Test
    public void theCrowdRunIsTheDugInCover() {
        Foe[] scene = {skeleton(9, 20), second(12), zombie(ZOMBIE, 1.5, 10), zombie(ZOMBIE + 1, 2, 30), zombie(ZOMBIE + 2, 2.5, 40)};
        CombatCommit off = with(facts(true, true, false, false));
        assertEquals(Event.RUN_START, off.step(tick(NOW, 16, true, scene)));
        assertEquals(Why.CROWD, off.why());
        CombatCommit c = with(facts(true, true, false, true));
        assertEquals(Event.NONE, c.step(tick(NOW, 16, true, scene)));
        assertEquals(Mode.NONE, c.mode());
    }

    // unarmed, a zombie's hit was a run through the skeleton's line
    @Test
    public void unarmedUnderASkeletonIsTheDugInCover() {
        Foe[] scene = {skeleton(10, 30), second(12), zombie(ZOMBIE, 1.5, 2)};
        CombatCommit off = with(facts(true, true, false, false));
        assertEquals(Event.RUN_START, off.step(tick(NOW, 16, false, scene)));
        CombatCommit c = with(facts(true, true, false, true));
        assertEquals(Event.NONE, c.step(tick(NOW, 16, false, scene)));
        assertEquals(Mode.NONE, c.mode());
    }

    // armed, a zombie's hit under the skeletons was a fight in their lines (and dropped the dig half way down)
    @Test
    public void armedAZombiesHitUnderTheSkeletonsIsTheDugInCover() {
        Foe[] scene = {skeleton(10, 30), second(12), zombie(ZOMBIE, 1.5, 2)};
        CombatCommit off = with(facts(true, true, false, false));
        assertEquals(Event.FIGHT_START, off.step(tick(NOW, 16, true, scene)));
        CombatCommit c = with(facts(true, true, false, true));
        assertEquals(Event.NONE, c.step(tick(NOW, 16, true, scene)));
        assertEquals(Mode.NONE, c.mode());
        assertEquals("skeleton cover instead of a fight with foe #" + ZOMBIE, c.takeNote());
        // the ground will not dig: the fight as before
        assertEquals(Event.FIGHT_START, with(facts(true, false, false, true)).step(tick(NOW, 16, true, scene)));
    }

    // a skeleton that walked up and hit is the cover too where the close rule would not walk up to it (here: no sword, no armour)
    @Test
    public void aSkeletonThatWalkedUpIsTheCoverNotTheFight() {
        Foe close = new Foe(SKELETON, 2, true, false, 3, Kind.NORMAL, 1.4, true, NEVER);
        CombatCommit off = with(facts(true, true, false, false));
        assertEquals(Event.FIGHT_START, off.step(tick(NOW, 16, true, close)));
        CombatCommit c = with(facts(true, true, false, true));
        assertEquals(Event.NONE, c.step(tick(NOW, 16, true, close)));
        assertEquals(Mode.NONE, c.mode());
        assertEquals("skeleton cover instead of a fight with foe #" + SKELETON + ": no sword", c.takeNote());
    }

    // with a sword, armour on and the hp for it, the one that walked up is fought as the close rule allows
    @Test
    public void aSkeletonThatWalkedUpIsFoughtWhereTheCloseRuleAllows() {
        Foe close = new Foe(SKELETON, 2, true, false, 3, Kind.NORMAL, 1.4, true, NEVER);
        CombatCommit c = with(new CombatCommit.Skel(Set.of(SKELETON), Set.of(SKELETON), true, true, 2, true, SkelCover.CLOSE_HP,
                SkelCover.RANGE, true, false, true));
        assertEquals(Event.FIGHT_START, c.step(tick(NOW, 17, true, close)));
    }

    // a run that began with no cover to be had (a failed one waits), then an arrow lands under the line: it ends there and digs in
    @Test
    public void aRunThatTakesAnArrowEndsDiggingIn() {
        CombatCommit c = with(facts(false, true, false, true));
        assertEquals(Event.RUN_START, c.step(walking(NOW, 7, skeleton(6, 20), second(12), zombie(ZOMBIE, 5, NEVER))));
        assertEquals(Mode.RUN, c.mode());
        // an old arrow, from before the run, ends nothing
        c.skelFacts(facts(true, true, false, true));
        assertEquals(Event.NONE, c.step(walking(NOW + 2, 7, skeleton(8, 22), second(12), zombie(ZOMBIE, 6, NEVER))));
        assertEquals(Mode.RUN, c.mode());
        assertEquals(Event.RUN_TO_COVER, c.step(walking(NOW + 10, 5, skeleton(9, 1), second(12), zombie(ZOMBIE, 6, NEVER))));
        assertEquals(Mode.NONE, c.mode());
    }

    @Test
    public void anArrowInARunEndsNothingWhereTheGroundWillNotDig() {
        CombatCommit c = with(facts(false, false, false, true));
        assertEquals(Event.RUN_START, c.step(walking(NOW, 7, skeleton(6, 20), second(12), zombie(ZOMBIE, 5, NEVER))));
        c.skelFacts(facts(true, false, false, true));
        assertEquals(Event.NONE, c.step(walking(NOW + 10, 5, skeleton(9, 1), second(12), zombie(ZOMBIE, 6, NEVER))));
        assertEquals(Mode.RUN, c.mode());
    }

    @Test
    public void switchedOffAnArrowInARunEndsNothing() {
        CombatCommit c = with(facts(false, true, false, false));
        assertEquals(Event.RUN_START, c.step(walking(NOW, 7, skeleton(6, 20), second(12), zombie(ZOMBIE, 5, NEVER))));
        c.skelFacts(facts(true, true, false, false));
        assertEquals(Event.NONE, c.step(walking(NOW + 10, 5, skeleton(9, 1), second(12), zombie(ZOMBIE, 6, NEVER))));
        assertEquals(Mode.RUN, c.mode());
    }
}
