package adris.altoclef.tasks.speedrun.gamer.tasks;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasks.construction.DestroyBlockTask;
import adris.altoclef.tasks.entity.AbstractKillEntityTask;
import adris.altoclef.tasks.movement.GetToBlockTask;
import adris.altoclef.tasks.resources.BlazePillboxTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.EntityHelper;
import adris.altoclef.util.helpers.WitherGapRules;
import adris.altoclef.util.helpers.WitherGapRules.Cell;
import baritone.behavior.CombatAim;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.skeleton.WitherSkeleton;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

// the buddy's fork, Tuning wither_gap (off): into a cell no wither skeleton can enter (WitherGapRules has the rule and the
// plan), dug or roofed first when the plan says so, and the swing from there at whatever comes to its mouth. it never decides
// when the gap is over: CombatCommit does, from what the brain reads off this task (inCell, everIn, failed)
public final class WitherGapTask extends Task {
    // out of the cell this long after being in it (knocked out, pushed): the cell is given up
    private static final long OUT_TICKS = 100;
    // the cell is read again by the rule this often while the buddy is in it: a block broken or lava come makes it no cell
    private static final long CHECK_TICKS = 20;
    // a mob farther than this is not looked at for a swing (the eye to its box is asked after)
    private static final double SWING_LOOK = 4.5;
    private static final Item[] PICKAXES = {Items.NETHERITE_PICKAXE, Items.DIAMOND_PICKAXE, Items.IRON_PICKAXE, Items.STONE_PICKAXE,
            Items.GOLDEN_PICKAXE, Items.WOODEN_PICKAXE};

    private final WitherGapRules.Plan plan;
    private final BlockPos cell;
    private Task walk;
    private Task dig;
    private BlockPos digging;
    private Task place;
    private BlockPos placing;
    private boolean inCell;
    private boolean everIn;
    private String failed;
    private long startedAt = Long.MIN_VALUE;
    private long outSince = -1;
    private long checkedAt = Long.MIN_VALUE / 2;
    // the wither skeletons swung at, for the line when one dies
    private final Set<Integer> hit = new LinkedHashSet<>();
    private int swings;
    private String hud = "Into a gap wither skeletons cannot enter";

    public WitherGapTask(WitherGapRules.Plan plan) {
        this.plan = plan;
        this.cell = new BlockPos(plan.x(), plan.y(), plan.z());
    }

    public boolean inCell() {
        return inCell;
    }

    public boolean everIn() {
        return everIn;
    }

    // why the cell was given up, null while it has not been
    public String failed() {
        return failed;
    }

    private void fail(String reason) {
        failed = reason;
        inCell = false;
    }

    @Override
    protected void onStart(AltoClef mod) {
        // (runs again after every interrupt: the clocks are the task's, the commitment holds the gap's own)
        if (startedAt == Long.MIN_VALUE) startedAt = mod.getWorld().getGameTime();
    }

    @Override
    protected Task onTick(AltoClef mod) {
        if (failed != null) return null;
        LocalPlayer player = mod.getPlayer();
        Level level = mod.getWorld();
        long now = level.getGameTime();
        noteDeaths(mod, player);
        // the tunnel first, dug from the plan's stand (DestroyBlockTask walks to reach each block)
        for (int[] c : plan.dig()) {
            BlockPos p = new BlockPos(c[0], c[1], c[2]);
            Cell k = cellOf(level, p);
            if (k == Cell.OPEN) continue;
            if (k != Cell.DIG) {
                fail("the block to dig at " + p.toShortString() + " is " + k.name().toLowerCase(Locale.ROOT) + " now");
                return null;
            }
            hud = "Digging a gap wither skeletons cannot enter";
            setDebugState(hud);
            // (the pickaxe in hand first: the commitment's hold keeps DestroyBlockTask from its own swap, and a sword takes
            // five times as long on nether bricks)
            if (!holdPickaxe(mod)) return null;
            if (dig == null || !p.equals(digging) || dig.stopped()) {
                dig = new DestroyBlockTask(p);
                digging = p;
            }
            return dig;
        }
        boolean in = player.blockPosition().equals(cell) && player.onGround();
        inCell = in;
        if (!in) {
            if (everIn) {
                if (outSince < 0) {
                    outSince = now;
                } else if (now - outSince > OUT_TICKS) {
                    fail("out of the cell for " + OUT_TICKS / 20 + " s");
                    return null;
                }
            }
            hud = "Into a gap wither skeletons cannot enter";
            setDebugState(hud);
            if (walk == null || walk.stopped()) walk = new GetToBlockTask(cell);
            return walk;
        }
        outSince = -1;
        if (!everIn) {
            everIn = true;
            Debug.logInternal("combat: wither gap: in the cell at " + plan.at() + " after " + String.format(Locale.ROOT, "%.1f", (now - startedAt) / 20.0)
                    + " s, hp " + String.format(Locale.ROOT, "%.1f", player.getHealth()));
        }
        // the roof, put from inside the cell, each block against a face in front of the eye (WitherGapRules.clickable)
        for (int[] c : plan.fill()) {
            BlockPos p = new BlockPos(c[0], c[1], c[2]);
            Cell k = cellOf(level, p);
            if (WitherGapRules.full(k)) continue;
            if (k != Cell.OPEN) {
                fail("the roof's cell at " + p.toShortString() + " is " + k.name().toLowerCase(Locale.ROOT) + " now");
                return null;
            }
            hud = "Roofing a gap wither skeletons cannot enter";
            setDebugState(hud);
            if (place == null || !p.equals(placing) || place.stopped()) {
                place = BlazePillboxTask.click(mod, p, false);
                placing = p;
            }
            return place;
        }
        // the cell as the rule sees it, once a second: a block broken or lava come is no cell any more
        if (now - checkedAt >= CHECK_TICKS || now < checkedAt) {
            checkedAt = now;
            String no = WitherGapRules.cellNo(grid(level), cell.getX(), cell.getY(), cell.getZ());
            if (no != null) {
                fail("the cell is no cell any more (" + no + ")");
                return null;
            }
        }
        hud = "Fighting from a gap wither skeletons cannot enter";
        setDebugState(hud);
        swing(mod, player);
        return null;
    }

    // the nearest mob in reach with a clear ray from the eye, a wither skeleton before anything else, hit as the kill task hits
    // (its aim, its weapon pick, its swing through the controller's one gate: in reach, in sight, one a tick at the tick's
    // start). nothing moves: a sword never hops, and any other hand only swings at a full charge
    private void swing(AltoClef mod, LocalPlayer player) {
        // chewing and swinging use the same hand, and a meal in here is safe
        if (mod.getFoodChain().isTryingToEat()) return;
        Mob best = null;
        Vec3 bestPoint = null;
        double bestDist = Double.POSITIVE_INFINITY;
        boolean bestWither = false;
        Mob face = null;
        double faceDist = Double.POSITIVE_INFINITY;
        try {
            for (Entity entity : new ArrayList<>(mod.getEntityTracker().getHostiles())) {
                if (!(entity instanceof Mob mob) || !mob.isAlive() || EntityHelper.isCalmNeutral(mod, mob)) continue;
                boolean wither = mob instanceof WitherSkeleton;
                double dist = mob.distanceTo(player);
                if (wither && dist < faceDist) {
                    face = mob;
                    faceDist = dist;
                }
                if (dist > SWING_LOOK) continue;
                if (best != null && (bestWither && !wither || bestWither == wither && dist >= bestDist)) continue;
                Vec3 point = PerchTask.reachablePoint(player, mob);
                if (point == null) continue;
                best = mob;
                bestPoint = point;
                bestDist = dist;
                bestWither = wither;
            }
        } catch (ConcurrentModificationException ignored) {
            // the tracker rebuilds its lists on another thread sometimes, a swing a tick late is fine
        }
        if (best == null) {
            // nothing in reach: the head on the nearest wither skeleton, where the next one comes from (the mouth)
            if (face != null) mod.getControllerExtras().aimAt(face, CombatAim.Priority.KILL_TASK);
            return;
        }
        mod.getControllerExtras().aimAt(best, CombatAim.Priority.KILL_TASK, bestPoint);
        if (AbstractKillEntityTask.equipWeapon(mod, false, best)) return;
        boolean swung;
        if (mod.getControllerExtras().swordInHand()) {
            swung = (mod.getControllerExtras().attackReady() || mod.getControllerExtras().wantsCritTick()) && mod.getControllerExtras().melee(best);
        } else {
            // (melee with an axe or a pick plans a crit hop and a walk up to the mob, out of the cell: the plain full swing)
            swung = mod.getControllerExtras().attackReady() && mod.getControllerExtras().attack(best);
        }
        if (swung) {
            swings++;
            if (bestWither) hit.add(best.getId());
        }
    }

    // a wither skeleton swung at that is dead (or gone from the world) gets its line, once
    private void noteDeaths(AltoClef mod, LocalPlayer player) {
        if (hit.isEmpty()) return;
        Level level = mod.getWorld();
        List<Integer> gone = new ArrayList<>();
        for (int id : hit) {
            Entity e = level.getEntity(id);
            if (e == null || !e.isAlive()) gone.add(id);
        }
        if (gone.isEmpty()) return;
        int left = 0;
        double stay = WitherGapRules.stayRange();
        try {
            for (WitherSkeleton w : mod.getEntityTracker().getTrackedEntities(WitherSkeleton.class)) {
                if (w.isAlive() && w.distanceTo(player) <= stay) left++;
            }
        } catch (ConcurrentModificationException ignored) {
            // a count a tick old is fine for a line
        }
        for (int id : gone) {
            hit.remove(id);
            Debug.logInternal("combat: wither gap: in the cell, a wither skeleton dead (#" + id + "), " + left + " left within "
                    + Math.round(stay) + ", hp " + String.format(Locale.ROOT, "%.1f", player.getHealth()) + ", " + swings + " swings");
        }
    }

    // the best pickaxe in the bag in hand: true once it is, false while the swap goes out (or with none, which gives up)
    private boolean holdPickaxe(AltoClef mod) {
        if (mod.getPlayer().getMainHandItem().is(ItemTags.PICKAXES)) return true;
        for (Item pick : PICKAXES) {
            if (mod.getItemStorage().hasItem(pick)) {
                mod.getSlotHandler().forceEquipItem(pick);
                return mod.getPlayer().getMainHandItem().is(ItemTags.PICKAXES);
            }
        }
        fail("no pickaxe to dig with");
        return false;
    }

    // ---- the blocks as WitherGapRules sees them

    // a grid over the world that reads each block once (a search asks the same ones many times)
    public static WitherGapRules.Grid grid(Level level) {
        Map<Long, Cell> seen = new HashMap<>();
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        return (x, y, z) -> seen.computeIfAbsent(WitherGapRules.key(x, y, z), k -> cellOf(level, at.set(x, y, z)));
    }

    public static Cell cellOf(Level level, BlockPos p) {
        BlockState state = level.getBlockState(p);
        FluidState fluid = state.getFluidState();
        if (fluid.is(FluidTags.LAVA)) return Cell.LAVA;
        if (!fluid.isEmpty()) return Cell.WATER;
        if (state.getBlock() instanceof BaseFireBlock) return Cell.FIRE;
        if (state.getCollisionShape(level, p).isEmpty()) return Cell.OPEN;
        if (!state.isCollisionShapeFullBlock(level, p)) return Cell.PARTIAL;
        if (state.is(Blocks.MAGMA_BLOCK)) return Cell.HOT;
        if (state.getBlock() instanceof FallingBlock) return Cell.FALLS;
        float hardness = state.getDestroySpeed(level, p);
        if (state.hasBlockEntity() || hardness < 0 || hardness > WitherGapRules.DIG_HARDNESS || !state.is(BlockTags.MINEABLE_WITH_PICKAXE)) {
            return Cell.HARD;
        }
        return Cell.DIG;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        // nothing of ours to hand back, the walk, the dig and the click stop themselves
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        // (the commitment ends the gap, a finished task would hand the wheel back first)
        return false;
    }

    @Override
    protected boolean isEqual(Task other) {
        return other == this;
    }

    @Override
    protected String toDebugString() {
        return "Wither gap at " + plan.at() + " (" + plan.kind().word() + "), " + swings + " swings" + (failed != null ? ", given up: " + failed : "");
    }

    @Override
    protected String toHudString() {
        return hud;
    }
}
