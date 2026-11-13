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

    // how far a shooter with a line on us counts toward a hiding place (BlazeFight's crowd score). the way out of hiding
    // counts every shooter within SIGHT_RANGE, the way in counted those with a line within BlazeCover.SIGHT, 32: blazes
    // at 26 to 30 blocks put the buddy into a pocket and out of it 19 times in 180 s (route jar 6's run 6, row L32). with
    // Tuning hide_one_range (on by default, route jar 7) both ways count to SIGHT_RANGE; off, the way in counts to 32
    public static double hideRange() {
        return hideRange(Tuning.get("hide_one_range", 0) > 0);
    }

    public static double hideRange(boolean oneRange) {
        return oneRange ? SIGHT_RANGE : BlazeCover.SIGHT;
    }
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

    // Tuning fireproof_wither_one (13 Nov 2026, off): in the fire-proof camp one wither skeleton by the blaze, or within
    // PACK_PLAYER_RADIUS of the buddy, holds the chase for a blaze in pick range or the one we are after; one within a bite
    // is still taken (BlazeFight.scan)
    public static boolean oneWitherHolds(boolean on, boolean byUs, double distance, double biteRange, int withersByBlaze, int withersByUs) {
        return on && byUs && distance > biteRange && (withersByBlaze > 0 || withersByUs > 0);
    }

    // enough wither skeletons around one spot to call it a pack
    public static boolean packNear(int witherSkeletons) {
        return witherSkeletons >= PACK_SIZE;
    }

    // a run zone (a wither skeleton pack met before, RunZones) hides the blazes in it from the fight, but never one in bite's
    // reach, nor any while the fire resistance rush holds: under the potion a blaze is only its bite. rush2 B run 13 on
    // 7 Nov 2026 waited three minutes by the spawner under the potion, shield up, bitten three times, while every blaze was
    // inside the 24 round the start of a run from wither skeletons 15 blocks off
    public static boolean zoneHides(boolean inZone, boolean rush, double distance, double biteRange) {
        return inZone && !rush && distance > biteRange;
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

    // (the buddy's fork, S137: one system; Tuning risk_flee_blaze, on under risk_flee) the danger meter with the task's goal
    // decides go or flee in the blaze fight too, where it reads: this fight starts no run of its own (14 hp with a blaze
    // looking, 6 hp always). the flee is the run mob defense makes for the meter, into this fight's corner where the mob
    // field prices that calm, by the flee search elsewhere. what stays here is the way back: the heal wait after that run,
    // in the corner, the pillbox or cover, till the hp is what the next piece needs (backAt) instead of 18. the pocket, the
    // corner, the camp and the potion are what the buddy does with the meter's word, as they were
    public static boolean oneSystem() {
        return Tuning.get("risk_flee", 0) >= 1 && Tuning.get("risk_flee_blaze", 1) > 0;
    }

    // the hp the heal wait ends at under the one system: what the next piece needs by the meter's last flee
    // (DangerShadow.needHp), never more than the recovered line: past it healing gains little
    public static float backAt(double need) {
        return (float) Math.max(0, Math.min(RECOVERED_HEALTH, need));
    }

    // under the one system: no run starts here. a heal wait that is on (after mob defense's run) goes on till the hp is
    // back at `back` and nothing is still hurting, or till it gave up
    public static Mode decide(float health, float back, boolean running, boolean runGaveUp, boolean haveTarget, boolean hurting) {
        if (running && !runGaveUp && (health < back || hurting)) return Mode.RUN;
        return haveTarget ? Mode.KILL : Mode.CAMP;
    }

    // (S137, the repair after smoke6, under the one system) why not to go after a blaze now, null to go: "burning" while the burn
    // still to come would leave less than the meter's margin (as the flee level holds it), "the group the last flee ran from"
    // for a blaze still in the mob field's memory while the hp less the burn is under what the next piece needs (back)
    public static String holdBack(double health, double burn, double back, boolean remembered) {
        if (burn > 0 && health < burn + DangerMeter.MARGIN * (1 + DangerMeter.BAND)) return "burning";
        if (remembered && health - Math.max(0, burn) < back) return "the group the last flee ran from";
        return null;
    }

    // (S137, the repair after smoke7, Tuning risk_lure_back, on under the one system) the lure's walk back into its corner from
    // outside it waits while the mob field, with the group the last flee ran from still in its memory, prices the corner over
    // calm and the hp less the burn still to come is under what the next piece needs (back): the same way back as holdBack for a
    // blaze. smoke7 r1 ran clear at 12.1 hp and walked straight back into the corner; 14 of 30 runs that ended clear ran again
    // within 10 s
    public static boolean lureHold(boolean overCalm, double health, double burn, double back) {
        return overCalm && health - Math.max(0, burn) < back;
    }

    public static boolean lureBackOn() {
        return oneSystem() && Tuning.get("risk_lure_back", 1) > 0;
    }

    // (S137, the repair after smoke8, Tuning risk_camp_back, on under the one system) the camp's way back after a run for the
    // danger meter (the walk to the camp, round to the pillbox's door, the lure back into its corner) waits while the mob
    // field, the group that run fled from in its memory, prices the camp's place over calm and the hp less the burn is no more
    // than the meter's run began at: the meter fled from that place at that hp, so walking back to it is the next run.
    // lureHold never asked in smoke8, the heal wait already ends at back; 20 of 45 clear runs ran again within 10 s, r4 ten
    // runs in 106 s from a camp at 18 to 20 hp, each clear in 2 to 19 blocks and `back to camping` 3 to 7 s later. runHp -1:
    // the last run was none of the meter's. held at most CAMP_BACK_TICKS after the run's end (the memory's full span)
    public static final long CAMP_BACK_TICKS = 600;

    public static boolean campBackHold(boolean overCalm, long now, long runEnd, double health, double burn, double runHp) {
        if (!overCalm || runHp < 0 || now < runEnd || now - runEnd > CAMP_BACK_TICKS) return false;
        return health - Math.max(0, burn) <= runHp;
    }

    public static boolean campBackOn() {
        return oneSystem() && Tuning.get("risk_camp_back", 1) > 0;
    }

    // healAfterRun with the hp the wait ends at
    public static boolean healAfterRun(long now, long runEnd, float health, double blazeDistance, float back) {
        return blazeDistance > LATCH_SKIP_BLAZE_RANGE && now >= runEnd && now - runEnd <= RUN_GIVE_UP_TICKS && health < back;
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

    // ---- the bow at a blaze out of reach under fire resistance (Tuning fireproof_bow, off by default; steering 145.3, 10 Nov
    // 2026). under the potion a blaze that sees the buddy stays where it is and shoots, and the chase walks to it: above or
    // behind the brick under the spawner, over the void at a bridge's end, in the air, the walk fails, the blaze is banned two
    // minutes ('no path') and the potion runs out with no swing (phase 4, 1 to 8 idle minutes in 32 deaths and stalls). such a
    // blaze is shot with the kit's bow from where the buddy stands, while BOW_FR_TICKS or more of the effect are left
    public static final int BOW_FR_TICKS = 200;
    public static final double BOW_RANGE = 20;
    public static final double BOW_FLOOR = 8;
    // the swing reach plus this before the bow takes over, the sword within the reach itself, the choice kept between
    public static final double BOW_SLACK = 0.5;

    public static boolean fireproofBowOn() {
        return Tuning.get("fireproof_bow", 0) > 0;
    }

    // whether a blaze is one to shoot: the switch, the effect's ticks left, a bow and arrows in the bag, the gap beyond the swing
    // reach and the slack, a clear line, a floor under it within BOW_FLOOR that is not lava (the rod lands where it can be
    // picked up), within BOW_RANGE, no zombified piglin on the arrow's line. a no-path ban is no input: the bow is for those
    public static boolean bowTakes(boolean on, int fireResTicks, boolean bowAndArrows, double gap, double swingReach, boolean line,
                                   double floorBelow, boolean overLava, double distance, boolean neutralOnLine) {
        return on && fireResTicks >= BOW_FR_TICKS && bowAndArrows && gap > swingReach + BOW_SLACK && line
                && floorBelow <= BOW_FLOOR && !overLava && distance <= BOW_RANGE && !neutralOnLine;
    }

    // the weapon for a blaze already a target: true the bow, false the sword; beyond the reach and the slack the bow, within the
    // reach the sword, between them the one in hand stays
    public static boolean bowKept(boolean bowNow, double gap, double swingReach) {
        if (gap > swingReach + BOW_SLACK) return true;
        if (gap <= swingReach) return false;
        return bowNow;
    }
}
