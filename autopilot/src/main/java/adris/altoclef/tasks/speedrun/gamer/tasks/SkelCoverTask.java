package adris.altoclef.tasks.speedrun.gamer.tasks;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.helpers.CombatCommit;
import adris.altoclef.util.helpers.SkelCover;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.skeleton.Bogged;
import net.minecraft.world.entity.monster.skeleton.Parched;
import net.minecraft.world.entity.monster.skeleton.Skeleton;
import net.minecraft.world.entity.monster.skeleton.Stray;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.ConcurrentModificationException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

// (the buddy's fork, Tuning skel_cover, a fix of 13 Nov 2026, on) the buddy under the hp line in a skeleton's line in the
// overworld takes cover where it stands: a full block from the bag (the throwaway kinds first, CornerWallTask.wallItems) in a
// cell on the line from the buddy's eye to the skeleton's, the head's height first and the feet's under it when the head's has
// no face yet; with no such cell, two down where the buddy stands and the top shut (CornerWallTask.digIn). then it stands
// there: the meal and the regen do the rest. SkelCover.Hold in CombatBrain says when the cover is on and when it ends; the click
// is CornerWallTask's (MobDefenseChain.placeCell's)
public final class SkelCoverTask extends Task {

    // the skeletons about as one tick's look: lines, the ones with a line to the buddy within range, nearest first; lineDist the
    // nearest of those; nearest the nearest of the kinds, seen or not; kinds and lineIds their entity ids
    public record Seen(List<Mob> lines, double lineDist, double nearest, Set<Integer> kinds, Set<Integer> lineIds) {
        public static final Seen NONE = new Seen(List.of(), Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Set.of(), Set.of());
    }

    // the tracker files an entity under its own class (MobDefenseChain.drawingShooter), so each kind is asked by name
    private static final List<Class<? extends Mob>> KINDS = List.of(Skeleton.class, Stray.class, Bogged.class, Parched.class);
    // the kinds looked at this far out (the hold's leave range, and a little for one walking in)
    private static final double LOOK_RANGE = SkelCover.LEAVE_RANGE + 4;

    // the skeletons of the kinds about, and which of them have a line to the buddy within range (their eye to ours, as their
    // bow goal asks it)
    public static Seen scan(AltoClef mod, LocalPlayer player, double range) {
        List<Mob> lines = new ArrayList<>();
        Set<Integer> kinds = new HashSet<>();
        Set<Integer> lineIds = new HashSet<>();
        double nearest = Double.POSITIVE_INFINITY;
        try {
            for (Class<? extends Mob> kind : KINDS) {
                for (Mob mob : mod.getEntityTracker().getTrackedEntities(kind)) {
                    if (!mob.isAlive()) continue;
                    double d = mob.distanceTo(player);
                    if (d > LOOK_RANGE) continue;
                    kinds.add(mob.getId());
                    nearest = Math.min(nearest, d);
                    if (d <= range && mob.hasLineOfSight(player)) {
                        lines.add(mob);
                        lineIds.add(mob.getId());
                    }
                }
            }
        } catch (ConcurrentModificationException ignored) {
            // the tracker rebuilds its lists on another thread sometimes: what was seen so far, the next tick has the rest
        }
        if (kinds.isEmpty()) return Seen.NONE;
        lines.sort(Comparator.comparingDouble(m -> m.distanceTo(player)));
        double lineDist = lines.isEmpty() ? Double.POSITIVE_INFINITY : lines.get(0).distanceTo(player);
        return new Seen(lines, lineDist, nearest, kinds, lineIds);
    }

    // one cell's click gets this long (the block in hand, the look, the click) before the next is tried
    private static final long CELL_TICKS = 20;
    // a cell on the line this far from the eye at most (a block's face needs to be in reach to click on)
    private static final double LINE_REACH = 3.5;
    // blocks one cover puts up at most: a skeleton that keeps walking round is the dig's then
    private static final int MAX_BLOCKS = 10;

    private final Supplier<Seen> seen;
    private final Object keys = new Object();
    private CornerWallTask dig;
    private String failed;
    private boolean placing;
    private int clicks;
    private BlockPos current;
    private long currentSince;
    private BlockPos feetAt;
    private final Set<BlockPos> skipped = new HashSet<>();
    // the cells clicked, with the line's lead as it stood at the click (the block shows up a tick later, the line gone by then)
    private final Map<BlockPos, String> clicked = new LinkedHashMap<>();
    private final Set<BlockPos> said = new HashSet<>();

    public SkelCoverTask(Supplier<Seen> seen) {
        this.seen = seen;
    }

    // why the cover could not be had, null while it has not given up (SkelCover.Hold's failed)
    public String failed() {
        return failed;
    }

    // a block is on its way in this tick: the arrow shield stays down, its use key and its look would take the click's
    public boolean placing() {
        return placing && failed == null && dig == null;
    }

    @Override
    protected void onStart(AltoClef mod) {
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        mod.getInputControls().releaseOwnedBy(keys);
        placing = false;
    }

    @Override
    protected boolean isEqual(Task other) {
        return other == this;
    }

    @Override
    protected String toDebugString() {
        return dig != null ? "Taking cover from skeletons (dug in)" : "Taking cover from skeletons";
    }

    @Override
    protected Task onTick(AltoClef mod) {
        placing = false;
        LocalPlayer player = mod.getPlayer();
        Level level = mod.getWorld();
        long now = level.getGameTime();
        if (failed != null) return stand(mod);
        if (dig != null) {
            CombatCommit.WallState state = dig.state();
            if (state == CombatCommit.WallState.FAILED) {
                failed = "the dig failed";
                return stand(mod);
            }
            return state == CombatCommit.WallState.BUILT ? stand(mod) : dig;
        }
        Seen s = seen.get();
        sayPlaced(level);
        // in cover (no line left), in the air after a knock, or chewing: still, the meal is not the click's to break
        if (s.lines().isEmpty() || !player.onGround() || mod.getFoodChain().isTryingToEat()) return stand(mod);
        BlockPos feet = player.blockPosition();
        if (!feet.equals(feetAt)) {
            // knocked to another cell: what was skipped from the old one may work from here
            feetAt = feet;
            skipped.clear();
            current = null;
        }
        Item[] blocks = CornerWallTask.wallItems(mod);
        BlockPos next = blocks.length > 0 && clicks < MAX_BLOCKS ? nextCell(level, player, s) : null;
        if (next == null) {
            // no block, no cell on the line a block can go into, or the blocks one cover may use spent: two down and the top shut
            if (CornerWallTask.diggable(mod, level, feet)) {
                dig = CornerWallTask.digIn(lead(s, player));
                mod.getClientBaritone().getPathingBehavior().cancelEverything();
                return dig;
            }
            failed = (blocks.length == 0 ? "no block" : clicks >= MAX_BLOCKS ? MAX_BLOCKS + " blocks up and still a line" : "no cell on the line for a block")
                    + " and no ground to dig at " + feet.toShortString();
            Debug.logInternal("combat: " + lead(s, player) + "no cover: " + failed);
            return stand(mod);
        }
        if (!next.equals(current)) {
            current = next;
            currentSince = now;
            clicks++;
        }
        placing = true;
        mod.getClientBaritone().getPathingBehavior().requestPause();
        if (now - currentSince > CELL_TICKS || !CornerWallTask.click(mod, player, level, next, CornerWallTask.BESIDE_SUPPORT, keys)) {
            skipped.add(next);
            current = null;
        } else {
            clicked.putIfAbsent(next, lead(s, player));
        }
        return null;
    }

    // still, the path paused
    private Task stand(AltoClef mod) {
        mod.getInputControls().releaseOwnedBy(keys);
        mod.getClientBaritone().getPathingBehavior().requestPause();
        return null;
    }

    // the first cell a block can go into, skeleton by skeleton nearest first: on the line from the buddy's eye to the
    // skeleton's eye, outside the buddy's own box, within LINE_REACH; each cell at the eye's height followed by the one under
    // it (its block is the face the head's one needs over open ground, and it stops a low arrow)
    private BlockPos nextCell(Level level, LocalPlayer player, Seen s) {
        Vec3 eye = player.getEyePosition();
        AABB body = player.getBoundingBox().inflate(0.01);
        int eyeY = (int) Math.floor(eye.y);
        for (Mob mob : s.lines()) {
            for (int[] c : SkelCover.rayCells(eye.x, eye.y, eye.z, mob.getX(), mob.getEyeY(), mob.getZ(), LINE_REACH)) {
                BlockPos cell = new BlockPos(c[0], c[1], c[2]);
                if (body.intersects(new AABB(cell))) continue;
                if (usable(level, player, cell)) return cell;
                if (cell.getY() == eyeY) {
                    BlockPos below = cell.below();
                    if (!body.intersects(new AABB(below)) && usable(level, player, below)) return below;
                }
            }
        }
        return null;
    }

    private boolean usable(Level level, LocalPlayer player, BlockPos cell) {
        return !skipped.contains(cell) && CornerWallTask.open(level, cell, player)
                && CornerWallTask.support(level, player, cell, CornerWallTask.BESIDE_SUPPORT) != null;
    }

    // a block of ours that showed up: its line, once
    private void sayPlaced(Level level) {
        for (Map.Entry<BlockPos, String> e : clicked.entrySet()) {
            BlockPos cell = e.getKey();
            if (!CornerWallTask.solid(level, cell) || !said.add(cell)) continue;
            Debug.logInternal("combat: " + e.getValue() + "a block at " + cell.getX() + " " + cell.getY() + " " + cell.getZ());
        }
    }

    // "skeleton cover: 2 skeletons with a line within 11.5 at 5.0 hp, "
    private static String lead(Seen s, LocalPlayer player) {
        int n = s.lines().size();
        String within = Double.isInfinite(s.lineDist()) ? "range" : String.format(Locale.ROOT, "%.1f", s.lineDist());
        return String.format(Locale.ROOT, "skeleton cover: %d %s with a line within %s at %.1f hp, ", n, n == 1 ? "skeleton" : "skeletons",
                within, player.getHealth());
    }
}
