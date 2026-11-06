package adris.altoclef.util.helpers;

// the pure half of "what do we do about the blazes". no world, just numbers, same deal as CombatRules.
//
// three moods and that is all of them: kill the one blaze we picked, camp by the spawner until one is in reach, or run when hp
// says so. it used to have cover spots and hunts and holds on top, and flipped "fight, no hide, no this guy" about once a second
public final class BlazeFightRules {

    // a blaze this close is a threat whatever it is doing, it will bite
    public static final double MELEE_RANGE = 3;
    // past this a blaze is background noise for a fight: not worth a look, not worth the walk. it can still hit us, see FIRE_RANGE
    public static final double SIGHT_RANGE = 20;
    // a blaze shoots whatever it has a line on inside its follow range, 48 in the 26.3 jar. the spread gets wide out there but
    // fireballs still land, and the burn does the rest
    public static final double FIRE_RANGE = 48;
    // above the floor, in blocks, a blaze we are not after yet can still be hit by a bot standing under it. the one we are after
    // is kept up to STAY_REACHABLE_HEIGHT, blazes bob and one hovering right on the line would get dropped for breathing
    public static final double MAX_REACHABLE_HEIGHT = 3.5;
    public static final double STAY_REACHABLE_HEIGHT = 4.5;
    // blazes further than this are not worth the walk, the ones that matter come to us
    public static final double PICK_RANGE = 24;
    // the disengage only cares about blazes this close, the ones further out are not following us anywhere
    public static final double DISENGAGE_RANGE = 16;
    // a target that stays out of reach this long (it floated off over lava, the roof) is not the one, take the next. shorter and
    // a blaze bobbing at the height line gets dropped for breathing
    public static final long TARGET_GIVE_UP_TICKS = 100;

    // rods done and a blaze still has a line on us: keep fighting before the next task takes the wheel. the calm spell is how
    // long nothing has been close, the cap is how long we try (a blaze that follows us out is not going to give up)
    public static final int DISENGAGE_CALM_TICKS = 60;
    public static final int DISENGAGE_CAP_TICKS = 900;

    // run at or below this with a blaze looking at us. 10 was too late: a blaze in melee plus the fire under us took 15 hp to 9
    // in three seconds, and from there the run never got out
    public static final float RUN_HEALTH = 14;
    // run at or below this whether or not anything is looking, we are one volley from the respawn screen
    public static final float PANIC_HEALTH = 6;
    // once we are running we run until we are back up here. the 4 hp between this and RUN_HEALTH is what keeps the mode from
    // flipping every heal tick
    public static final float RECOVERED_HEALTH = 18;

    // sitting out of sight with no blaze looking and hp not going up for this long means it is not going to go up (no
    // food, hunger too low to regen). waiting for 18 hp then is waiting for ever
    public static final long RUN_GIVE_UP_TICKS = 600;

    // this many wither skeletons together is a pack, and a pack ends a rods run. 3 can still be fought, 5 cannot
    public static final int PACK_SIZE = 3;
    // a blaze with a pack this close is not worth going for, walking up to it is walking into them
    public static final double PACK_BLAZE_RADIUS = 10;
    // a pack this close to the spawner means the camp spot is theirs, we wait for it to leave or the watch gives the spawner up
    public static final double PACK_SPAWNER_RADIUS = 12;
    // a run from two of them is already enough to keep the spawner walk out of their closet. two still get fought when they
    // come at us, but walking back past them for a blaze was the fortress death
    public static final int ZONE_PACK_SIZE = 2;
    // a pack this close to us (3D, off the tracker, aware of us or not) has its blazes left alone: the blaze target is dropped and
    // the spot goes in a zone. fortress floors behind a wall count too, a fat radius is the point
    public static final double PACK_PLAYER_RADIUS = 12;
    // and this close it is already on top of us, so we back away first
    public static final double PACK_BACKOFF_RADIUS = 8;
    // the buddy's fork: with a pack about, two this close or one within BACKOFF_CLOSE is already on us: the hold waited for
    // three within 8 while two stood at one and six blocks, they hit from in front and behind at once and the run that
    // followed started inside their reach (attempt 215, 4 Nov 2026)
    public static final int BACKOFF_PAIR = 2;
    public static final double BACKOFF_CLOSE = 4;

    // whether the back off starts (pure): within8 = wither skeletons within PACK_BACKOFF_RADIUS, within4 = within BACKOFF_CLOSE
    public static boolean backOffNow(int within8, int within4) {
        return within8 >= BACKOFF_PAIR || within4 >= 1;
    }

    // how far the back off runs, it only has to get us out of the backoff radius with room to spare
    public static final double PACK_BACKOFF_DISTANCE = 16;
    // a blaze this close is swinging range: running from it is giving it free shots, it flies faster than we walk
    public static final double LATCH_SKIP_BLAZE_RANGE = 4.5;

    private BlazeFightRules() {
    }

    // enough wither skeletons around one spot to call it a pack
    public static boolean packNear(int witherSkeletons) {
        return witherSkeletons >= PACK_SIZE;
    }

    // the middle of a pack ({x, z}), null when the skeletons in the list are not enough to call it one
    public static double[] packCenter(java.util.List<double[]> spots) {
        return packCenter(spots, PACK_SIZE);
    }

    public static double[] packCenter(java.util.List<double[]> spots, int min) {
        if (spots.size() < min) return null;
        double x = 0;
        double z = 0;
        for (double[] spot : spots) {
            x += spot[0];
            z += spot[1];
        }
        return new double[]{x / spots.size(), z / spots.size()};
    }

    public enum Mode {
        // nothing is in reach, wait by the spawner
        CAMP,
        // hit the one blaze we picked
        KILL,
        // losing. get away from the shooters, eat, come back
        RUN
    }

    // does a blaze count as hostile for the stance logic. charged is the flag the blaze itself syncs while it winds up a
    // volley (the client draws it as the blaze being on fire), so it is the one honest "it is attacking" signal we get
    public static boolean isThreat(double distance, boolean charged, boolean lineOfSight) {
        if (distance <= MELEE_RANGE) return true;
        return charged && lineOfSight && distance <= SIGHT_RANGE;
    }

    // a blaze lined up on us from where it hovers, hit or no hit. the heal-up hold re-runs on one of these before the first
    // fireball lands and not after the second
    public static boolean watching(double distance, boolean lineOfSight) {
        return lineOfSight && distance <= SIGHT_RANGE;
    }

    // a drop is not worth a walk while a blaze is this close or we are running: the rod of the blaze we just killed lies among
    // the rest of them, and the walk to it went in with nothing swinging and no run check (the buddy's fork, 3 Nov 2026:
    // four blazes within two blocks, three melee hits in a second, 20 hp to 8 and dead). a drop at our feet still lands
    public static final double PICKUP_CLEAR_RANGE = 5;

    public static boolean holdPickup(boolean running, double nearestBlaze) {
        return running || nearestBlaze <= PICKUP_CLEAR_RANGE;
    }

    // who a run has to get away from. no line means no shot however close the number says it is, the run's close crowd has those
    public static boolean inFireRange(double distance, boolean lineOfSight) {
        return lineOfSight && distance <= FIRE_RANGE;
    }

    // a blaze or skeleton shot that landed this lately keeps a run going: an empty crowd is not a safe one while shots still land
    public static final long RUN_SHOT_TICKS = 60;

    public static boolean shotLately(long now, long shotAt) {
        return now >= shotAt && now - shotAt <= RUN_SHOT_TICKS;
    }

    // start disengaging: the rods are in, a blaze sees us, and we have not done this already for this batch of rods
    public static boolean disengageWanted(boolean rodsDone, boolean blazeSeesUs, boolean alreadyDone) {
        return rodsDone && blazeSeesUs && !alreadyDone;
    }

    // stop disengaging: nothing has been close for the calm spell, or the cap ran out and we go on anyway
    public static boolean disengageOver(long ticksSinceSeen, long ticksRunning) {
        return ticksSinceSeen >= DISENGAGE_CALM_TICKS || ticksRunning >= DISENGAGE_CAP_TICKS;
    }

    // can we walk under it and swing. over lava never (we would have to follow it in), and a blaze with no floor under it
    // is a blaze in the middle of the void (height infinite)
    public static boolean isReachable(double heightAboveFloor, boolean overLava, double distance, double range, boolean committed) {
        double ceiling = committed ? STAY_REACHABLE_HEIGHT : MAX_REACHABLE_HEIGHT;
        return !overLava && distance <= range && heightAboveFloor <= ceiling;
    }

    // keep swinging at the same one: it is alive, the tracker still has it, and it has not been out of reach for too long
    public static boolean keepTarget(boolean alive, boolean tracked, long ticksOutOfReach) {
        return alive && tracked && ticksOutOfReach < TARGET_GIVE_UP_TICKS;
    }

    // a combat run (the mob defense one) ended lately and we are not back up yet: the rods task heals first instead of
    // walking straight back at whatever we ran from. the same give up as the idle run covers it from there
    public static boolean healAfterRun(long now, long runEnd, float health) {
        return now >= runEnd && now - runEnd <= RUN_GIVE_UP_TICKS && health < RECOVERED_HEALTH;
    }

    // same, but never with a blaze already in swinging range: the first hit after the run tipped us under 18 and the latch
    // turned a kill into a run with the blaze a block away, three more hits for nothing
    public static boolean healAfterRun(long now, long runEnd, float health, double blazeDistance) {
        return blazeDistance > LATCH_SKIP_BLAZE_RANGE && healAfterRun(now, runEnd, health);
    }

    public static boolean runStalled(long ticksWithoutHealing) {
        return ticksWithoutHealing >= RUN_GIVE_UP_TICKS;
    }

    // the give up is only for getting us out of the run we are stuck in. once out it has done its job: left on, the next 10 hp
    // run skips the wait-for-16 and walks back out the moment the blaze loses sight, every time. a panic run (6 hp) stays in
    // RUN with it on, so the rods task still knows it is dead time
    public static boolean runGaveUpNext(boolean gaveUp, boolean inRun, boolean healthRose, boolean stalled) {
        if (healthRose || !inRun) return false;
        return gaveUp || stalled;
    }

    // threat is a blaze with a line of sight to us inside SIGHT_RANGE, haveTarget is the blaze we are committed to.
    // runGaveUp: the run stopped healing us (runStalled). then we are not running any more, only the fresh hp rules can send
    // us back, so 8 hp with nothing looking goes back to work instead of waiting for a 16 that never comes
    public static Mode decide(float health, boolean running, boolean runGaveUp, boolean threat, boolean haveTarget) {
        return decide(health, running, runGaveUp, threat, haveTarget, false);
    }

    // burning only holds a run we are already in: 18 hp and still on fire is not recovered, walking back in alight just
    // burns the rest off. it never starts a run on its own, and the give up still ends it
    public static Mode decide(float health, boolean running, boolean runGaveUp, boolean threat, boolean haveTarget, boolean burning) {
        boolean leave = running && !runGaveUp
                ? health < RECOVERED_HEALTH || burning
                : (health <= Tuning.get("run_health", RUN_HEALTH) && threat) || health <= PANIC_HEALTH;
        if (leave) return Mode.RUN;
        return haveTarget ? Mode.KILL : Mode.CAMP;
    }

    // what keeps a run going past the recovered line (decide's burning): fire, or the wither effect. walking back in with the
    // wither ticking means the regen we waited for is gone again before the first blaze is down
    public static boolean stillHurting(boolean burning, boolean withered) {
        return burning || withered;
    }

    // in a pocket, no more than POCKET_FIGHT_MAX blazes on us and every one of them come round within POCKET_FIGHT, above PANIC_HEALTH:
    // no run, the sword. more of them, one shooting from further off, or panic hp: the run as before (never staying put at any
    // price: in the wild three or four come round at once, or something else with them)
    public static final double POCKET_FIGHT = 5;
    public static final int POCKET_FIGHT_MAX = 2;

    // the game tick up to which the fight in a pocket wants the aura's shield (CombatBrain.auraShield): the blazes are the rods
    // task's own, so the brain had no fight and the shield never went up between swings; 30 bites took 89 hp in one run
    public static volatile long pocketShieldUntil = Long.MIN_VALUE;
    public static final double POCKET_SHIELD_RANGE = 3.5;

    public static boolean holdPocket(boolean inPocket, int onUs, double furthest, float health) {
        return inPocket && onUs <= POCKET_FIGHT_MAX && furthest <= POCKET_FIGHT && health > PANIC_HEALTH;
    }

    // the ones that hurt from where they are, with something thrown: a danger only with a line on us
    public static boolean shooter(String kind) {
        return switch (kind) {
            case "blaze", "ghast", "skeleton", "stray", "bogged", "pillager", "witch", "piglin" -> true;
            default -> false;
        };
    }

    // what one hostile adds to the danger of a crowd (BlazeFight's hiding): a blaze 1 and half again with a line on us, a wither
    // skeleton or a hoglin 1.5, a magma cube by its size (a small one a third, 4 and up a whole), anything else 1
    public static double danger(String kind, int size, boolean seesUs) {
        return switch (kind) {
            case "blaze" -> seesUs ? 1.5 : 1;
            case "wither_skeleton", "hoglin", "zoglin" -> 1.5;
            case "magma_cube", "slime" -> size >= 4 ? 1 : size >= 2 ? 0.67 : 0.34;
            default -> 1;
        };
    }

    // the camp spot gets one bounded step inland off a lava rim or ledge, same budget idea as the heal hold's step
    public static final int CAMP_STEP_TICKS = 100;

    // step while there is a cell to go to, we are not in it yet, and the budget is not spent
    public static boolean wantsCampStep(boolean haveCell, boolean arrived, int ticksUsed) {
        return haveCell && !arrived && ticksUsed < CAMP_STEP_TICKS;
    }
}
