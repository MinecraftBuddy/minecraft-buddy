package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.entity.KillEntityTask;
import adris.altoclef.tasks.movement.GetToBlockTask;
import adris.altoclef.tasks.movement.RunAwayFromHostilesTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.BlazeCover;
import adris.altoclef.util.helpers.BlazeFightRules;
import adris.altoclef.util.helpers.BlazeFightRules.Mode;
import adris.altoclef.util.helpers.EntityHelper;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.helpers.RecoverHold;
import adris.altoclef.util.helpers.RunZones;
import baritone.pathing.movement.CliffAvoidance;
import baritone.utils.BlockStateInterface;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.monster.Blaze;
import net.minecraft.world.entity.monster.skeleton.WitherSkeleton;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

// the per tick brain of the rods task while there are blazes about. it only decides, BlazeFightRules has the numbers.
// one blaze at a time: pick the nearest one we can reach, hit it until it is dead, then pick the next. null from tick() means
// "nothing to do right now" (camping, or running with nobody in sight)
final class BlazeFight {

    // past FIRE_RANGE on purpose, so a blaze with a line on us never counts as far enough. who is in the crowd is what ends a run
    static final double RUN_DISTANCE = 50;
    // same reach the plain run's crowd uses for "who is after us"
    private static final double RUN_CROWD_RADIUS = 20;

    private Mode mode = Mode.CAMP;
    private Mode loggedMode = Mode.CAMP;
    private boolean running;
    // the combat run end we already took a heal wait from, so one run arms it once
    private long armedFromRun = Long.MIN_VALUE;
    // the run stall watch: when the idle run started (or last gained hp), what hp was last tick, and whether we gave up
    // waiting. MIN_VALUE is not watching
    private long runIdleSince = Long.MIN_VALUE;
    private float lastHealth = Float.NaN;
    private boolean runGaveUp;
    // the blaze or skeleton whose projectile hit us last, and when. a run with that thrower out of the crowd lists used to stop and
    // stand still in its line of fire
    private DamageSource shotSeen;
    private Mob shooter;
    private long shotAt = Long.MIN_VALUE / 2;
    // blazes with a line on us, and blazes we could walk under and hit, as of the last scan
    private final List<Blaze> threats = new ArrayList<>();
    private final List<Blaze> candidates = new ArrayList<>();
    private int around;
    private double nearest = Double.POSITIVE_INFINITY;
    // the one blaze we are after. it stays ours until it dies, goes away or sits out of reach for a while, so two similar
    // blazes can never have us changing our mind
    private Blaze target;
    private boolean targetTracked;
    private boolean targetInReach;
    // the target sits by a wither skeleton pack as of the last scan, so it is dropped
    private boolean packDrop;
    // a pack within PACK_PLAYER_RADIUS of us (packSize of them, around packCenter) and whether it is also inside the back off radius
    private boolean packHere;
    private boolean packClose;
    private int packSize;
    private double[] packCenter;
    private boolean packLogged;
    private long outOfReachSince = Long.MIN_VALUE;
    private double range = BlazeFightRules.PICK_RANGE;
    // read off the pathfinder thread. fighting and running never place blocks at our feet or above: pillaring up at a hovering
    // blaze is how we ended up building next to one
    private volatile boolean noPlacing;
    // our feet y as of the last tick, for the height rule in noPlacing(pos)
    private volatile int floorY = Integer.MIN_VALUE;

    // ---- the corner camp (BlazeCover.corner, BlazeLureTask): with a corner, a blaze is only gone after once it has come round the
    // corner: it sees the corner cell, or we see its body, within CORNER_REACH of it; or it is biting us. at any crowd: with the
    // plain fight below three blazes, the walk to the corner went after one by the spawner, and the spawner's next four killed
    // the buddy there in 54 game seconds (corner14, 4 Nov 2026); the looks and the trips out bring them
    static final double CORNER_REACH = 6;
    // the blaze we are after is let go this far from the corner: after hiding the plain fight chased one 23 blocks into the
    // room (corner11): out with a purpose and on a short leash, not out into the room (4 Nov 2026)
    static final double CORNER_LEASH = 8;
    // a blaze this close bites, so it has a line on us whatever our ray from the eyes says
    static final double BITE_RANGE = 2.5;
    // the buddy's own leash: with a corner, a blaze is gone after only while we stand within BUDDY_LEASH of it, else back to the
    // corner (one that bites is fought anywhere). the leash on the blaze alone let the fight out of the door onto the platform,
    // in every blaze's sight (corner15, runs 2 and 4: 0 of 4); and a run with a corner within CORNER_RUN is into the corner, not
    // the plain run, which dug out through the bend's wall and bridged over the lava (run 1)
    // (1.5 since steering 18, back to corner14's stance: only what comes within reach from the corner is fought; 3.5 in corner16
    // to corner20)
    static final double BUDDY_LEASH = 1.5;
    static final double CORNER_RUN = 12;
    // the run into the corner: round by a standing pillbox's door, and the plain run or cover once it had BoxWay.RUN_TICKS
    private final adris.altoclef.util.helpers.BoxWay cornerWay = new adris.altoclef.util.helpers.BoxWay();
    private BlockPos cornerRunTo;
    private BlockPos corner;
    private boolean cornerHold;

    // ---- hiding from a crowd (4 Nov 2026: more foes than can be killed at once are fled from to a choke point where they
    // come one by one, as one against ten never works; and not the blazes alone: a swarm of small magma cubes with them was
    // too much as well): at HIDE_SCORE of danger about (crowdScore), at any
    // hp, the camp's corner, else the nearest pocket no blaze sees (BlazeCover.pick with BlazeCover.pocket) becomes a corner of its
    // own, held as the camp's is, until the danger is down to HIDE_DONE. what walks after us has to come through its way in
    static final double HIDE_SCORE = 4;
    // out again at HIDE_DONE for HIDE_DONE_TICKS, or at HIDE_LONG_DONE after HIDE_LONG_TICKS in hiding: out at 2 the step
    // out was seen by two and in again within the second, over and over, and each time the looks began anew (4 Nov 2026)
    static final double HIDE_DONE = 1;
    static final long HIDE_DONE_TICKS = 40;
    static final double HIDE_LONG_DONE = 2;
    static final long HIDE_LONG_TICKS = 600;
    private long hideSince;
    private long calmSince = Long.MIN_VALUE;
    static final double CROWD_RANGE = 16;
    // the walk into hiding may be this long (the bent corridor was 16 to 24 steps from the platform, and with 12 the crowd's
    // hiding never once found it: forty "no closed in cover" in a block, 0 passes in 8)
    static final int HIDE_WALK = 24;
    static final long HIDE_RETRY_TICKS = 40;
    private BlockPos hide;
    private boolean hideArrived;
    private boolean pocketHeld;
    private long pocketLoggedAt = Long.MIN_VALUE / 2;

    // a block of the pocket's walls (within 3 of the hiding place or the camp's corner): not to be broken. read off the
    // pathfinder's thread, so the volatile copy
    private volatile BlockPos wallOf;

    // (within POCKET_GUARD of the corner: at 3 the walk to the corner went round the outside to dig in through the corridor's back
    // wall, 4 from the corner, which would have opened the choke point from behind; corner17 run 2)
    static final int POCKET_GUARD = 5;

    boolean pocketWall(BlockPos pos) {
        BlockPos c = wallOf;
        return c != null && Math.abs(pos.getX() - c.getX()) <= POCKET_GUARD && Math.abs(pos.getZ() - c.getZ()) <= POCKET_GUARD
                && pos.getY() >= c.getY() - 1 && pos.getY() <= c.getY() + 3;
    }

    // with a corner: no block placed over the lava sea, at any height (air under it down to lava). the walk to the corner
    // bridged round the platform's outside and fell off its bridge (corner17 run 2), the run bridged over the void and fell off
    // at an ascend (corner15 run 1); a bridge there is a fireball's knockback from the lava. read off the pathfinder's thread
    private volatile boolean seaBan;

    static boolean overLava(BlockPos pos) {
        var level = net.minecraft.client.Minecraft.getInstance().level;
        if (level == null) return false;
        for (int d = 1; d <= 64; d++) {
            BlockPos p = pos.below(d);
            BlockState state = level.getBlockState(p);
            if (state.is(Blocks.LAVA)) return true;
            if (!state.getCollisionShape(level, p).isEmpty()) return false;
        }
        return false;
    }

    // standing in the hiding place or the camp's corner
    private boolean inPocket(LocalPlayer player) {
        BlockPos c = activeCorner();
        return c != null && BlazeLureTask.in(player.position(), c);
    }

    // the furthest blaze with a line on us (as of this tick's scan); 0 with none
    private double nearestThreat(LocalPlayer player) {
        double far = 0;
        for (Blaze b : threats) far = Math.max(far, player.distanceTo(b));
        return far;
    }
    private BlazeLureTask hideLure;
    private GetToBlockTask hideWalk;
    private long hideRetryAt = Long.MIN_VALUE;
    private int lastSeen;
    // the blazes with a line on us as of the last scan (the danger is worked out before this scan's sight)
    private List<Blaze> seenLast = List.of();

    // the corner the fight holds to now: the crowd's hiding place, else the camp's
    private BlockPos activeCorner() {
        return hide != null ? hide : corner;
    }

    // the walk into hiding or the looks from it, null when not hiding (or when the fight has a blaze to hit or a run)
    Task hideTask(AltoClef mod) {
        if (hide == null || mode != Mode.CAMP) return null;
        if (BlazeLureTask.in(mod.getPlayer().position(), hide)) hideArrived = true;
        // (once there the looks have the wheel and walk back to it themselves: a walk back to the cell on every step out undid
        // each look, 1446 steps out and 1441 walks back in one run and not one look, 4 Nov 2026)
        if (!hideArrived || hideLure == null) {
            if (hideWalk == null) hideWalk = new GetToBlockTask(hide);
            return hideWalk;
        }
        return hideLure;
    }

    // how much is about to hurt us: a shooter (BlazeFightRules.shooter) only with a line from its eyes to one of the points (our
    // eyes), or with points null every shooter within SIGHT_RANGE (in hiding: out there each one sees us), the rest within
    // CROWD_RANGE by distance, as they walk
    // up anyway; each by BlazeFightRules.danger. (4 Nov 2026: only a blaze with a line of sight is a danger, and the same
    // holds for every mob that attacks with projectiles)
    private double crowdScore(AltoClef mod, LocalPlayer player, List<Vec3> points) {
        double score = 0;
        java.util.Set<Entity> seen = new java.util.HashSet<>();
        try {
            List<Entity> all = new ArrayList<>(mod.getEntityTracker().getHostiles());
            for (Blaze b : mod.getEntityTracker().getTrackedEntities(Blaze.class)) if (!all.contains(b)) all.add(b);
            for (Entity e : all) {
                if (!e.isAlive() || !seen.add(e)) continue;
                String kind = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath();
                int size = e instanceof net.minecraft.world.entity.monster.cubemob.MagmaCube cube ? cube.getSize() : 0;
                if (BlazeFightRules.shooter(kind) && points == null) {
                    if (player.distanceTo(e) <= BlazeFightRules.SIGHT_RANGE) score += BlazeFightRules.danger(kind, size, true);
                } else if (BlazeFightRules.shooter(kind)) {
                    if (player.distanceTo(e) > BlazeCover.SIGHT) continue;
                    boolean line = false;
                    for (Vec3 pt : points) {
                        if (pt.distanceTo(e.getEyePosition()) <= BlazeCover.SIGHT && adris.altoclef.util.helpers.GhastCover.clear(mod.getWorld(), e.getEyePosition(), pt, player)) {
                            line = true;
                            break;
                        }
                    }
                    if (line) score += BlazeFightRules.danger(kind, size, true);
                } else if (player.distanceTo(e) <= CROWD_RANGE) {
                    score += BlazeFightRules.danger(kind, size, false);
                }
            }
        } catch (java.util.ConcurrentModificationException ignored) {
            // the tracker rebuilds on another thread sometimes
        }
        return score;
    }

    // a pillbox is the plan (the rods task): no hiding place of the crowd's then. one picked before the box's first wall, 14
    // blocks off down the corridor, took the corner's place (activeCorner), was beyond the corner run's reach, and every run was
    // the plain one, over the bridge: bent-b, three of three dead on it with the box built whole
    private boolean pillboxPlanned;

    // the buddy's leash, from the corner: with a pillbox the whole box is in it, its look out two steps from the cell (at 1.5,
    // every blaze was let go the moment the buddy stood in the look out, "we are 1.7 from the corner", bent-d run 1)
    static final double PILLBOX_LEASH = 2.5;
    // (Tuning pillbox_reach: at 3.5 the box survived twelve game minutes but the blazes hung at four to eight, out of reach,
    // realfort-a-bent-i runs 1 and 2, stalls. the default is what the rod drills' yardstick plays)
    static final double PILLBOX_REACH = 4.5;

    private double leash() {
        double l = adris.altoclef.util.helpers.Tuning.get("buddy_leash", BUDDY_LEASH);
        return pillboxPlanned ? Math.max(l, PILLBOX_LEASH) : l;
    }

    // the pillbox's look out, set with the plan (the rods task)
    private BlockPos pillboxLookout;

    void pillboxLookout(BlockPos o) {
        pillboxLookout = o;
    }

    void pillboxPlanned(boolean b) {
        pillboxPlanned = b;
        if (b) {
            hide = null;
            hideLure = null;
            hideWalk = null;
        }
    }

    // with a crowd about, a hiding place; with the crowd gone, none
    // (the buddy's fork, steering 37 and the 15:05 evaluation of 5 Nov 2026: with fire resistance and Tuning fireproof_camp the
    // camp is the spawner itself, with no corner, no pillbox and no hiding from a crowd: the fireballs do nothing then, and
    // with the box the ceiling's runs stalled locked out of the cell or sealed in it, 6 and 1 of 7 in realfort-ab-ceiling-2)
    // the reach under fire resistance with fireproof_camp on (0: the usual one)
    static double fireproofHold(net.minecraft.world.entity.LivingEntity player) {
        return fireproofCamp(player) ? adris.altoclef.util.helpers.Tuning.get("fireproof_hold", 0) : 0;
    }

    // (the buddy's fork, Tuning fireproof_reach, blocks: under fire resistance with fireproof_camp only a blaze whose feet are at
    // most that far above the buddy's: the nearest blaze over a ledge or the spawner's roof was chased, "no path", banned two
    // minutes, as a fireball under the potion never lifts the ban, and the next one the same: 55 bans on 24 blazes and not one
    // swing in eight minutes, piece2in-1 run 3. with it on, a rod is fetched with no blaze within bite's reach. 0: off)
    static double fireproofReach(net.minecraft.world.entity.LivingEntity player) {
        return fireproofCamp(player) ? adris.altoclef.util.helpers.Tuning.get("fireproof_reach", 0) : 0;
    }

    static boolean fireproofCamp(net.minecraft.world.entity.LivingEntity player) {
        return player != null && adris.altoclef.util.helpers.Tuning.get("fireproof_camp", 0) > 0
                && adris.altoclef.util.helpers.FireRes.has(player);
    }

    private void noteCrowd(AltoClef mod, LocalPlayer player, List<Blaze> blazes, long now) {
        if (pillboxPlanned || fireproofCamp(player)) return;
        // (in hiding, every one about counts, seen or not: whoever is out there sees us once we are. by the look outs' sight it was
        // 1.5 and out, and three blazes on us a second later, the look outs see the room through a door one wide)
        double score = hide != null ? crowdScore(mod, player, null) : crowdScore(mod, player, List.of(player.getEyePosition()));
        String near = String.format(java.util.Locale.ROOT, "danger %.1f", score);
        if (hide != null) {
            if (score > HIDE_DONE) calmSince = Long.MIN_VALUE;
            else if (calmSince == Long.MIN_VALUE) calmSince = now;
            boolean calm = calmSince != Long.MIN_VALUE && now - calmSince >= HIDE_DONE_TICKS;
            if (calm || (score <= HIDE_LONG_DONE && now - hideSince >= HIDE_LONG_TICKS)) {
                calmSince = Long.MIN_VALUE;
                Debug.logInternal("rods: " + near + ", out of hiding at " + hide.toShortString());
                hide = null;
                hideLure = null;
                hideWalk = null;
            }
            return;
        }
        if (now < hideRetryAt || score < HIDE_SCORE) return;
        hideRetryAt = now + HIDE_RETRY_TICKS;
        if (mod.getClientBaritone().getPlayerContext() == null) return;
        CliffAvoidance.Cells cells = CliffAvoidance.of(new BlockStateInterface(mod.getClientBaritone().getPlayerContext(), true));
        BlockPos at = player.blockPosition();
        int[] c;
        String how;
        if (corner != null) {
            // (the camp's own corner first: a pocket by the spawner, found when the camp was picked)
            c = new int[]{corner.getX(), corner.getY(), corner.getZ()};
            how = "the camp's corner";
        } else {
            List<Vec3> eyes = blazeEyes(mod, player);
            c = BlazeCover.pick(cells, at.getX(), at.getY(), at.getZ(), witherSkeletonSpots(mod, player.position(), HIDE_WALK + BlazeCover.WITHER_CLEAR),
                    new java.util.HashSet<>(), (x, y, z) -> BlazeCover.hidden(mod.getWorld(), eyes, x, y, z, player),
                    (x, y, z) -> BlazeCover.pocket(mod.getWorld(), x, y, z, player), HIDE_WALK);
            how = "a pocket";
        }
        if (c == null) {
            Debug.logInternal("rods: " + near + " (" + lastSeen + " blazes on us), no pocket within " + HIDE_WALK + " steps, fighting on");
            return;
        }
        int enclosed = BlazeCover.enclosure(mod.getWorld(), c[0], c[1], c[2], player);
        hide = new BlockPos(c[0], c[1], c[2]);
        adris.altoclef.util.snapshot.Snapshots.chose(corner != null ? "blaze.corner.hide" : "blaze.hide", c);
        hideArrived = false;
        hideSince = now;
        calmSince = Long.MIN_VALUE;
        hideLure = BlazeLureTask.of(cells, hide, null);
        hideWalk = null;
        // (the blaze we were after stays ours only in the sword's reach: walking on at it kept the fight in the open)
        if (target != null && player.distanceTo(target) > 3) target = null;
        Debug.logInternal("rods: " + near + " (" + lastSeen + " blazes on us), into hiding at " + hide.toShortString() + ", " + how
                + " (closed in " + enclosed + " of 24), " + Math.round(Math.sqrt(at.distSqr(hide))) + " blocks");
    }

    void corner(BlockPos c) {
        corner = c;
    }

    // a pillbox going up (BlazePillboxTask): only a blaze that bites is fought meanwhile. every blaze in reach broke into its
    // placing, so each block ran out of its time and five of six were left out (corner23b, the straight room)
    private boolean building;
    // the trip out of a standing pillbox (steering 35): since when we are out, and whether its limit is reached
    private long tripSince = Long.MIN_VALUE;
    private boolean tripOver;
    static final double BOX_IN = 1.6;
    static final double TRIP_HP = 12;

    boolean tripOver() {
        return tripOver;
    }

    void building(boolean b) {
        building = b;
    }

    // a fire between us and the corner is put out first (FireOnTheWay)
    private final adris.altoclef.util.helpers.FireOnTheWay fireWay = new adris.altoclef.util.helpers.FireOnTheWay();

    Task fireOnTheWay(AltoClef mod, BlockPos to) {
        return fireWay.step(mod, to);
    }

    // after the corner's leash let a blaze go: only one within BITE_RANGE is taken this long (ticks)
    static final long LEASH_HOLD_TICKS = 40;
    private long leashHoldUntil = Long.MIN_VALUE;

    // one line a second at most for a blaze let go, and why (the fight flipped between kill and camp each tick in corner17 run 1,
    // and nothing said which rule did it)
    private long letGoLoggedAt = Long.MIN_VALUE / 2;

    private void noteLetGo(String why, LocalPlayer player) {
        long now = player.level().getGameTime();
        if (now - letGoLoggedAt < 20) return;
        letGoLoggedAt = now;
        Debug.logInternal("rods: let the blaze go, " + why + (target == null ? "" : String.format(java.util.Locale.ROOT, " (it was %.1f off)", player.distanceTo(target))));
    }

    BlockPos corner() {
        return corner;
    }

    // the corner's rule holds this tick (set by scan): there is one and a crowd is about
    boolean cornerHold() {
        return cornerHold;
    }

    // the rods are in: only blazes close by still matter
    void disengageOnly() {
        range = BlazeFightRules.DISENGAGE_RANGE;
    }

    // fighting and running are the only places that place nothing, anywhere else (the walk to the spawner) may bridge
    void idle() {
        noPlacing = false;
    }

    Mode mode() {
        return mode;
    }

    boolean noPlacing() {
        return noPlacing;
    }

    // the ban by height: below our feet is bridge and traverse territory and stays open, so a bridge we were halfway across
    // still has a way off. feet level and up (pillar, ascend) is what builds us next to a blaze
    boolean noPlacing(BlockPos pos) {
        // (a pillbox's own blocks are never banned: the high walls and the roof are at our feet's level and up, and in a fight
        // mode they were refused, "would not go in" for every one of them, corner23c)
        if (pillboxBlocks.contains(pos)) return false;
        return placingBanned(noPlacing, pos.getY(), floorY) || (seaBan && overLava(pos));
    }

    // the blocks of a pillbox under way, read off the pathfinder's thread
    private volatile java.util.Set<BlockPos> pillboxBlocks = java.util.Set.of();

    void pillboxBlocks(java.util.Set<BlockPos> blocks) {
        pillboxBlocks = blocks;
    }

    static boolean placingBanned(boolean noPlacing, int placeY, int floorY) {
        return noPlacing && placeY >= floorY;
    }

    // the nearest live blaze as of the last scan, any height, any line of sight: for the pickup hold
    double nearestBlaze() {
        return nearest;
    }

    // same floor rule as baritone's playerFeet (y + 0.1251). getBlockY drops a block the moment we sink a hair under the top
    // (slabs, the first tick of a fall) and then the bridge cell the path counted on reads as feet level and gets refused
    static int floorY(double y) {
        return Mth.floor(y + 0.1251);
    }

    // blazes we could hit right now, and every live blaze in range (that one is just for the hud)
    int reachable() {
        return candidates.size();
    }

    int around() {
        return around;
    }

    boolean runGaveUp() {
        return runGaveUp;
    }

    Task tick(AltoClef mod) {
        LocalPlayer player = mod.getPlayer();
        long now = mod.getWorld().getGameTime();
        scan(mod, player, now);
        noteShot(player, now);
        updateTarget(player, now);
        float health = player.getHealth();
        // hp going up is what the run waits for, so any rise means it is working again
        if (health > lastHealth) runGaveUp = false;
        // a run mob defense made also leaves us hurt: wait it out here like our own run, it is the same latch
        if (!running && mod.getMobDefenseChain() != null) {
            long runEnd = mod.getMobDefenseChain().lastCombatRunEnd();
            double blazeDistance = target == null ? Double.POSITIVE_INFINITY : player.distanceTo(target);
            if (runEnd != armedFromRun && BlazeFightRules.healAfterRun(now, runEnd, health, blazeDistance)) {
                running = true;
                armedFromRun = runEnd;
            }
        }
        boolean burning = player.isOnFire() && !adris.altoclef.util.helpers.FireRes.has(player);
        boolean withered = player.hasEffect(MobEffects.WITHER);
        Mode next = BlazeFightRules.decide(health, running, runGaveUp, !threats.isEmpty(), target != null,
                BlazeFightRules.stillHurting(burning, withered));
        // in the pocket with only blazes that came round on us: fight them there. the run found no cell they do not see (they
        // are in the pocket), and the old run dug out through its wall: the choke point gone, with a blaze in it the sword
        // could easily have killed (4 Nov 2026)
        if (next == Mode.RUN && BlazeFightRules.holdPocket(inPocket(player), threats.size(), nearestThreat(player), health)) {
            next = target != null ? Mode.KILL : Mode.CAMP;
            if (!pocketHeld && now - pocketLoggedAt > 100) Debug.logInternal("rods: in the pocket at " + String.format(java.util.Locale.ROOT, "%.1f", health)
                    + " hp, the blazes on us came round (nearest " + String.format(java.util.Locale.ROOT, "%.1f", nearestThreat(player)) + "): fighting, no run");
            if (!pocketHeld) pocketLoggedAt = now;
            pocketHeld = true;
        } else {
            pocketHeld = false;
        }
        BlockPos held = cornerHold ? activeCorner() : null;
        if (next == Mode.KILL && held != null && target != null && player.distanceTo(target) > BITE_RANGE
                && player.position().distanceTo(Vec3.atBottomCenterOf(held)) > leash()) {
            noteLetGo(String.format(java.util.Locale.ROOT, "we are %.1f from the corner", player.position().distanceTo(Vec3.atBottomCenterOf(held))), player);
            target = null;
            next = Mode.CAMP;
            // (the buddy's fork: and no new one past the bite's reach for a while, so the camp walks back inside the leash.
            // taken again the next tick, the same blaze was let go 126 to 555 times in three minutes, the buddy standing
            // outside the leash all the while, rod-1 run 8 and piece2hold-3 run 10, the live watch's hits of 6 Nov 2026)
            leashHoldUntil = now + LEASH_HOLD_TICKS;
        }
        running = next == Mode.RUN;
        mode = next;
        // (in the pocket with a blaze in the sword's reach: the aura's shield between swings, BlazeFightRules.pocketShieldUntil)
        // (only while hitting one: the shield up slowed the looks' step out so much that 9260 ticks of stepping out made 8 looks)
        if (mode == Mode.KILL && inPocket(player) && nearest <= BlazeFightRules.POCKET_SHIELD_RANGE) BlazeFightRules.pocketShieldUntil = now + 10;
        // a new run looks for its own cover
        if (mode != Mode.RUN) {
            cover = null;
            standPick.reset();
            coverBanned.clear();
            coverRetryAt = Long.MIN_VALUE;
            cornerWay.reset();
            cornerRunTo = null;
        }
        noPlacing = mode != Mode.CAMP;
        seaBan = cornerHold;
        // (the corner for the mob defense's run too)
        if (held != null) adris.altoclef.util.helpers.BlazeShelter.set(held, now);
        // (the way back into hiding measured, BlazeShelter.tickHide: hitting is no way back)
        if (mode == Mode.KILL) adris.altoclef.util.helpers.BlazeShelter.killAt = now;
        floorY = floorY(player.getY());
        watchRun(now, health);
        lastHealth = health;
        logChange(mod, health);
        notePack(mod, now);
        return switch (mode) {
            // (a pack on top of us gets a short run. further off they are behind a wall or not coming, the walk goes on and the zone routes round them)
            case CAMP -> packClose ? new RunAwayFromHostilesTask(BlazeFightRules.PACK_BACKOFF_DISTANCE, true, () -> runCrowd(mod)) : null;
            case KILL -> new KillEntityTask(target);
            // never stand still in a run: with no blaze on us the wither skeletons that kept chasing still count, only an empty
            // crowd (nobody at all) is sitting tight until the food chain and regen are done
            // (a shot that landed lately keeps the thrower in that crowd, see runCrowd, and a blaze with a line on us from far out
            // is in it too, see underFireCrowd. out of their sight is where a run gets to stop)
            case RUN -> {
                boolean nearCorner = held != null && player.position().distanceTo(Vec3.atBottomCenterOf(held)) <= adris.altoclef.util.helpers.Tuning.get("corner_run", CORNER_RUN);
                // (a pillbox going up: anywhere in the box is in, its roof row is placed from the front cell, and the rods
                // task goes on building through the heal wait)
                if (nearCorner && building && pillboxPlanned && adris.altoclef.util.helpers.Tuning.get("pillbox_heal_build", 1) > 0
                        && player.position().distanceTo(Vec3.atBottomCenterOf(held)) <= PILLBOX_LEASH) {
                    cover = null;
                    yield null;
                }
                // (the buddy's fork, stand_model on: the corner, a cover or the plain run is the model's pick, the corner one cell
                // among the rest and every mob about weighed, so no coverFits; with no cell from it the rules below)
                BlockPos stand = adris.altoclef.util.helpers.StandPick.on() ? standCell(mod, nearCorner ? held : null) : null;
                boolean intoCorner = stand != null ? stand.equals(held) : nearCorner;
                // (with a corner near, the run is into it: standing in it is the heal wait, null)
                if (intoCorner) {
                    if (BlazeLureTask.in(player.position(), held)) {
                        cover = null;
                        cornerWay.reset();
                        // (a pillbox's cell: the heal wait at its middle, see BlazePillboxTask.centreIn)
                        if (pillboxPlanned) BlazePillboxTask.hideIn(mod, held);
                        yield null;
                    }
                    // (null: the corner not reached in BoxWay.RUN_TICKS, the run goes on below as with no corner near)
                    Task way = cornerWay.step(mod, held);
                    if (way != null) {
                        cover = null;
                        Task fire = fireOnTheWay(mod, held);
                        if (fire != null) yield fire;
                        if (!held.equals(cornerRunTo)) {
                            cornerRunTo = held;
                            Debug.logInternal("rods: running into the corner at " + held.toShortString() + " at "
                                    + String.format(java.util.Locale.ROOT, "%.1f", health) + " hp");
                        }
                        yield way;
                    }
                    stand = null;
                }
                cornerRunTo = null;
                if (stand != null) {
                    // (the model's cell: the walk there, standing in it the heal wait)
                    cover = null;
                    yield standPick.walk(mod);
                }
                List<Entity> crowd = underFireCrowd(mod);
                if (crowd.isEmpty()) {
                    cover = null;
                    yield null;
                }
                // (the buddy's fork: out of the blazes' sight before any run, see BlazeCover. null from cover() with a cover
                // cell is standing in it, which the rods task reads as the heal wait)
                if (coverFits(player, crowd)) {
                    Task hide = cover(mod, now);
                    if (cover != null) yield hide;
                } else {
                    cover = null;
                }
                yield new RunAwayFromHostilesTask(RUN_DISTANCE, true, () -> underFireCrowd(mod));
            }
        };
    }

    // ---- the model of where to stand (StandModel, tuning stand_model): the run's cell from one cost over every mob about

    // how far round us the run's cell is looked for (BlazeCover's walk was 12 steps; the model takes 0.5 ms at 8)
    static final int STAND_RADIUS = 8;
    // what hitting back counts in a run: the run is for healing, but a blaze in reach from a hidden cell is a rod
    static final double STAND_HIT_BACK = 0.5;
    private final adris.altoclef.util.helpers.StandPick standPick = new adris.altoclef.util.helpers.StandPick("rods run");

    private BlockPos standCell(AltoClef mod, BlockPos corner) {
        adris.altoclef.util.helpers.StandModel.Query q = adris.altoclef.util.helpers.StandModel.Query.of().radius(STAND_RADIUS)
                .hitBack(STAND_HIT_BACK).maxRate(adris.altoclef.util.helpers.StandModel.RUN_RATE);
        return standPick.cell(mod, q, corner == null ? List.of() : List.of(corner), target);
    }

    // ---- cover from the blazes (the buddy's fork, 4 Nov 2026)

    // a walk to cover that has not got there in this long is not going to: that cell is banned for this run and the next is picked
    private static final long COVER_WALK_TICKS = 160;
    // the cover is looked at again this often: a blaze that drifted round sees it now, and the next cell is picked
    private static final long COVER_CHECK_TICKS = 10;
    // no cell found: the run as before, and a new look after this long
    private static final long COVER_RETRY_TICKS = 40;
    // something other than a blaze this close is after us: cover from the blazes is no cover from it, so the plain run
    private static final double COVER_CHASER_RANGE = 10;

    private BlockPos cover;
    private long coverSince;
    private long coverCheckAt;
    private long coverRetryAt = Long.MIN_VALUE;
    private boolean coverArrived;
    private final java.util.Set<Long> coverBanned = new java.util.HashSet<>();
    private GetToBlockTask coverWalk;

    // only blazes on us: a wither skeleton or anything else close follows us into any cover
    private static boolean coverFits(LocalPlayer player, List<Entity> crowd) {
        for (Entity e : crowd) {
            if (!(e instanceof Blaze) && player.distanceTo(e) <= COVER_CHASER_RANGE) return false;
        }
        return true;
    }

    // the walk to cover, null once in it (or with no cover: then cover is null too and the caller runs)
    private Task cover(AltoClef mod, long now) {
        LocalPlayer player = mod.getPlayer();
        if (now < coverSince) {
            cover = null;
            coverBanned.clear();
        }
        if (cover != null && now >= coverCheckAt) {
            coverCheckAt = now + COVER_CHECK_TICKS;
            boolean arrived = player.blockPosition().equals(cover);
            if (!arrived && now - coverSince > COVER_WALK_TICKS) {
                coverBanned.add(BlazeCover.key(cover.getX(), cover.getY(), cover.getZ()));
                Debug.logInternal("rods: cover at " + cover.toShortString() + " not reached in " + COVER_WALK_TICKS + " ticks, the next");
                cover = null;
            } else if (!BlazeCover.hidden(mod.getWorld(), blazeEyes(mod, player), cover.getX(), cover.getY(), cover.getZ(), player)) {
                Debug.logInternal("rods: a blaze sees the cover at " + cover.toShortString() + ", the next");
                cover = null;
            }
        }
        if (cover == null) {
            if (now < coverRetryAt) return null;
            if (mod.getClientBaritone().getPlayerContext() == null) return null;
            CliffAvoidance.Cells cells = CliffAvoidance.of(new BlockStateInterface(mod.getClientBaritone().getPlayerContext(), true));
            List<Vec3> eyes = blazeEyes(mod, player);
            BlockPos at = player.blockPosition();
            int[] c = BlazeCover.pick(cells, at.getX(), at.getY(), at.getZ(), witherSkeletonSpots(mod, player.position(), BlazeCover.RADIUS + BlazeCover.WITHER_CLEAR),
                    coverBanned, (x, y, z) -> BlazeCover.hidden(mod.getWorld(), eyes, x, y, z, player),
                    (x, y, z) -> BlazeCover.enclosure(mod.getWorld(), x, y, z, player));
            if (c == null) {
                coverRetryAt = now + COVER_RETRY_TICKS;
                Debug.logInternal("rods: no cover from " + eyes.size() + " blazes within " + BlazeCover.RADIUS + ", running");
                return null;
            }
            cover = new BlockPos(c[0], c[1], c[2]);
            adris.altoclef.util.snapshot.Snapshots.chose("blaze.cover", c);
            coverSince = now;
            coverCheckAt = now + COVER_CHECK_TICKS;
            coverArrived = false;
            coverWalk = new GetToBlockTask(cover);
            Debug.logInternal("rods: into cover at " + cover.toShortString() + " (closed in " + BlazeCover.enclosure(mod.getWorld(), c[0], c[1], c[2], player)
                    + " of 24), " + Math.round(Math.sqrt(player.blockPosition().distSqr(cover)))
                    + " blocks, out of the sight of " + eyes.size() + " blazes, at " + String.format(java.util.Locale.ROOT, "%.1f", player.getHealth()) + " hp");
        }
        if (player.blockPosition().equals(cover)) {
            if (!coverArrived) {
                coverArrived = true;
                Debug.logInternal("rods: in cover at " + cover.toShortString() + " after " + (now - coverSince) + " ticks, "
                        + String.format(java.util.Locale.ROOT, "%.1f", player.getHealth()) + " hp");
            }
            return null;
        }
        return coverWalk;
    }

    // the eyes of the live blazes within the cover's sight
    private static List<Vec3> blazeEyes(AltoClef mod, LocalPlayer player) {
        List<Vec3> eyes = new ArrayList<>();
        try {
            for (Blaze blaze : mod.getEntityTracker().getTrackedEntities(Blaze.class)) {
                if (blaze.isAlive() && player.distanceTo(blaze) <= BlazeCover.SIGHT + BlazeCover.RADIUS) eyes.add(blaze.getEyePosition());
            }
        } catch (java.util.ConcurrentModificationException ignored) {
            // the tracker rebuilds on another thread sometimes
        }
        return eyes;
    }

    // one line and one zone per time the pack check trips, not per tick. the zone is what the next spawner walk routes round
    private void notePack(AltoClef mod, long now) {
        if (!packHere) {
            packLogged = false;
            return;
        }
        if (packLogged) return;
        packLogged = true;
        if (packCenter != null) RunZones.INSTANCE.add(packCenter[0], packCenter[1], now, RunZones.PACK_RADIUS);
        Vec3 at = mod.getPlayer().position();
        Debug.logInternal("pack: " + packSize + " wither skeletons within " + (int) BlazeFightRules.PACK_PLAYER_RADIUS + " at "
                + Math.round(at.x) + "," + Math.round(at.y) + "," + Math.round(at.z) + ", " + (packClose ? "backing off" : "walking on"));
    }

    // projectile hits, every tick: the game hands a fresh source object per hit
    private void noteShot(LocalPlayer player, long now) {
        DamageSource source = player.getLastDamageSource();
        if (source == null || source == shotSeen) return;
        shotSeen = source;
        if (source.getDirectEntity() instanceof Projectile && source.getEntity() instanceof Mob mob
                && RecoverHold.rerunSource(BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()).getPath())) {
            shooter = mob;
            shotAt = now;
        }
    }

    // blazes close enough to matter plus whatever else is after us
    private List<Entity> runCrowd(AltoClef mod) {
        List<Entity> blazes = new ArrayList<>(threats);
        List<Entity> others = new ArrayList<>();
        // the thing that shot us lately is still after us even when it is out of the lists below (past 20, or not angry on paper)
        if (shooter != null && shooter.isAlive() && BlazeFightRules.shotLately(mod.getWorld().getGameTime(), shotAt)
                && !blazes.contains(shooter)) {
            others.add(shooter);
        }
        try {
            LocalPlayer player = mod.getPlayer();
            for (Blaze blaze : mod.getEntityTracker().getTrackedEntities(Blaze.class)) {
                if (blaze.isAlive() && player.distanceTo(blaze) <= BlazeFightRules.SIGHT_RANGE && !blazes.contains(blaze)) blazes.add(blaze);
            }
            for (Entity hostile : mod.getEntityTracker().getHostiles()) {
                if (hostile instanceof Blaze || !hostile.isAlive() || player.distanceTo(hostile) > RUN_CROWD_RADIUS) continue;
                if (EntityHelper.isAngryAtPlayer(mod, hostile)) others.add(hostile);
            }
            // the hostile list only holds the ones that noticed us, a pack that has not is still a place not to run to
            for (WitherSkeleton skeleton : mod.getEntityTracker().getTrackedEntities(WitherSkeleton.class)) {
                if (skeleton.isAlive() && player.distanceTo(skeleton) <= RUN_CROWD_RADIUS && !others.contains(skeleton)) others.add(skeleton);
            }
        } catch (java.util.ConcurrentModificationException ignored) {
            // the tracker rebuilds on another thread sometimes, we get the crowd again next tick
        }
        return merge(blazes, others);
    }

    // the run's crowd: runCrowd plus every blaze that can shoot us from further out. waiting to be hit first is too late, the
    // fight doesn't even hear about a hit while mob defense has the wheel. not in runCrowd itself, the pack back off shares that one
    private List<Entity> underFireCrowd(AltoClef mod) {
        List<Entity> crowd = runCrowd(mod);
        try {
            LocalPlayer player = mod.getPlayer();
            for (Blaze blaze : mod.getEntityTracker().getTrackedEntities(Blaze.class)) {
                // the close ones are in already, so the raycasts are only for the ones between SIGHT_RANGE and FIRE_RANGE
                if (!blaze.isAlive() || crowd.contains(blaze)) continue;
                if (BlazeFightRules.inFireRange(player.distanceTo(blaze), LookHelper.seesPlayer(blaze, player, BlazeFightRules.FIRE_RANGE))) crowd.add(blaze);
            }
        } catch (java.util.ConcurrentModificationException ignored) {
            // the tracker rebuilds on another thread sometimes, we get the crowd again next tick
        }
        return crowd;
    }

    // live wither skeletons within radius of a spot. 0 when the tracker is mid rebuild, we ask again next tick
    static int witherSkeletonsNear(AltoClef mod, Vec3 at, double radius) {
        int count = 0;
        try {
            for (WitherSkeleton skeleton : mod.getEntityTracker().getTrackedEntities(WitherSkeleton.class)) {
                if (skeleton.isAlive() && skeleton.position().distanceTo(at) <= radius) count++;
            }
        } catch (java.util.ConcurrentModificationException ignored) {
            // the tracker rebuilds on another thread sometimes
        }
        return count;
    }

    // x/z of every live wither skeleton within radius of a spot, same tracker rules as witherSkeletonsNear
    static List<double[]> witherSkeletonSpots(AltoClef mod, Vec3 at, double radius) {
        List<double[]> spots = new ArrayList<>();
        try {
            for (WitherSkeleton skeleton : mod.getEntityTracker().getTrackedEntities(WitherSkeleton.class)) {
                if (skeleton.isAlive() && skeleton.position().distanceTo(at) <= radius) spots.add(new double[]{skeleton.getX(), skeleton.getZ()});
            }
        } catch (java.util.ConcurrentModificationException ignored) {
            // the tracker rebuilds on another thread sometimes
        }
        return spots;
    }

    static <T> List<T> merge(List<? extends T> blazes, List<? extends T> others) {
        List<T> all = new ArrayList<>(blazes);
        all.addAll(others);
        return all;
    }

    // an idle run (nobody looking, just healing) that has not gained hp in RUN_GIVE_UP_TICKS gives up. runs after decide, the
    // flag is read by the next one
    private void watchRun(long now, float health) {
        boolean rose = health > lastHealth;
        boolean idle = mode == Mode.RUN && threats.isEmpty();
        boolean stalled = false;
        if (!idle || rose || now < runIdleSince) {
            runIdleSince = idle ? now : Long.MIN_VALUE;
        } else {
            if (runIdleSince == Long.MIN_VALUE) runIdleSince = now;
            stalled = BlazeFightRules.runStalled(now - runIdleSince);
        }
        runGaveUp = BlazeFightRules.runGaveUpNext(runGaveUp, mode == Mode.RUN, rose, stalled);
    }

    // where the run starts and who is in it, so a run into unseen skeletons can be told from a run into nothing
    private String runDetail(AltoClef mod, float health) {
        LocalPlayer player = mod.getPlayer();
        StringBuilder crowd = new StringBuilder();
        for (Entity e : underFireCrowd(mod)) {
            if (crowd.length() > 0) crowd.append(", ");
            crowd.append(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath()).append(' ').append(Math.round(player.distanceTo(e)));
        }
        return "rods: running at " + String.format(java.util.Locale.ROOT, "%.1f", health) + " hp from " + player.blockPosition().toShortString()
                + ", " + RUN_DISTANCE + " block run, crowd [" + crowd + "], burning " + player.isOnFire()
                + ", wither " + player.hasEffect(MobEffects.WITHER);
    }

    private void logChange(AltoClef mod, float health) {
        if (mode == loggedMode) return;
        loggedMode = mode;
        switch (mode) {
            case KILL -> Debug.logInternal("rods: going after a blaze (" + candidates.size() + " in reach)");
            case RUN -> Debug.logInternal(runDetail(mod, health));
            case CAMP -> Debug.logInternal("rods: all quiet, back to camping");
        }
    }

    // ---- what is out there

    private void scan(AltoClef mod, LocalPlayer player, long now) {
        List<Blaze> blazes;
        try {
            blazes = new ArrayList<>(mod.getEntityTracker().getTrackedEntities(Blaze.class));
        } catch (java.util.ConcurrentModificationException e) {
            // the tracker rebuilds on another thread sometimes, one tick of stale blazes is fine
            return;
        }
        threats.clear();
        candidates.clear();
        packDrop = false;
        // (the same pack check as for a blaze below, asked where we stand: the closet is the thing, not the blaze over it)
        List<double[]> nearUs = witherSkeletonSpots(mod, player.position(), BlazeFightRules.PACK_PLAYER_RADIUS);
        packHere = BlazeFightRules.packNear(nearUs.size());
        packSize = nearUs.size();
        packCenter = packHere ? BlazeFightRules.packCenter(nearUs) : null;
        // (once the back off starts it runs until the pack is out of the hold radius too, else it stops at 8.1 blocks with them still on top of us)
        packClose = packHere && (packClose || BlazeFightRules.backOffNow(witherSkeletonsNear(mod, player.position(), BlazeFightRules.PACK_BACKOFF_RADIUS),
                witherSkeletonsNear(mod, player.position(), BlazeFightRules.BACKOFF_CLOSE)));
        around = 0;
        nearest = Double.POSITIVE_INFINITY;
        targetTracked = false;
        targetInReach = false;
        noteCrowd(mod, player, blazes, now);
        BlockPos corner = activeCorner();
        wallOf = corner;
        // (in the pocket only a blaze in our view is ours: the walk at one behind its wall broke the wall. in view is any point of
        // its body from our eyes, or biting: by eye to eye only, one in the door at 1.7 blocks that bit was let go each tick)
        if (target != null && corner != null && BlazeLureTask.in(player.position(), corner)
                && player.distanceTo(target) > BITE_RANGE && !BlazeCover.bodySeen(mod.getWorld(), player.getEyePosition(), target, player)) {
            noteLetGo("out of our view in the pocket", player);
            target = null;
        }
        cornerHold = corner != null;
        // (steering 35, 5 Nov 2026: watched in game, the buddy died each time it stayed out of its pillbox for long. the three
        // deaths read in realfort-a-raised2 all began with a chase out of a standing box, each step
        // out putting more blazes within the player's reach, and a way back that found no path. so a trip out of a standing
        // pillbox (out of B, F and O: farther than BOX_IN from B's middle) has a limit: the chase and the rods out there go on
        // for pillbox_trip ticks, not at all on fire or under pillbox_trip_hp; then the target is let go and none is taken till
        // we are back in, and the camp walks back into B. 0, the default, is no limit)
        double trip = adris.altoclef.util.helpers.Tuning.get("pillbox_trip", 0);
        tripOver = false;
        if (trip > 0 && pillboxPlanned && corner != null && !building) {
            double bx = player.getX() - (corner.getX() + 0.5), bz = player.getZ() - (corner.getZ() + 0.5);
            boolean inBox = bx * bx + bz * bz <= BOX_IN * BOX_IN && Math.abs(player.getY() - corner.getY()) < 1;
            if (inBox || now < tripSince) tripSince = Long.MIN_VALUE;
            else if (tripSince == Long.MIN_VALUE) tripSince = now;
            tripOver = !inBox && (now - tripSince > trip || player.isOnFire()
                    || player.getHealth() < adris.altoclef.util.helpers.Tuning.get("pillbox_trip_hp", TRIP_HP));
            if (tripOver && target != null) {
                noteLetGo("the trip out of the box is over (" + (now - tripSince) + " ticks out, " + Math.round(player.getHealth()) + " hp"
                        + (player.isOnFire() ? ", on fire" : "") + ")", player);
                target = null;
            }
        } else {
            tripSince = Long.MIN_VALUE;
        }
        // (on the leash: the one we are after is let go once it is CORNER_LEASH from the corner)
        if (cornerHold && target != null && target.position().distanceTo(Vec3.atBottomCenterOf(corner)) > adris.altoclef.util.helpers.Tuning.get("corner_leash", CORNER_LEASH)) {
            noteLetGo("past the leash, " + Math.round(target.position().distanceTo(Vec3.atBottomCenterOf(corner))) + " from the corner", player);
            target = null;
        }
        Vec3 cornerEye = corner == null ? null : new Vec3(corner.getX() + 0.5, corner.getY() + 1.62, corner.getZ() + 0.5);
        // (a pillbox: one that sees its look out has come round, not one that sees its cell, which no blaze sees by design: from
        // the cell no blaze was ever in reach but a biting one, stalls with blazes within four, realfort-b-bent-m run 4)
        BlockPos seeCell = pillboxPlanned && pillboxLookout != null ? pillboxLookout : corner;
        for (Blaze blaze : blazes) {
            if (!blaze.isAlive()) continue;
            double distance = player.distanceTo(blaze);
            nearest = Math.min(nearest, distance);
            boolean inReach = false;
            // a blaze hovering over a wither skeleton pack is theirs, not ours
            // the one we are already after is checked at any distance, so it can't drift out of pick range and keep us walking at a pack
            boolean packed = packHere || ((distance <= range || blaze == target)
                    && BlazeFightRules.packNear(witherSkeletonsNear(mod, blaze.position(), BlazeFightRules.PACK_BLAZE_RADIUS)));
            // a pack we already met is still there when one of them walks out of tracking, the zone remembers it for us
            packed |= RunZones.INSTANCE.covers(blaze.getX(), blaze.getZ(), now);
            if (packed && blaze == target) packDrop = true;
            if (distance <= range && !packed) {
                around++;
                Floor floor = floorUnder(mod.getWorld(), blaze);
                inReach = !tripOver && BlazeFightRules.isReachable(floor.height(), floor.lava(), distance, range, blaze == target)
                        && mod.getEntityTracker().isEntityReachable(blaze)
                        && (!building || distance <= BITE_RANGE)
                        // (from a pillbox only one the sword reaches from it: one "in reach" six from the corner was walked to
                        // and let go by the leash out of the box, again and again, bent-e run 1, dead on the bridge)
                        && (!pillboxPlanned || corner == null || distance <= adris.altoclef.util.helpers.Tuning.get("pillbox_reach", PILLBOX_REACH))
                        // (the buddy's fork, Tuning fireproof_hold, blocks: under fire resistance only one that close, the rest
                        // come to the camp. the fireballs do little then, and the chase to blaze after blaze ended "no path"
                        // and a ban each, 54 in five minutes with blazes in sight and no rod, steering 67)
                        && (fireproofHold(player) <= 0 || distance <= fireproofHold(player))
                        && (fireproofReach(player) <= 0 || blaze.getY() - player.getY() <= fireproofReach(player))
                        && (!cornerHold || blaze == target || distance <= BITE_RANGE || (blaze.getEyePosition().distanceTo(cornerEye) <= CORNER_REACH
                        && (!BlazeCover.hidden(mod.getWorld(), List.of(blaze.getEyePosition()), seeCell.getX(), seeCell.getY(), seeCell.getZ(), player)
                        || BlazeCover.bodySeen(mod.getWorld(), player.getEyePosition(), blaze, player))));
                if (inReach) candidates.add(blaze);
            }
            if (blaze == target) {
                targetTracked = true;
                targetInReach = inReach;
            }
            // raycasts only for the ones that could actually hit us from where they are
            if (distance <= BlazeFightRules.SIGHT_RANGE && LookHelper.seesPlayer(blaze, player, BlazeFightRules.SIGHT_RANGE)) {
                threats.add(blaze);
            }
        }
        lastSeen = threats.size();
        seenLast = new ArrayList<>(threats);
    }

    // keep the blaze we have until it is dead, gone or has been out of reach a while, then take the nearest one in reach
    private void updateTarget(LocalPlayer player, long now) {
        if (target != null) {
            // a clock that went backwards (new world) starts the out of reach count over
            if (targetInReach || now < outOfReachSince) {
                outOfReachSince = targetInReach ? Long.MIN_VALUE : now;
            } else if (outOfReachSince == Long.MIN_VALUE) {
                outOfReachSince = now;
            }
            long outFor = outOfReachSince == Long.MIN_VALUE ? 0 : now - outOfReachSince;
            if (packDrop || !BlazeFightRules.keepTarget(target.isAlive(), targetTracked, outFor)) target = null;
        }
        if (target != null) return;
        outOfReachSince = Long.MIN_VALUE;
        Blaze nearest = null;
        double best = Double.POSITIVE_INFINITY;
        boolean leashHeld = now < leashHoldUntil && now >= leashHoldUntil - LEASH_HOLD_TICKS;
        for (Blaze blaze : candidates) {
            double d = player.distanceToSqr(blaze);
            if (leashHeld && d > BITE_RANGE * BITE_RANGE) continue;
            if (d < best) {
                best = d;
                nearest = blaze;
            }
        }
        target = nearest;
        targetTracked = nearest != null;
        targetInReach = nearest != null;
    }

    private List<Entity> threatEntities() {
        return new ArrayList<>(threats);
    }

    // what is under a blaze: how high it hovers over the block we would stand on, and whether it is lava instead
    private record Floor(double height, boolean lava) {
    }

    // walk straight down from the blaze to whatever it is hovering over. lava is a no, a floor is how high it is
    private static Floor floorUnder(Level level, Blaze blaze) {
        BlockPos at = blaze.blockPosition();
        for (int i = 0; i <= 8; i++) {
            BlockPos check = at.below(i);
            BlockState state = level.getBlockState(check);
            if (state.is(Blocks.LAVA)) return new Floor(Double.POSITIVE_INFINITY, true);
            if (!state.getCollisionShape(level, check).isEmpty()) {
                // fences and slabs and stairs are not a full block, use the actual top of the thing
                return new Floor(blaze.getY() - (check.getY() + state.getCollisionShape(level, check).max(Direction.Axis.Y)), false);
            }
        }
        return new Floor(Double.POSITIVE_INFINITY, false);
    }
}
