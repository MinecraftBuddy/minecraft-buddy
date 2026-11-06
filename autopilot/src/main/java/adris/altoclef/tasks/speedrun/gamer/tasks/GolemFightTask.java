package adris.altoclef.tasks.speedrun.gamer.tasks;

import adris.altoclef.AltoClef;
import adris.altoclef.BotBehaviour;
import adris.altoclef.tasks.entity.AbstractKillEntityTask;
import adris.altoclef.tasks.movement.GetToEntityTask;
import adris.altoclef.tasks.movement.PickupDroppedItemTask;
import adris.altoclef.tasks.resources.GetBuildingMaterialsTask;
import adris.altoclef.tasks.speedrun.gamer.GamerContext;
import adris.altoclef.tasks.speedrun.gamer.GolemRules;
import adris.altoclef.tasks.speedrun.gamer.config.OverworldConfig;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.helpers.MobFooting;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.slots.PlayerSlot;
import baritone.behavior.CombatAim;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

// one iron golem, killed from a pillar. golems cannot reach a player whose feet are above their head (GolemRules has
// the numbers), so: walk up while it is calm, stack blocks next to it until we are high enough, then poke it with full
// cooldown swings. it does not get knocked back so it stays at the foot of the pillar and keeps coming back into reach.
//
// the one thing this task does not do is leave the pillar while the golem is angry. coming down into a 7 to 21 damage
// hit is how the whole plan loses, so the exit is "dead, gone, or has had time to calm down", never "gave up"
public final class GolemFightTask extends Task {
    public static final List<Item> SWORDS = List.of(Items.NETHERITE_SWORD, Items.DIAMOND_SWORD, Items.IRON_SWORD,
            Items.STONE_SWORD, Items.GOLDEN_SWORD, Items.WOODEN_SWORD);
    public static final List<Item> AXES = List.of(Items.NETHERITE_AXE, Items.DIAMOND_AXE, Items.IRON_AXE,
            Items.STONE_AXE, Items.GOLDEN_AXE, Items.WOODEN_AXE);
    // we stop walking and start stacking this close (center to center), it fits the reach numbers and leaves room for
    // the block, a golem overlapping our column cell makes the game refuse the placement
    private static final double PILLAR_FROM = 2.5;
    private static final double APPROACH_TO = 2.0;
    private static final int MAX_REPILLARS = 2;
    private static final int GOLEM_AIR = 3;
    // a dead golem's drops take a moment to show up
    private static final int DROPS_WAIT_TICKS = 40;
    private static final int LOOT_TICKS = 20 * 20;
    // a golem drops 3 to 5 ingots, picking up five is all of them
    private static final int MAX_DROP = 5;
    // an entity that vanished from the client is unloaded, teleported or dead without us seeing it. give it this long to
    // come back (or for the drops to show) before we decide anything
    private static final int MISSING_TICKS = 20 * 10;
    // the calm golem walked off while we stood on the pillar: walk back down and stack next to it again, this many times
    private static final int MAX_REPOSITIONS = 2;
    private static final double REPOSITION_AFTER_SECONDS = 2.5;
    // past the soft fight cap we wait out an angry golem for this long, then leave anyway (a flag that never clears)
    private static final double HOLD_EXTRA_SECONDS = 120;
    private static final double HOLD_EXTRA_UNTOUCHED_SECONDS = 30;
    private static final int PROGRESS_EVERY_TICKS = 100;
    private static final int HOSTILE_CHECK_TICKS = 10;
    private static final double FAR_AWAY = 24;

    private enum State {
        APPROACH, GATHER, PILLAR, FIGHT, LOOT, DONE
    }

    private final int golemId;
    private final GamerContext ctx;
    private final OverworldConfig cfg;

    private State state = State.APPROACH;
    private long stateSince = Long.MIN_VALUE;
    private long startTick = Long.MIN_VALUE;
    private long fightSince = -1;
    private long lastHitTick = Long.MIN_VALUE / 2;
    private long lastProgressTick;
    private long lootSince;
    private int hits;
    private int outOfReachTicks;
    private int repillars;
    // the safe feet height the pillar task was last given, so a higher step showing up later restarts it
    private double pillarSafe;
    private int repositions;
    private int lootBase;
    private long missingSince = -1;
    private int gatherTarget;
    private GolemRules.Abort abort = GolemRules.Abort.NONE;
    private boolean golemSeenDead;
    private boolean killed;
    private boolean hostilesNear;
    private boolean ownsBehaviour;
    // the level we pushed, for the hand over on a pause
    private BotBehaviour.State level;
    private AltoClef mod;
    private Task subtask;
    private String hud = "Walking up to an iron golem";

    public GolemFightTask(int golemId, GamerContext ctx) {
        this.golemId = golemId;
        this.ctx = ctx;
        this.cfg = ctx.cfg().overworld;
    }

    public int golemId() {
        return golemId;
    }

    public boolean killed() {
        return killed;
    }

    public String hud() {
        return hud;
    }

    // why it ended early, NONE for a fight that ran its course. GolemHunt decides from this if the golem is burned
    public GolemRules.Abort abort() {
        return abort;
    }

    // monsters close enough to ruin it (a creeper at the foot of the pillar does not care how tall we are)
    public static boolean monstersNear(AltoClef mod, int radius, int ignoreId) {
        LocalPlayer player = mod.getPlayer();
        AABB box = player.getBoundingBox().inflate(radius);
        // the angry golem we are fighting is in getHostiles too, that one is not "other"
        return !mod.getWorld().getEntitiesOfClass(Monster.class, box, Entity::isAlive).isEmpty()
                || mod.getEntityTracker().getHostiles().stream().anyMatch(e -> e.getId() != ignoreId);
    }

    @Override
    protected void onStart(AltoClef mod) {
        this.mod = mod;
        // live predicate: the golem is only ours to fight while we are actually above its head. fall off or get
        // squashed down and mob defense gets its say again
        mod.getBehaviour().releasePaused(this);
        level = mod.getBehaviour().push();
        mod.getBehaviour().addForceFieldExclusion(this::golemWeFightFromAbove);
        mod.getBehaviour().addMobDefenseExclusion(this::golemWeFightFromAbove);
        ownsBehaviour = true;
        if (startTick == Long.MIN_VALUE) {
            startTick = mod.getWorld().getGameTime();
            stateSince = startTick;
            lastProgressTick = startTick;
        }
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        // same hand over as ResourceTask: a chain cutting in must not make the golem fair game for mob defense
        if (isInterrupting()) {
            mod.getBehaviour().holdWhilePaused(this, chain(), level);
        } else {
            mod.getBehaviour().releasePaused(this);
        }
        if (ownsBehaviour) {
            mod.getBehaviour().pop();
            ownsBehaviour = false;
        }
        level = null;
        subtask = null;
    }

    @Override
    protected void onStopWhilePaused(AltoClef mod) {
        mod.getBehaviour().releasePaused(this);
    }

    private boolean golemWeFightFromAbove(Entity entity) {
        if (entity.getId() != golemId || state != State.FIGHT) {
            return false;
        }
        LocalPlayer player = mod == null ? null : mod.getPlayer();
        return player != null && !GolemRules.golemCanHitUs(player.getY(), entity.getY());
    }

    private IronGolem golem(AltoClef mod) {
        Entity e = mod.getWorld().getEntity(golemId);
        return e instanceof IronGolem g ? g : null;
    }

    private void enter(State next, long now, String why) {
        state = next;
        stateSince = now;
        subtask = null;
        outOfReachTicks = 0;
        adris.altoclef.Debug.logInternal("golem: " + why);
    }

    // DONE with a reason on it, so the hunt can tell "the day was wrong" from "the golem won"
    private void quit(GolemRules.Abort why, long now, String msg) {
        abort = why;
        enter(State.DONE, now, msg);
    }

    private void progress(long now, String what) {
        lastProgressTick = now;
        ctx.progress(what);
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        return state == State.DONE;
    }

    @Override
    protected Task onTick(AltoClef mod) {
        LocalPlayer player = mod.getPlayer();
        long now = mod.getWorld().getGameTime();
        IronGolem g = golem(mod);
        if (g != null && !g.isDeadOrDying() && !g.isAlive()) {
            // removed without dying: unloaded. that is "not here", not "dead"
            g = null;
        }
        if (g != null && g.isDeadOrDying()) {
            golemSeenDead = true;
        }
        if (g != null) {
            missingSince = -1;
        }
        if (now % HOSTILE_CHECK_TICKS == 0) {
            hostilesNear = monstersNear(mod, cfg.golemHostileRadius, golemId);
        }
        // only a death we watched ends the fight
        if (golemSeenDead && state != State.LOOT && state != State.DONE) {
            if (hits == 0) {
                quit(GolemRules.Abort.GONE, now, "golem gone before we hit it");
                return null;
            }
            killed = true;
            startLoot(mod, now, "golem dead after " + hits + " hits");
        } else if (g == null && state != State.LOOT && state != State.DONE) {
            missing(mod, now);
            return null;
        }
        return switch (state) {
            case APPROACH -> approach(mod, player, g, now);
            case GATHER -> gather(mod, g, now);
            case PILLAR -> pillar(mod, player, g, now);
            case FIGHT -> fight(mod, player, g, now);
            case LOOT -> loot(mod, now);
            case DONE -> null;
        };
    }

    private void startLoot(AltoClef mod, long now, String why) {
        lootSince = now;
        lootBase = ctx.facts().count(Items.IRON_INGOT);
        hud = "Picking up the golem's iron";
        enter(State.LOOT, now, why);
    }

    // the golem is not in the client's entity list and we never saw it die. the mob that ate it is not worth guessing at, so
    // wait for it to come back or for its drops, then give up in a way that never walks us down into a live golem
    private void missing(AltoClef mod, long now) {
        if (missingSince < 0) {
            missingSince = now;
        }
        hud = "Waiting for the iron golem to show up again";
        if (now - missingSince < MISSING_TICKS) {
            return;
        }
        if (hits == 0) {
            quit(GolemRules.Abort.LOST, now, "golem vanished before we hit it");
        } else if (now - lastHitTick > (long) (cfg.golemCalmSeconds * 20)) {
            // gone for good and it has had longer than its anger lasts, so nothing is waiting for us down there. any iron on
            // the ground is a guess (it could be anybody's), LOOT will find out
            killed = mod.getEntityTracker().itemDropped(Items.IRON_INGOT);
            startLoot(mod, now, "golem out of sight after " + hits + " hits");
        }
    }

    private Task approach(AltoClef mod, LocalPlayer player, IronGolem g, long now) {
        hud = "Walking up to an iron golem";
        if (g.isAggressive() || hostilesNear) {
            // it noticed us (or something else did) while we are still on the ground. not our plan any more
            quit(g.isAggressive() ? GolemRules.Abort.ANGRY_ON_GROUND : GolemRules.Abort.MONSTERS, now,
                    g.isAggressive() ? "golem is angry on the ground, backing out" : "monsters showed up");
            return null;
        }
        if ((now - stateSince) / 20.0 > cfg.golemApproachSeconds) {
            quit(GolemRules.Abort.UNREACHABLE, now, "could not get to the golem in time");
            return null;
        }
        double dx = g.getX() - player.getX();
        double dz = g.getZ() - player.getZ();
        if (Math.sqrt(dx * dx + dz * dz) <= PILLAR_FROM && player.onGround()) {
            enter(State.PILLAR, now, "close enough, stacking");
            return null;
        }
        if (subtask == null) {
            subtask = new GetToEntityTask(g, APPROACH_TO);
        }
        return subtask;
    }

    // cobble or dirt up to need + spares, then back to walking up to it. the golem is calm the whole time (the approach
    // checks that again on the way back), so this is just a mining trip with a clock on it
    private Task gather(AltoClef mod, IronGolem g, long now) {
        hud = "Gathering blocks for an iron golem pillar";
        if (g.isAggressive() || hostilesNear) {
            quit(g.isAggressive() ? GolemRules.Abort.ANGRY_ON_GROUND : GolemRules.Abort.MONSTERS, now,
                    g.isAggressive() ? "golem noticed us while we gathered" : "monsters showed up while we gathered");
            return null;
        }
        if (ctx.facts().buildBlocks() >= gatherTarget) {
            enter(State.APPROACH, now, "got the blocks (" + ctx.facts().buildBlocks() + "), back to the golem");
            return null;
        }
        // mining a stack of cobble is slower than stacking it, so this gets double the pillar clock
        if ((now - stateSince) / 20.0 > cfg.golemPillarSeconds * 2) {
            quit(GolemRules.Abort.NO_BLOCKS, now, "could not find blocks in time (" + ctx.facts().buildBlocks() + " of " + gatherTarget + ")");
            return null;
        }
        if (subtask == null) {
            subtask = new GetBuildingMaterialsTask(gatherTarget);
        }
        return subtask;
    }

    private Task pillar(AltoClef mod, LocalPlayer player, IronGolem g, long now) {
        hud = "Pillaring up next to an iron golem";
        double ground = golemGround(mod, player, g);
        double safe = GolemRules.safeFeetY(ground, cfg.golemSafeMargin);
        if (player.onGround() && player.getY() >= safe - 1.0e-6) {
            if (fightSince < 0) {
                fightSince = now;
            }
            enter(State.FIGHT, now, "on top at y=" + player.getY());
            progress(now, "on the pillar next to an iron golem");
            return null;
        }
        if (hostilesNear && hits == 0) {
            quit(GolemRules.Abort.MONSTERS, now, "monsters showed up while stacking");
            return null;
        }
        if ((now - stateSince) / 20.0 > cfg.golemPillarSeconds) {
            quit(GolemRules.Abort.PILLAR_STUCK, now, "pillar went nowhere");
            return null;
        }
        // the golem found a higher step since the pillar task was given its target: ask again from where we stand
        if (subtask != null && safe > pillarSafe + 0.5) {
            subtask = null;
        }
        if (subtask == null) {
            int need = GolemRules.blocksToRaise(player.getY(), golemGround(mod, player, g), cfg.golemSafeMargin,
                    cfg.golemMaxPillar);
            int have = ctx.facts().buildBlocks();
            adris.altoclef.Debug.logInternal("golem: pillar wants " + need + " blocks, buildBlocks " + have
                    + " (us y=" + player.getY() + " golem y=" + g.getY() + ")");
            if (need < 0) {
                quit(GolemRules.Abort.TOO_TALL, now, "not worth a pillar this tall (golem y=" + g.getY() + ", us y=" + player.getY() + ")");
                return null;
            }
            if (have < need) {
                // not on the pillar yet and nothing has hit us: go and get the blocks instead of writing the golem off. on a
                // pillar with an angry golem below, walking off to mine is the one thing worse than leaving
                if (fightSince < 0 && hits == 0) {
                    gatherTarget = need + GolemRules.SPARE_BLOCKS;
                    enter(State.GATHER, now, "short of blocks (" + have + " of " + need + "), gathering up to " + gatherTarget);
                } else {
                    quit(GolemRules.Abort.NO_BLOCKS, now, "not enough blocks to restack (" + have + " of " + need + ")");
                }
                return null;
            }
            BlockPos here = player.blockPosition();
            pillarSafe = safe;
            subtask = new PillarHereTask(here.above(Math.max(need, 1)));
        }
        return subtask;
    }

    // where the golem's feet could be right next to our column: its own ground, or a step (a block or a slab) up on any of the 8
    // sides it can walk up. built for the low side only, the pillar gets a golem that steps up and hits us before we hit it
    private double golemGround(AltoClef mod, LocalPlayer player, IronGolem g) {
        // a 2.7 tall golem needs 3 free cells above the step
        return MobFooting.groundBeside(mod.getWorld(), player.blockPosition(), g.getY(), GOLEM_AIR);
    }

    private Task fight(AltoClef mod, LocalPlayer player, IronGolem g, long now) {
        hud = "Fighting an iron golem from a pillar";
        if (GolemRules.golemCanHitUs(player.getY(), golemGround(mod, player, g)) && player.onGround()) {
            // it walked onto higher ground (or we are standing lower than we thought). one more block, a couple of times
            if (repillars < MAX_REPILLARS) {
                repillars++;
                enter(State.PILLAR, now, "golem got higher than our feet, stacking more");
            } else {
                quit(GolemRules.Abort.GAVE_UP, now, "cannot stay above it");
            }
            return null;
        }
        long sinceStart = now - fightSince;
        double fightSeconds = sinceStart / 20.0;
        boolean wantFight = fightSeconds <= cfg.golemFightSeconds && player.getHealth() >= cfg.golemAbortHealth && !hostilesNear;
        Vec3 hitPoint = reachablePoint(player, g);
        boolean reachable = hitPoint != null;
        if (wantFight && reachable) {
            outOfReachTicks = 0;
            if (hit(mod, g, hitPoint, now)) {
                return null;
            }
        } else {
            outOfReachTicks++;
        }
        if (now - lastProgressTick > PROGRESS_EVERY_TICKS) {
            // waiting for it to come back or to calm down is the plan, not a stall
            progress(now, "waiting on the pillar for the golem");
        }
        if (wantFight && !reachable && hits == 0 && !g.isAggressive() && repositions < MAX_REPOSITIONS
                && outOfReachTicks / 20.0 > REPOSITION_AFTER_SECONDS) {
            // a calm golem wanders and we have not touched it yet, so nothing up here is in danger. go back down and stack
            // next to it where it is now. the old answer was to wait 5 s and write the golem off for the run
            repositions++;
            fightSince = -1;
            enter(State.APPROACH, now, "golem out of reach and calm, walking back to it (" + repositions + " of " + MAX_REPOSITIONS + ")");
            return null;
        }
        boolean givingUp = !wantFight || outOfReachTicks / 20.0 > cfg.golemOutOfReachSeconds;
        double dist = Math.sqrt(g.distanceToSqr(player));
        // the safe check used to be skipped past the hard cap, which is exactly when an angry golem was still waiting below
        boolean safe = GolemRules.safeToComeDown(true, g.isAggressive(), dist, now - lastHitTick,
                (long) (cfg.golemCalmSeconds * 20), FAR_AWAY);
        double softCap = cfg.golemFightSeconds + cfg.golemCalmSeconds + 15;
        // a golem we never touched has no grudge against us, if it is "angry" it is busy with something else and we should not
        // wait on that for minutes
        double holdExtra = hits == 0 ? HOLD_EXTRA_UNTOUCHED_SECONDS : HOLD_EXTRA_SECONDS;
        if (GolemRules.leavePillar(givingUp, fightSeconds, softCap, softCap + holdExtra, safe)) {
            if (hits == 0) {
                // never landed one: the golem is not spent, the day was just wrong. hunt may try it again after a cooldown
                quit(hostilesNear ? GolemRules.Abort.MONSTERS : GolemRules.Abort.OUT_OF_REACH, now, "leaving the pillar after 0 hits");
            } else {
                enter(State.DONE, now, "leaving the pillar after " + hits + " hits");
            }
        }
        return null;
    }

    // vanilla melee wants the hitbox inside the interaction range and nothing solid between the eye and the bit we hit.
    // a ray to the middle of its head used to fail on a pillar corner with the golem standing right there, so any point
    // of the box in range with a clear ray counts. that point, or null when there is none
    private Vec3 reachablePoint(LocalPlayer player, IronGolem g) {
        AABB box = g.getBoundingBox();
        var eye = player.getEyePosition();
        for (double[] pt : GolemRules.aimPoints(eye.x, eye.y, eye.z, box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ,
                GolemRules.INTERACT_RANGE)) {
            Vec3 point = new Vec3(pt[0], pt[1], pt[2]);
            if (LookHelper.cleanLineOfSight(player, eye, point, 8)) {
                return point;
            }
        }
        return null;
    }

    // true when this tick was spent on the weapon swap or the swing. the fight state never has a path of ours alive (it
    // returns no child and the approach and pillar children are stopped on the way in), so CombatAim gets the head here
    // and the swing goes through the controller's aim gate like every other melee swing
    private boolean hit(AltoClef mod, IronGolem g, Vec3 point, long now) {
        // the point we found a clear line to, so a pillar corner doesn't leave the aim staring at stone
        mod.getControllerExtras().aimAt(g, CombatAim.Priority.GOLEM, point);
        if (equipWeapon(mod)) {
            return true;
        }
        // full cooldown only, a half charged swing is half the damage for the same 0.5 s of waiting
        if (!mod.getControllerExtras().attackReady()) {
            return false;
        }
        // false while the crosshair is still on its way over, the next tick asks again
        if (!mod.getControllerExtras().attack(g)) {
            return false;
        }
        lastHitTick = now;
        hits++;
        progress(now, "hit an iron golem (" + hits + ")");
        return true;
    }

    // sword if we have one, an axe if that is all there is. same swap rule as the kill tasks: swap now, swing next tick
    static boolean equipWeapon(AltoClef mod) {
        if (AbstractKillEntityTask.bestWeapon(mod) != null) {
            return AbstractKillEntityTask.equipWeapon(mod);
        }
        Item inHand = StorageHelper.getItemStackInSlot(PlayerSlot.getEquipSlot()).getItem();
        for (Item axe : AXES) {
            if (mod.getItemStorage().hasItem(axe)) {
                if (axe != inHand) {
                    mod.getSlotHandler().forceEquipItem(axe);
                    return true;
                }
                return false;
            }
        }
        return false;
    }

    // the drops land at the foot of the pillar. baritone paths down on its own to fetch them (3 block drops are fine
    // for it, taller pillars it digs out), so there is no separate "get down" step
    private Task loot(AltoClef mod, long now) {
        hud = "Picking up the golem's iron";
        boolean dropped = mod.getEntityTracker().itemDropped(Items.IRON_INGOT);
        long waited = now - lootSince;
        int got = ctx.facts().count(Items.IRON_INGOT) - lootBase;
        // done when the drops are in the bag and none are left on the ground, or it is all five. a flat "+5" target never
        // happened, a golem drops 3 to 5
        boolean enough = got >= MAX_DROP || (got > 0 && !dropped);
        if (enough || waited > LOOT_TICKS || (!dropped && waited > DROPS_WAIT_TICKS)) {
            enter(State.DONE, now, "iron collected, now " + ctx.facts().count(Items.IRON_INGOT) + " ingots");
            return null;
        }
        if (!dropped) {
            return null;
        }
        if (subtask == null) {
            subtask = new PickupDroppedItemTask(Items.IRON_INGOT, lootBase + MAX_DROP);
        }
        progress(now, "picking up golem iron");
        return subtask;
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof GolemFightTask task && task.golemId == golemId;
    }

    @Override
    protected String toDebugString() {
        return "Iron golem fight, state " + state + ", " + hits + " hits";
    }

    @Override
    protected String toHudString() {
        return hud;
    }
}
