package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.InteractWithBlockTask;
import adris.altoclef.tasks.construction.DestroyBlockTask;
import adris.altoclef.tasks.container.BrewFireResistanceTask;
import adris.altoclef.tasks.movement.GetToBlockTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.BlazeCover;
import adris.altoclef.util.helpers.BlazeShelter;
import adris.altoclef.util.helpers.BrewBox;
import adris.altoclef.util.helpers.EntityHelper;
import adris.altoclef.util.helpers.FireRes;
import baritone.pathing.movement.CliffAvoidance;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

// the buddy's fork, Tuning brew_box: the brew of fire resistance walled in (BrewBox). the stand first, made in the bag; then the
// box planned near the buddy and built as a pillbox is (BlazePillboxTask: from inside B, the roof row from F, out of a charged
// blaze's sight while it waits); then the buddy in B, the stand in F and the brew's steps from B (BrewFireResistanceTask, the
// stand pinned). while the box holds (holds: whole, the buddy inside, nothing hostile in it, no fire in a cell, at least
// BrewBox.LOW_HP) the rods task skips its fight and the mob defense stands down (BlazeShelter.brewBox). the potion is drunk
// inside, then a wall with a walk out beyond it is broken and the buddy walks out (the next wall when that gets nowhere), and the
// order goes on. no box (no floor, no blocks, three tries): the open brew as before. no stand in F or the bag at the stand's
// step: opened at once
final class BrewBoxTask extends Task {
    enum Stage { PREP, BUILD, STEP_IN, CRAFT, STAND, BREW, DRINK, HEAL, OPEN, LEAVE, DONE }

    // a box not whole after this many builds is given up
    static final int TRIES = 3;
    // the ticks one build may run (the ticks it had the wheel: a fight in between does not count)
    static final long TRY_TICKS = 1200;
    // the step into B and the stand's click, at most (ticks it ran)
    static final long STEP_TICKS = 100;
    static final long STAND_TICKS = 100;
    // the stand made inside the pillbox (brew_box 2): the table in F, the craft, the table back, at most (ticks it ran)
    static final long CRAFT_TICKS = 600;
    // the drink asked for this long at most, and the door broken open in this long at most (game ticks)
    static final long DRINK_TICKS = 200;
    static final long OPEN_TICKS = 400;
    // the walk out through an open door, at most (ticks it ran: a fight in between does not count), and the ticks of it with no
    // path under way: then the next wall is broken
    static final long LEAVE_TICKS = 200;
    static final long NO_PATH_TICKS = 60;

    private final BrewFireResistanceTask brew;
    private final BlockPos spawner;
    // the cells the box may not take (a pillbox of the fight's under way), asked at each plan
    private final Supplier<Set<BlockPos>> keepClear;

    private Stage stage = Stage.PREP;
    private BrewBox.Box box;
    private BlazePillboxTask builder;
    private int tries;
    private long tryTicks;
    private long buildStartedAt;
    // every cell a plan put a block in, BlazeCover.key (the door is one of ours), and the same cells as positions
    private final Set<Long> placed = new HashSet<>();
    private final Set<BlockPos> placedAt = new java.util.LinkedHashSet<>();
    // the world of the last tick, for fail()
    private Level tickLevel;
    private boolean failed;
    private long stepTicks;
    private long standTicks;
    private Task placeStand;
    private long craftTicks;
    private Task placeTable;
    private Task takeTable;
    private long drinkSince;
    // the walls to break, best first (BrewBox.doors), and the one being opened or walked out of
    private List<BrewBox.Door> doors;
    private int doorAt;
    private long openSince;
    // brew_open_lookout: when the stand in F began to be taken down, and whether that was given up
    private long standSince = Long.MIN_VALUE;
    private boolean standGaveUp;
    private long leaveTicks;
    private long noPathTicks;
    private Task leaving;
    private Task breaking;
    private BlockPos breakingAt;
    private DamageSource lastHit;
    private long holdsAt = Long.MIN_VALUE;
    private boolean holdsWas;
    private String holdsWhy = "";

    // the fight's standing pillbox as the box (brew_box 2): its cell B and the step to its front cell F. the bent box is the brew
    // box's two cells with the look out open, so its walls and roof stand and only the look out's column goes in. null: a box
    // of its own planned near the buddy (brew_box 1)
    private final int[] pillboxBack;
    private final int[] pillboxDir;
    // its door (the cell outside the look out) and look out, for the way back in: a fight's run during the build took the buddy
    // out, and the build walked at the cell from the walled side and gave up (piece2craft-1, Steve and Makena, steering 69)
    private final BlockPos pillboxDoor;
    private final BlockPos pillboxLookout;
    private Task doorWalk;
    // brew_box 2: a failed box is no open brew, the rods task waits and tries again (steering 69)
    boolean waitOnFail;

    // Tuning box_home's sealed heal (BoxHomeRules): the same box in the fight's standing pillbox with no stand and no brew.
    // the look out walled, the buddy in B waits while the food chain eats (HEAL) till BoxHomeRules.healDone, then the wall is
    // broken (one of the blocks placed here first) and the box is done with the buddy still in the pillbox: no walk out
    private final boolean heal;
    private long sealedAt;
    private long riseAt;
    private float bestHp;
    private String healWhy = "";
    // a sealed heal that failed with blocks of its own standing breaks them (OPEN) before it is over: why it failed, null
    // before. realfort-pair-home B:2 (7 Nov 2026): two of three blocks went in, the third never, and the two walled the look
    // out for the last twelve game minutes of the run; no blaze came to the opening and every trip out for a rod found no way
    private String unsealWhy;

    static BrewBoxTask heal(BlockPos spawner, int[][] pillbox) {
        return new BrewBoxTask(null, spawner, java.util.Set::of, pillbox, true);
    }

    // the log lines' name: the brew's box or the heal's
    private String what() {
        return heal ? "heal box" : "brew box";
    }

    // Tuning brew_open_lookout (on by default, a fix): a brew box in the fight's standing pillbox (brew_box 2) opens the wall it
    // placed itself first, the pillbox's look out, and is done with the buddy still in the pillbox, as the sealed heal is. it
    // broke another wall with a walk out beyond it and walked out: the look out stayed walled with cobblestone while the lure
    // ("a look round the corner", 40 rounds at the cobblestone) and the door walk still aimed at it, about 38 game minutes at
    // one rod in phase 6 (cascade realfort-p4-cream7 run 7, look 40, 10 Nov 2026)
    private boolean lookoutFirst() {
        return !heal && pillboxBack != null && adris.altoclef.util.helpers.Tuning.get("brew_open_lookout", 1) > 0;
    }

    // the doors all of whose cells were placed here (the walled look out), in their order
    private List<BrewBox.Door> oursOf(List<BrewBox.Door> all) {
        List<BrewBox.Door> ours = new ArrayList<>();
        for (BrewBox.Door d : all) {
            boolean every = true;
            for (int[] c : d.cells()) every &= placed.contains(BlazeCover.key(c[0], c[1], c[2]));
            if (every) ours.add(d);
        }
        return ours;
    }

    // still planning (no box yet)
    boolean preparing() {
        return stage == Stage.PREP;
    }

    // a failed seal breaking the blocks it placed (the rods task gives it the tick wherever the buddy is: OPEN_TICKS at most)
    boolean unsealing() {
        return stage == Stage.OPEN && unsealWhy != null;
    }

    // why the sealed heal ended (BoxHomeRules.healDone), empty before
    String healWhy() {
        return healWhy;
    }

    BrewBoxTask(BrewFireResistanceTask brew, BlockPos spawner, Supplier<Set<BlockPos>> keepClear) {
        this(brew, spawner, keepClear, null);
    }

    BrewBoxTask(BrewFireResistanceTask brew, BlockPos spawner, Supplier<Set<BlockPos>> keepClear, int[][] pillbox) {
        this(brew, spawner, keepClear, pillbox, false);
    }

    private BrewBoxTask(BrewFireResistanceTask brew, BlockPos spawner, Supplier<Set<BlockPos>> keepClear, int[][] pillbox, boolean heal) {
        this.heal = heal;
        this.brew = brew;
        this.spawner = spawner;
        this.keepClear = keepClear;
        this.pillboxBack = pillbox == null ? null : pillbox[0];
        this.pillboxDir = pillbox == null ? null : pillbox[1];
        this.pillboxDoor = pillbox == null || pillbox.length < 3 || pillbox[2] == null ? null : new BlockPos(pillbox[2][0], pillbox[2][1], pillbox[2][2]);
        // (the look out between F and the door: the door is F + 2p, so O is their middle)
        if (pillboxDoor != null) {
            int fx = pillbox[0][0] + pillbox[1][0], fz = pillbox[0][2] + pillbox[1][1];
            this.pillboxLookout = new BlockPos((fx + pillboxDoor.getX()) / 2, pillbox[0][1], (fz + pillboxDoor.getZ()) / 2);
        } else {
            this.pillboxLookout = null;
        }
    }

    // the box planned at the pillbox's cells: no spawner rule (its cells were planned hidden from the spawn area, and the buddy
    // and the stand fill them) and nothing to keep clear (they are the cells kept clear). null when they do not shut
    private BrewBox.Box pillboxPlan(CliffAvoidance.Cells world, CliffAvoidance.Cells stand) {
        if (pillboxBack == null) return null;
        return BrewBox.planAt(world, stand, pillboxBack[0], pillboxBack[1], pillboxBack[2], pillboxDir, Double.NaN, Double.NaN, null, false);
    }

    // the box is going up: the fight fights only what bites meanwhile, and lets its blocks be placed (BlazeFight.building and
    // pillboxBlocks, as for the pillbox)
    boolean building() {
        return stage == Stage.BUILD;
    }

    java.util.Set<BlockPos> blocks() {
        Set<BlockPos> s = new HashSet<>();
        if (box != null && stage == Stage.BUILD) for (int[] b : box.blocks()) s.add(pos(b));
        return s;
    }

    // after the brew, or with the brew given up inside: the door is being broken, or walked out of
    boolean opening() {
        return stage == Stage.OPEN || stage == Stage.LEAVE;
    }

    // the door being broken with the buddy still inside: the fight waits for it (CollectBlazeRodsTask). from OPEN on holds() is
    // off, and a blaze in reach cut the break off till OPEN_TICKS ran out with the door standing, piece2in-1 run 8
    boolean breakingOut(AltoClef mod) {
        return stage == Stage.OPEN && box != null && mod.getPlayer() != null && inside(mod.getPlayer().position());
    }

    // still to do: not given up, not opened
    boolean active() {
        return !failed && stage != Stage.DONE;
    }

    private boolean shutIn() {
        return stage == Stage.STEP_IN || stage == Stage.CRAFT || stage == Stage.STAND || stage == Stage.BREW || stage == Stage.DRINK
                || stage == Stage.HEAL;
    }

    // the brew was given up (its budget, BREW_BUDGET_TICKS): a shut box opens, one not yet shut is dropped
    void giveUp(String why) {
        BrewFireResistanceTask.pinBoxStand(null);
        if (shutIn()) {
            Debug.logInternal(what() + ": " + why + ", opening");
            stage = Stage.OPEN;
        } else if (!opening()) {
            stage = Stage.DONE;
        }
    }

    // the fight sits out this tick (BrewBox.holds), asked once per tick
    boolean holds(AltoClef mod) {
        // (in the pillbox, brew_box 2, its look out going up: the buddy in B or F is out of the spawn area's sight already, and
        // a fight's run from there took the buddy out of the box mid build and the way back in failed, steering 69)
        boolean buildingIn = stage == Stage.BUILD && pillboxBack != null;
        if ((!shutIn() && !buildingIn) || box == null || mod.getPlayer() == null || mod.getWorld() == null) return false;
        long now = mod.getWorld().getGameTime();
        if (now == holdsAt) return holdsWas;
        holdsAt = now;
        var me = mod.getPlayer();
        Level level = mod.getWorld();
        boolean whole = buildingIn || whole(level);
        boolean inside = inside(me.position());
        boolean hostile;
        String inRoom = "";
        try {
            var in = level.getEntities(me, room().deflate(0.05), e -> e.isAlive() && (e instanceof Enemy || EntityHelper.isGenerallyHostileToPlayer(mod, e)));
            hostile = !in.isEmpty();
            // (what it is and where, for the line: the box let the fight in for "a mob inside" in 211 of 217 boxes of an
            // evening, 12 Nov 2026, too often to be mobs in a shut two-cell box)
            if (hostile) {
                var e = in.get(0);
                inRoom = String.format(java.util.Locale.ROOT, " %s at %.1f %.1f %.1f, the buddy at %.1f %.1f %.1f",
                        BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath(), e.getX(), e.getY(), e.getZ(), me.getX(), me.getY(), me.getZ());
            }
        } catch (java.util.ConcurrentModificationException e) {
            hostile = true;
        }
        boolean fire = fireInside(level);
        boolean was = holdsWas;
        holdsWas = BrewBox.holds(whole, inside, hostile, fire, me.getHealth());
        String why = !whole ? "the box is broken" : !inside ? "the buddy is out of it" : hostile ? "a mob inside:" + inRoom : fire ? "fire inside"
                : !holdsWas ? String.format(java.util.Locale.ROOT, "%.1f hp", me.getHealth()) : "";
        // (once per change: how often the box let the fight back in, and why)
        if (was && !holdsWas && !why.equals(holdsWhy)) Debug.logInternal(what() + ": open to the fight (" + why + ")");
        holdsWhy = why;
        return holdsWas;
    }

    // a hit taken inside the box, every tick the rods task runs: the game hands a fresh source object per hit
    void noteHits(AltoClef mod) {
        var me = mod.getPlayer();
        if (me == null || box == null) return;
        DamageSource s = me.getLastDamageSource();
        boolean in = (shutIn() || opening()) && inside(me.position());
        if (in && s != null && s != lastHit && me.hurtTime > 0) {
            var by = s.getEntity();
            Debug.logInternal(what() + ": hit (" + s.getMsgId() + (by == null ? "" : " by " + BuiltInRegistries.ENTITY_TYPE.getKey(by.getType()).getPath()) + ")");
        }
        if (s != null) lastHit = s;
    }

    @Override
    protected void onStart(AltoClef mod) {
    }

    @Override
    protected Task onTick(AltoClef mod) {
        long now = mod.getWorld().getGameTime();
        Level level = mod.getWorld();
        tickLevel = level;
        switch (stage) {
            case PREP -> {
                // (no stand made out here: the box first, the stand at a table inside it, CRAFT. made before the box, its
                // table went down in the open where the station placer liked, steering 62 and 69)
                CliffAvoidance.Cells stand = cells(mod);
                if (stand == null) return null;
                BlockPos at = mod.getPlayer().blockPosition();
                BrewBox.Box plan;
                if (pillboxBack != null) {
                    plan = pillboxPlan(worldCells(level), stand);
                    if (plan == null) return fail("the pillbox does not shut: " + BrewBox.why);
                } else {
                    plan = BrewBox.plan(worldCells(level), stand, new int[]{at.getX(), at.getY(), at.getZ()}, eyes(level), sx(), sz(), avoid());
                }
                if (plan == null) return fail("no floor: no two cells to shut within " + BrewBox.WALK + " steps");
                if (!enoughBlocks(mod, plan)) return fail("no blocks: " + plan.blocks().size() + " to place, " + BlazePillboxTask.blocksInBag(mod) + " in the bag");
                buildStartedAt = now;
                startTry(plan);
                Debug.logInternal(what() + ": planned, the buddy's cell " + pos(plan.back()).toShortString() + ", the stand's "
                        + pos(plan.front()).toShortString() + ", " + plan.blocks().size() + " blocks" + (pillboxBack != null ? ", in the pillbox" : "")
                        + (spawner == null ? "" : String.format(java.util.Locale.ROOT, ", %.1f from the spawner", Math.hypot(plan.back()[0] - spawner.getX(), plan.back()[2] - spawner.getZ()))));
                return null;
            }
            case BUILD -> {
                // (no shield while it goes up: a raised shield holds the use key the clicks need, as for the pillbox)
                BlazeShelter.building(now);
                boolean over = ++tryTicks > TRY_TICKS;
                if (builder.isFinished(mod) || over) {
                    if (whole(level)) {
                        stage = Stage.STEP_IN;
                        stepTicks = 0;
                        lastHit = mod.getPlayer().getLastDamageSource();
                        Debug.logInternal(what() + ": built (" + placed.size() + " blocks, " + (now - buildStartedAt) + " ticks)");
                        return null;
                    }
                    String why = over ? "not whole in " + TRY_TICKS + " ticks" : builder.gaveUp() ? "its build gave up" : open(level) + " cells still open";
                    if (tries >= TRIES) return fail(TRIES + " tries, " + why);
                    // again: the same cells with what is missing, else another site
                    CliffAvoidance.Cells stand = cells(mod);
                    if (stand == null) return null;
                    CliffAvoidance.Cells world = worldCells(level);
                    BrewBox.Box again = pillboxBack != null ? pillboxPlan(world, stand)
                            : BrewBox.planAt(world, stand, box.back()[0], box.back()[1], box.back()[2], box.dir(), sx(), sz(), avoid());
                    if (again == null && pillboxBack == null) {
                        BlockPos at = mod.getPlayer().blockPosition();
                        again = BrewBox.plan(world, stand, new int[]{at.getX(), at.getY(), at.getZ()}, eyes(level), sx(), sz(), avoid());
                    }
                    if (again == null) return fail("no floor for try " + (tries + 1) + ", " + why);
                    if (!enoughBlocks(mod, again)) return fail("no blocks for try " + (tries + 1) + ": " + again.blocks().size() + " to place, " + BlazePillboxTask.blocksInBag(mod) + " in the bag");
                    Debug.logInternal(what() + ": try " + (tries + 1) + " (" + why + "), " + again.blocks().size() + " blocks to place");
                    startTry(again);
                    return null;
                }
                // (in the pillbox: from outside it round to its open side first, never at the cell through its walls)
                BlockPos me = mod.getPlayer().blockPosition();
                if (pillboxDoor != null && !inPillbox(me) && !(me.getX() == pillboxDoor.getX() && me.getZ() == pillboxDoor.getZ())) {
                    if (doorWalk == null || doorWalk.isFinished(mod)) doorWalk = new GetToBlockTask(pillboxDoor, false);
                    setDebugState("Round to the pillbox's open side for the brew box");
                    return doorWalk;
                }
                setDebugState("Building the brew box, try " + tries + " of " + TRIES);
                return builder;
            }
            case STEP_IN -> {
                if (!inside(mod.getPlayer().position())) return fail("shut out of the box");
                // (from F, where the roof row was placed, to B's middle: the stand goes where the buddy stood)
                if (++stepTicks <= STEP_TICKS && BlazePillboxTask.centreIn(mod, pos(box.back()), BlazePillboxTask.HIDE_SLACK)) {
                    setDebugState("Into the brew box's back cell");
                    return null;
                }
                // (the sealed heal: the wait in B, HEAL)
                if (heal) {
                    stage = Stage.HEAL;
                    sealedAt = now;
                    riseAt = now;
                    bestHp = mod.getPlayer().getHealth();
                    Debug.logInternal(String.format(java.util.Locale.ROOT, "heal box: sealed, the buddy in the back cell %s at %.1f hp",
                            pos(box.back()).toShortString(), bestHp));
                    return null;
                }
                // (brew_box 2 with no stand yet: made in here, CRAFT)
                // (steering 69: the stand made in here for every box, never at a table outside it)
                stage = !mod.getItemStorage().hasItem(Items.BREWING_STAND) ? Stage.CRAFT : Stage.STAND;
                standTicks = 0;
                placeStand = null;
                craftTicks = 0;
                placeTable = null;
                takeTable = null;
                return null;
            }
            case CRAFT -> {
                // (steering 62: the station inside the shut box with the buddy, never the buddy outside by it. the table goes in F
                // beside B, the stand is made at it, the table comes back into the bag and the stand takes F. a table set down
                // where the station placer liked stood in the pillbox's corner with the buddy outside it, piece2box-3)
                BlockPos f = pos(box.front());
                boolean tableInF = level.getBlockState(f).is(Blocks.CRAFTING_TABLE);
                if (++craftTicks > CRAFT_TICKS) {
                    Debug.logInternal(what() + ": the stand not made inside in " + CRAFT_TICKS / 20 + " s, opening");
                    stage = Stage.OPEN;
                    return null;
                }
                if (mod.getItemStorage().hasItem(Items.BREWING_STAND)) {
                    if (tableInF) {
                        if (takeTable == null) takeTable = new DestroyBlockTask(f);
                        setDebugState("Taking the crafting table back");
                        return takeTable;
                    }
                    Debug.logInternal(what() + ": the stand made inside (" + craftTicks + " ticks)");
                    stage = Stage.STAND;
                    return null;
                }
                if (!tableInF) {
                    if (!mod.getItemStorage().hasItem(Items.CRAFTING_TABLE)) {
                        setDebugState("A crafting table for inside the box");
                        return TaskCatalogue.getItemTask("crafting_table", 1);
                    }
                    // (in B's middle first, as for the stand: a body over the line into F and the game refuses the block)
                    if (BlazePillboxTask.centreIn(mod, pos(box.back()), BlazePillboxTask.HIDE_SLACK)) return null;
                    if (placeTable == null) placeTable = new InteractWithBlockTask(new ItemTarget(Items.CRAFTING_TABLE, 1), Direction.UP, f.below(), false).stayPut();
                    setDebugState("Putting the crafting table in the box");
                    return placeTable;
                }
                setDebugState("Making the brewing stand inside the box");
                return TaskCatalogue.getItemTask("brewing_stand", 1);
            }
            case STAND -> {
                BlockPos f = pos(box.front());
                if (level.getBlockState(f).is(Blocks.BREWING_STAND)) {
                    BrewFireResistanceTask.pinBoxStand(f);
                    stage = Stage.BREW;
                    return null;
                }
                // (no stand in F and none in the bag: the brew would make one shut in, a rod for it from inside the box, no path
                // and bans until its budget ran out. so open now; the brew goes on outside, or the order does)
                if (!mod.getItemStorage().hasItem(Items.BREWING_STAND)) {
                    Debug.logInternal(what() + ": no stand, opening");
                    BrewFireResistanceTask.pinBoxStand(null);
                    stage = Stage.OPEN;
                    return null;
                }
                if (++standTicks > STAND_TICKS) {
                    Debug.logInternal(what() + ": the stand did not go in at " + f.toShortString() + ", the brew places its own");
                    stage = Stage.BREW;
                    return null;
                }
                // (in B's middle first: a body over the line into F and the game refuses the block)
                if (BlazePillboxTask.centreIn(mod, pos(box.back()), BlazePillboxTask.HIDE_SLACK)) return null;
                if (placeStand == null) placeStand = new InteractWithBlockTask(new ItemTarget(Items.BREWING_STAND, 1), Direction.UP, f.below(), false).stayPut();
                setDebugState("Putting the brewing stand in the box");
                return placeStand;
            }
            case BREW -> {
                if (brew.isFinished(mod)) {
                    BrewFireResistanceTask.pinBoxStand(null);
                    stage = Stage.DRINK;
                    drinkSince = now;
                    return null;
                }
                // (pinned again each tick: an interruption unpins it, see onStop)
                BlockPos f = pos(box.front());
                if (level.getBlockState(f).is(Blocks.BREWING_STAND)) BrewFireResistanceTask.pinBoxStand(f);
                setDebugState("Brewing walled in");
                return brew;
            }
            case DRINK -> {
                var me = mod.getPlayer();
                if (me.hasEffect(MobEffects.FIRE_RESISTANCE) || (BrewFireResistanceTask.fireRes(mod) == 0 && !me.isUsingItem())) {
                    stage = Stage.OPEN;
                    return null;
                }
                if (now - drinkSince > DRINK_TICKS || now < drinkSince) {
                    Debug.logInternal(what() + ": the potion not drunk in " + DRINK_TICKS / 20 + " s, opening");
                    stage = Stage.OPEN;
                    return null;
                }
                // (the food chain drinks with no blaze near or fire on us too while this is fresh, FireRes.ask)
                FireRes.ask(now);
                setDebugState("Drinking fire resistance in the box");
                return null;
            }
            case HEAL -> {
                var me = mod.getPlayer();
                float hp = me.getHealth();
                if (hp > bestHp + 0.01f) {
                    bestHp = hp;
                    riseAt = now;
                }
                String why = adris.altoclef.util.helpers.BoxHomeRules.healDone(hp, me.getMaxHealth(), now - sealedAt, now - riseAt);
                if (why != null || now < sealedAt) {
                    healWhy = why == null ? "a new world" : why;
                    Debug.logInternal(String.format(java.util.Locale.ROOT, "heal box: %s, %.1f hp after %d s, opening", healWhy, hp, Math.max(0, now - sealedAt) / 20));
                    stage = Stage.OPEN;
                    return null;
                }
                // (in B's middle, out of the look out's line)
                if (BlazePillboxTask.centreIn(mod, pos(box.back()), BlazePillboxTask.HIDE_SLACK)) return null;
                setDebugState("Healing sealed in the box");
                return null;
            }
            case OPEN -> {
                if (doors == null) {
                    // (brew_open_lookout: in the fight's pillbox the stand in F is taken down first, so that F's front wall,
                    // the pillbox's look out, is a door again: with the stand in F only B's three walls were, and the box
                    // broke one of them and left the look out walled, look 40. not down in OPEN_TICKS: a wall as before)
                    if (lookoutFirst() && level.getBlockState(pos(box.front())).is(Blocks.BREWING_STAND) && !standGaveUp) {
                        if (standSince == Long.MIN_VALUE || now < standSince) standSince = now;
                        if (now - standSince <= OPEN_TICKS) {
                            if (breaking == null || !pos(box.front()).equals(breakingAt)) {
                                breaking = new DestroyBlockTask(pos(box.front()));
                                breakingAt = pos(box.front());
                            }
                            setDebugState("Taking the stand down for the look out");
                            return breaking;
                        }
                        standGaveUp = true;
                        Debug.logInternal("brew box: the stand not taken down in " + OPEN_TICKS / 20 + " s, a wall of B as before");
                    }
                    if (lookoutFirst() && standSince != Long.MIN_VALUE && !standGaveUp) {
                        Debug.logInternal("brew box: the stand taken down from " + pos(box.front()).toShortString() + " for the look out");
                    }
                    breaking = null;
                    // (F's walls are doors too with no stand in F)
                    boolean standInF = level.getBlockState(pos(box.front())).is(Blocks.BREWING_STAND);
                    CliffAvoidance.Cells world = worldCells(level);
                    CliffAvoidance.Cells walk = cells(mod);
                    doors = BrewBox.doors(world, walk == null ? world : walk, box, placed, !standInF, sx(), sz());
                    // (the sealed heal opens its own wall again, the look out: a door all of whose blocks were placed here)
                    if (heal) {
                        List<BrewBox.Door> ours = oursOf(doors);
                        if (!ours.isEmpty()) doors = ours;
                    } else if (lookoutFirst()) {
                        // (the brew in the fight's pillbox: its own wall first, the other walls kept behind it for a look out
                        // that will not open)
                        List<BrewBox.Door> ours = oursOf(doors);
                        if (!ours.isEmpty()) {
                            // (the pillbox's own look out column first when its door is known: a wall the box put back
                            // elsewhere is no look out)
                            if (pillboxLookout != null) {
                                ours.sort(java.util.Comparator.comparingInt(d -> d.cells().get(0)[0] == pillboxLookout.getX()
                                        && d.cells().get(0)[2] == pillboxLookout.getZ() ? 0 : 1));
                            }
                            List<BrewBox.Door> first = new ArrayList<>(ours);
                            for (BrewBox.Door d : doors) if (!ours.contains(d)) first.add(d);
                            doors = first;
                        }
                    }
                    doorAt = 0;
                    openSince = now;
                }
                BrewBox.Door door = doors.get(doorAt);
                for (int[] d : door.cells()) {
                    BlockPos p = pos(d);
                    if (!solid(level, p)) continue;
                    if (now - openSince > OPEN_TICKS || now < openSince) {
                        Debug.logInternal(what() + ": not opened in " + OPEN_TICKS / 20 + " s, the walk on digs out");
                        if (unsealWhy != null) failed = true;
                        stage = Stage.DONE;
                        return null;
                    }
                    if (breaking == null || !p.equals(breakingAt)) {
                        breaking = new DestroyBlockTask(p);
                        breakingAt = p;
                    }
                    setDebugState("Opening the brew box");
                    return breaking;
                }
                // (the sealed heal: open, and the buddy stays in the pillbox)
                if (heal) {
                    Debug.logInternal("heal box: opened at " + pos(door.cells().get(0)).toShortString() + (unsealWhy != null ? " after the failed seal" : ""));
                    if (unsealWhy != null) failed = true;
                    stage = Stage.DONE;
                    return null;
                }
                // (the brew in the fight's pillbox with its own wall open again: the pillbox stands as planned, look out and
                // door, and the buddy stays in it; the rods task's fight and walks go on from there)
                if (lookoutFirst() && oursOf(List.of(door)).size() == 1) {
                    Debug.logInternal("brew box: the pillbox's look out opened again at " + pos(door.cells().get(0)).toShortString() + ", the buddy stays in the pillbox");
                    stage = Stage.DONE;
                    return null;
                }
                // (a door with no walk out read beyond it, none with one left: the order's own walk takes it from here)
                if (!door.out()) {
                    Debug.logInternal(what() + ": opened at " + pos(door.cells().get(0)).toShortString() + ", no walk out seen beyond it");
                    stage = Stage.DONE;
                    return null;
                }
                Debug.logInternal(what() + ": opened at " + pos(door.cells().get(0)).toShortString());
                stage = Stage.LEAVE;
                leaveTicks = 0;
                noPathTicks = 0;
                leaving = null;
                return null;
            }
            case LEAVE -> {
                BrewBox.Door door = doors.get(doorAt);
                if (!inRoom(mod.getPlayer().position())) {
                    Debug.logInternal(what() + ": out at " + mod.getPlayer().blockPosition().toShortString());
                    stage = Stage.DONE;
                    return null;
                }
                // (the walk out gets nowhere: no path for a while, or still inside after LEAVE_TICKS. the next wall then, till
                // all are tried; the door that led nowhere kept the buddy in the box twelve game minutes)
                noPathTicks = mod.getClientBaritone().getPathingBehavior().isPathing() ? 0 : noPathTicks + 1;
                if (++leaveTicks > LEAVE_TICKS || noPathTicks > NO_PATH_TICKS) {
                    String why = noPathTicks > NO_PATH_TICKS ? "no path in " + NO_PATH_TICKS / 20 + " s" : "still inside after " + LEAVE_TICKS / 20 + " s";
                    if (doorAt + 1 >= doors.size()) {
                        Debug.logInternal(what() + ": no way out at " + pos(door.cells().get(0)).toShortString() + " (" + why + "), every wall tried, the walk on digs out");
                        stage = Stage.DONE;
                        return null;
                    }
                    doorAt++;
                    Debug.logInternal(what() + ": no way out at " + pos(door.cells().get(0)).toShortString() + " (" + why + "), the next wall at "
                            + pos(doors.get(doorAt).cells().get(0)).toShortString());
                    stage = Stage.OPEN;
                    openSince = now;
                    breaking = null;
                    leaving = null;
                    return null;
                }
                if (leaving == null) leaving = new GetToBlockTask(pos(door.beyond()));
                setDebugState("Out of the brew box");
                return leaving;
            }
            default -> {
                return null;
            }
        }
    }

    private Task fail(String why) {
        BrewFireResistanceTask.pinBoxStand(null);
        // (the sealed heal: the blocks it placed that stand are broken first, so the look out is open again; the rods task
        // retries the seal after its wait)
        if (heal && unsealWhy == null && tickLevel != null) {
            List<int[]> standing = new ArrayList<>();
            for (BlockPos p : placedAt) if (solid(tickLevel, p)) standing.add(new int[]{p.getX(), p.getY(), p.getZ()});
            if (!standing.isEmpty()) {
                unsealWhy = why;
                Debug.logInternal(what() + ": failed (" + why + "), opening the " + standing.size() + " blocks it placed");
                doors = List.of(new BrewBox.Door(standing, null, false));
                doorAt = 0;
                openSince = tickLevel.getGameTime();
                breaking = null;
                breakingAt = null;
                stage = Stage.OPEN;
                return null;
            }
        }
        failed = true;
        stage = Stage.DONE;
        Debug.logInternal(what() + ": failed (" + why + ")" + (heal ? ", no seal" : pillboxBack != null || waitOnFail ? ", the brew waits" : ", the open brew"));
        return null;
    }

    private void startTry(BrewBox.Box plan) {
        box = plan;
        adris.altoclef.util.snapshot.Snapshots.chose("brewbox", plan.back()).with("front", plan.front()).blocks(plan.blocks());
        for (int[] b : plan.blocks()) {
            placed.add(BlazeCover.key(b[0], b[1], b[2]));
            placedAt.add(pos(b));
        }
        builder = new BlazePillboxTask(plan.build(), false);
        tries++;
        tryTicks = 0;
        stage = Stage.BUILD;
    }

    private static boolean enoughBlocks(AltoClef mod, BrewBox.Box plan) {
        return BlazePillboxTask.blocksInBag(mod) >= plan.blocks().size();
    }

    // the world as the shell is judged (whole): a block's collision is solid, lava and water are fluids, the rest open (fire too:
    // a block goes in over it). an unloaded chunk is solid, as CliffAvoidance.of reads it
    private static CliffAvoidance.Cells worldCells(Level level) {
        return (x, y, z) -> {
            BlockPos p = new BlockPos(x, y, z);
            if (!level.isLoaded(p)) return CliffAvoidance.Cell.SOLID;
            var state = level.getBlockState(p);
            var fluid = state.getFluidState();
            if (fluid.is(net.minecraft.tags.FluidTags.LAVA)) return CliffAvoidance.Cell.LAVA;
            if (fluid.is(net.minecraft.tags.FluidTags.WATER)) return CliffAvoidance.Cell.WATER;
            return state.getCollisionShape(level, p).isEmpty() ? CliffAvoidance.Cell.OPEN : CliffAvoidance.Cell.SOLID;
        };
    }

    // where to stand, as Baritone reads it (fire and the cells a task keeps the walk off are no cells to stand in)
    private static CliffAvoidance.Cells cells(AltoClef mod) {
        var ctx = mod.getClientBaritone().getPlayerContext();
        if (ctx == null) return null;
        // (a fresh look at the world: Baritone's bsi is the last path search's copy)
        return CliffAvoidance.of(new baritone.utils.BlockStateInterface(ctx, true));
    }

    private double sx() {
        return spawner == null ? Double.NaN : spawner.getX();
    }

    private double sz() {
        return spawner == null ? Double.NaN : spawner.getZ();
    }

    // the spawn area's eyes in open cells (as the pillbox's plan), none with no spawner
    private List<Vec3> eyes(Level level) {
        List<Vec3> out = new ArrayList<>();
        if (spawner == null) return out;
        for (Vec3 eye : BlazeCover.spawnEyes(spawner.getX(), spawner.getY(), spawner.getZ())) {
            BlockPos p = BlockPos.containing(eye);
            if (level.getBlockState(p).getCollisionShape(level, p).isEmpty()) out.add(eye);
        }
        return out;
    }

    private Set<Long> avoid() {
        Set<Long> out = new HashSet<>();
        Set<BlockPos> keep = keepClear == null ? null : keepClear.get();
        if (keep != null) for (BlockPos p : keep) out.add(BlazeCover.key(p.getX(), p.getY(), p.getZ()));
        return out;
    }

    private boolean whole(Level level) {
        return open(level) == 0;
    }

    // the shell's cells with nothing solid in them
    private int open(Level level) {
        int n = 0;
        for (int[] s : box.shell()) if (!solid(level, pos(s))) n++;
        return n;
    }

    private boolean fireInside(Level level) {
        for (int[] c : new int[][]{box.back(), box.front()}) {
            for (int k = 0; k <= 1; k++) {
                if (level.getBlockState(new BlockPos(c[0], c[1] + k, c[2])).getBlock() instanceof BaseFireBlock) return true;
            }
        }
        return false;
    }

    // the two cells' room, floor to roof
    private AABB room() {
        int[] b = box.back(), f = box.front();
        return new AABB(Math.min(b[0], f[0]), b[1], Math.min(b[2], f[2]), Math.max(b[0], f[0]) + 1, b[1] + 2, Math.max(b[2], f[2]) + 1);
    }

    // the feet over one of the two cells, at any height: a jump inside is still in, the walk out ends past the walls
    private boolean inRoom(Vec3 at) {
        if (box == null) return false;
        AABB r = room();
        return at.x > r.minX && at.x < r.maxX && at.z > r.minZ && at.z < r.maxZ;
    }

    // the feet in one of the two cells
    // the brew begun outside the pillbox it is to be walled in: the walk in comes before the fight (piece2side-1 run 3: the
    // buddy fought in the opening to 0.1 hp while the box waited for the fight to let go)
    static final double GO_IN_RANGE = 10;

    boolean goingIn(AltoClef mod) {
        if (stage != Stage.BUILD || pillboxBack == null || pillboxDoor == null || mod.getPlayer() == null) return false;
        BlockPos me = mod.getPlayer().blockPosition();
        if (inPillbox(me)) return false;
        return Math.sqrt(me.distSqr(pillboxDoor)) <= GO_IN_RANGE;
    }

    // over B, F or the look out of the pillbox this box is made of, from under the floor to the roof
    private boolean inPillbox(BlockPos at) {
        if (pillboxBack == null) return false;
        BlockPos b = new BlockPos(pillboxBack[0], pillboxBack[1], pillboxBack[2]);
        BlockPos f = b.offset(pillboxDir[0], 0, pillboxDir[1]);
        for (BlockPos c : new BlockPos[]{b, f, pillboxLookout}) {
            if (c != null && c.getX() == at.getX() && c.getZ() == at.getZ() && at.getY() >= c.getY() - 1 && at.getY() <= c.getY() + 2) return true;
        }
        return false;
    }

    private boolean inside(Vec3 at) {
        if (box == null) return false;
        AABB r = room();
        return at.x > r.minX && at.x < r.maxX && at.z > r.minZ && at.z < r.maxZ && at.y > r.minY - 0.5 && at.y < r.minY + 1;
    }

    private static boolean solid(Level level, BlockPos p) {
        return !level.getBlockState(p).getCollisionShape(level, p).isEmpty();
    }

    private static BlockPos pos(int[] c) {
        return new BlockPos(c[0], c[1], c[2]);
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        return failed || stage == Stage.DONE;
    }

    // failed: the open brew goes on (the rods task's brewStep)
    boolean failed() {
        return failed;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        // (a stand pinned only while this runs: one left pinned would draw a later brew back to it from anywhere)
        BrewFireResistanceTask.pinBoxStand(null);
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof BrewBoxTask;
    }

    @Override
    protected String toDebugString() {
        return "Brewing walled in (" + stage.name().toLowerCase() + ")";
    }
}
