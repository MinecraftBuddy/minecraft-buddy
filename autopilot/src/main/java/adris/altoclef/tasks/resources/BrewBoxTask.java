package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.InteractWithBlockTask;
import adris.altoclef.tasks.construction.DestroyBlockTask;
import adris.altoclef.tasks.container.BrewFireResistanceTask;
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
// inside, then B's wall is broken and the order goes on. no box (no floor, no blocks, three tries): the open brew as before
final class BrewBoxTask extends Task {
    enum Stage { PREP, BUILD, STEP_IN, STAND, BREW, DRINK, OPEN, DONE }

    // a box not whole after this many builds is given up
    static final int TRIES = 3;
    // the ticks one build may run (the ticks it had the wheel: a fight in between does not count)
    static final long TRY_TICKS = 1200;
    // the step into B and the stand's click, at most (ticks it ran)
    static final long STEP_TICKS = 100;
    static final long STAND_TICKS = 100;
    // the drink asked for this long at most, and the door broken open in this long at most (game ticks)
    static final long DRINK_TICKS = 200;
    static final long OPEN_TICKS = 400;

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
    // every cell a plan put a block in, BlazeCover.key (the door is one of ours)
    private final Set<Long> placed = new HashSet<>();
    private boolean failed;
    private long stepTicks;
    private long standTicks;
    private Task placeStand;
    private long drinkSince;
    private List<int[]> door;
    private long openSince;
    private Task breaking;
    private BlockPos breakingAt;
    private DamageSource lastHit;
    private long holdsAt = Long.MIN_VALUE;
    private boolean holdsWas;
    private String holdsWhy = "";

    BrewBoxTask(BrewFireResistanceTask brew, BlockPos spawner, Supplier<Set<BlockPos>> keepClear) {
        this.brew = brew;
        this.spawner = spawner;
        this.keepClear = keepClear;
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

    // after the brew, or with the brew given up inside: the door is being broken
    boolean opening() {
        return stage == Stage.OPEN;
    }

    // still to do: not given up, not opened
    boolean active() {
        return !failed && stage != Stage.DONE;
    }

    private boolean shutIn() {
        return stage == Stage.STEP_IN || stage == Stage.STAND || stage == Stage.BREW || stage == Stage.DRINK;
    }

    // the brew was given up (its budget, BREW_BUDGET_TICKS): a shut box opens, one not yet shut is dropped
    void giveUp(String why) {
        BrewFireResistanceTask.pinBoxStand(null);
        if (shutIn()) {
            Debug.logInternal("brew box: " + why + ", opening");
            stage = Stage.OPEN;
        } else if (stage != Stage.OPEN) {
            stage = Stage.DONE;
        }
    }

    // the fight sits out this tick (BrewBox.holds), asked once per tick
    boolean holds(AltoClef mod) {
        if (!shutIn() || box == null || mod.getPlayer() == null || mod.getWorld() == null) return false;
        long now = mod.getWorld().getGameTime();
        if (now == holdsAt) return holdsWas;
        holdsAt = now;
        var me = mod.getPlayer();
        Level level = mod.getWorld();
        boolean whole = whole(level);
        boolean inside = inside(me.position());
        boolean hostile;
        try {
            hostile = !level.getEntities(me, room().deflate(0.05), e -> e.isAlive() && (e instanceof Enemy || EntityHelper.isGenerallyHostileToPlayer(mod, e))).isEmpty();
        } catch (java.util.ConcurrentModificationException e) {
            hostile = true;
        }
        boolean fire = fireInside(level);
        boolean was = holdsWas;
        holdsWas = BrewBox.holds(whole, inside, hostile, fire, me.getHealth());
        String why = !whole ? "the box is broken" : !inside ? "the buddy is out of it" : hostile ? "a mob inside" : fire ? "fire inside"
                : !holdsWas ? String.format(java.util.Locale.ROOT, "%.1f hp", me.getHealth()) : "";
        // (once per change: how often the box let the fight back in, and why)
        if (was && !holdsWas && !why.equals(holdsWhy)) Debug.logInternal("brew box: open to the fight (" + why + ")");
        holdsWhy = why;
        return holdsWas;
    }

    // a hit taken inside the box, every tick the rods task runs: the game hands a fresh source object per hit
    void noteHits(AltoClef mod) {
        var me = mod.getPlayer();
        if (me == null || box == null) return;
        DamageSource s = me.getLastDamageSource();
        boolean in = (shutIn() || stage == Stage.OPEN) && inside(me.position());
        if (in && s != null && s != lastHit && me.hurtTime > 0) {
            var by = s.getEntity();
            Debug.logInternal("brew box: hit (" + s.getMsgId() + (by == null ? "" : " by " + BuiltInRegistries.ENTITY_TYPE.getKey(by.getType()).getPath()) + ")");
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
        switch (stage) {
            case PREP -> {
                // (the stand from the bag: made inside, its table would want F)
                if (!mod.getItemStorage().hasItem(Items.BREWING_STAND)) {
                    setDebugState("Making the brewing stand before the box");
                    return TaskCatalogue.getItemTask("brewing_stand", 1);
                }
                CliffAvoidance.Cells stand = cells(mod);
                if (stand == null) return null;
                BlockPos at = mod.getPlayer().blockPosition();
                BrewBox.Box plan = BrewBox.plan(worldCells(level), stand, new int[]{at.getX(), at.getY(), at.getZ()}, eyes(level), sx(), sz(), avoid());
                if (plan == null) return fail("no floor: no two cells to shut within " + BrewBox.WALK + " steps");
                if (!enoughBlocks(mod, plan)) return fail("no blocks: " + plan.blocks().size() + " to place, " + BlazePillboxTask.blocksInBag(mod) + " in the bag");
                buildStartedAt = now;
                startTry(plan);
                Debug.logInternal("brew box: planned, the buddy's cell " + pos(plan.back()).toShortString() + ", the stand's "
                        + pos(plan.front()).toShortString() + ", " + plan.blocks().size() + " blocks"
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
                        Debug.logInternal("brew box: built (" + placed.size() + " blocks, " + (now - buildStartedAt) + " ticks)");
                        return null;
                    }
                    String why = over ? "not whole in " + TRY_TICKS + " ticks" : builder.gaveUp() ? "its build gave up" : open(level) + " cells still open";
                    if (tries >= TRIES) return fail(TRIES + " tries, " + why);
                    // again: the same cells with what is missing, else another site
                    CliffAvoidance.Cells stand = cells(mod);
                    if (stand == null) return null;
                    CliffAvoidance.Cells world = worldCells(level);
                    BrewBox.Box again = BrewBox.planAt(world, stand, box.back()[0], box.back()[1], box.back()[2], box.dir(), sx(), sz(), avoid());
                    if (again == null) {
                        BlockPos at = mod.getPlayer().blockPosition();
                        again = BrewBox.plan(world, stand, new int[]{at.getX(), at.getY(), at.getZ()}, eyes(level), sx(), sz(), avoid());
                    }
                    if (again == null) return fail("no floor for try " + (tries + 1) + ", " + why);
                    if (!enoughBlocks(mod, again)) return fail("no blocks for try " + (tries + 1) + ": " + again.blocks().size() + " to place, " + BlazePillboxTask.blocksInBag(mod) + " in the bag");
                    Debug.logInternal("brew box: try " + (tries + 1) + " (" + why + "), " + again.blocks().size() + " blocks to place");
                    startTry(again);
                    return null;
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
                stage = Stage.STAND;
                standTicks = 0;
                placeStand = null;
                return null;
            }
            case STAND -> {
                BlockPos f = pos(box.front());
                if (level.getBlockState(f).is(Blocks.BREWING_STAND)) {
                    BrewFireResistanceTask.pinBoxStand(f);
                    stage = Stage.BREW;
                    return null;
                }
                if (!mod.getItemStorage().hasItem(Items.BREWING_STAND) || ++standTicks > STAND_TICKS) {
                    Debug.logInternal("brew box: the stand did not go in at " + f.toShortString() + ", the brew places its own");
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
                    Debug.logInternal("brew box: the potion not drunk in " + DRINK_TICKS / 20 + " s, opening");
                    stage = Stage.OPEN;
                    return null;
                }
                // (the food chain drinks with no blaze near or fire on us too while this is fresh, FireRes.ask)
                FireRes.ask(now);
                setDebugState("Drinking fire resistance in the box");
                return null;
            }
            case OPEN -> {
                if (door == null) {
                    door = BrewBox.door(worldCells(level), box, placed, sx(), sz());
                    openSince = now;
                }
                for (int[] d : door) {
                    BlockPos p = pos(d);
                    if (!solid(level, p)) continue;
                    if (now - openSince > OPEN_TICKS || now < openSince) {
                        Debug.logInternal("brew box: not opened in " + OPEN_TICKS / 20 + " s, the walk on digs out");
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
                Debug.logInternal("brew box: opened at " + pos(door.get(0)).toShortString());
                stage = Stage.DONE;
                return null;
            }
            default -> {
                return null;
            }
        }
    }

    private Task fail(String why) {
        failed = true;
        stage = Stage.DONE;
        BrewFireResistanceTask.pinBoxStand(null);
        Debug.logInternal("brew box: failed (" + why + "), the open brew");
        return null;
    }

    private void startTry(BrewBox.Box plan) {
        box = plan;
        for (int[] b : plan.blocks()) placed.add(BlazeCover.key(b[0], b[1], b[2]));
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

    // the feet in one of the two cells
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
