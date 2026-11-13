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
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

// (the buddy's fork, Tuning skel_cover, a fix of 13 Nov 2026, on) the buddy under the hp line in a skeleton's line in the
// overworld takes cover where it stands: a full block from the bag (the throwaway kinds first, CornerWallTask.wallItems) in a
// cell on the line from the buddy's eye to the skeleton's, the head's height first and the feet's under it when the head's has
// no face yet; with no such cell, two down where the buddy stands and the top shut (CornerWallTask.digIn). then it stands
// there: the meal and the regen do the rest. SkelCover.Hold in CombatBrain says when the cover is on and when it ends; the click
// is CornerWallTask's (MobDefenseChain.placeCell's).
// (Tuning skel_cover_dig_first, a fix of 14 Nov 2026, on) test heel's skeletons walked round the one block to 1-3 m, then "no
// cell on the line ... no ground to dig" in 11 of 23 deaths, and a hole with no top was stood in and shot into: the cover digs in
// first, two down with the top shut by the dug block, wherever the ground comes out with the tools at hand; where it does not,
// a pillbox from the bag (the four cells at the eye's height, the side toward the skeleton first, and the top where it has a
// face to go against), whatever line there is; a hole with no top is a cover that failed; an arrow that lands in cover places
// the next block or digs at once, and one in the finished cover with nothing more to put up is a cover that failed.
// (Tuning skel_cover_wet_fail, a fix of 14 Nov 2026, on) off the ground a second (in water) is a cover that failed: the task
// stood 36 s in water under six arrows
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

    // (skel_cover_dig_first) an arrow counts this long after it landed (one that came mid meal or mid knock is old news by then)
    private static final long ARROW_TICKS = 40;
    // the dug-in hole's top settles this long before an arrow counts as one through it (the last one may have been on its way)
    private static final long SETTLE_TICKS = 10;
    // an arrow in the finished pillbox sends it round its cells again this many times, then the dig, then no cover
    private static final int BOX_ROUNDS = 2;
    // cells one pillbox clicks at most (the four sides, their feet's cells for a face, the top)
    private static final int BOX_MAX_CLICKS = 12;

    private final Supplier<Seen> seen;
    // (skel_cover_dig_first) dig first and build the pillbox: the key, or the over-runs cover with a melee mob near (the brain's)
    private final BooleanSupplier digFirst;
    // the game time of the last projectile that hit the buddy (CombatBrain.countShots)
    private final LongSupplier shotAt;
    private final Object keys = new Object();
    private CornerWallTask dig;
    private long builtAt = -1;
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
    private long shotHandled = Long.MIN_VALUE / 2;
    private long offSince = -1;
    // (skel_cover_dig_first) the pillbox: where it stands, its cells in order, the ones given up, how many clicks and rounds
    private boolean boxing;
    private boolean boxDone;
    private BlockPos boxFeet;
    private final List<BlockPos> boxCells = new ArrayList<>();
    private final Set<BlockPos> boxSkipped = new HashSet<>();
    private int boxClicks;
    private int boxRounds;
    private String boxLead = "skeleton cover: ";

    public SkelCoverTask(Supplier<Seen> seen) {
        this(seen, SkelCover::digFirstOn, () -> Long.MIN_VALUE / 2);
    }

    public SkelCoverTask(Supplier<Seen> seen, BooleanSupplier digFirst, LongSupplier shotAt) {
        this.seen = seen;
        this.digFirst = digFirst;
        this.shotAt = shotAt;
    }

    // why the cover could not be had, null while it has not given up (SkelCover.Hold's failed)
    public String failed() {
        return failed;
    }

    // (skel_cover_over_runs) the buddy is digging in, or dug in, here: the cover against melee mobs too, whatever the ground under
    // the hole's floor would say
    public boolean digging() {
        return failed == null && dig != null && dig.state() != CombatCommit.WallState.FAILED;
    }

    // a block is on its way in this tick: the arrow shield stays down, its use key and its look would take the click's
    public boolean placing() {
        return placing && failed == null && dig == null;
    }

    @Override
    protected void onStart(AltoClef mod) {
        // the arrows that sent the buddy into cover are not arrows in it
        shotHandled = shotAt.getAsLong();
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
        return dig != null ? "Taking cover from skeletons (dug in)" : boxing ? "Taking cover from skeletons (pillbox)" : "Taking cover from skeletons";
    }

    @Override
    protected Task onTick(AltoClef mod) {
        placing = false;
        LocalPlayer player = mod.getPlayer();
        Level level = mod.getWorld();
        long now = level.getGameTime();
        if (failed != null) return stand(mod);
        // (skel_cover_wet_fail) off the ground a second, in water mostly: no cover to be had here, the run's rules again
        if (SkelCover.wetFailOn()) {
            if (player.onGround()) {
                offSince = -1;
            } else if (offSince < 0 || now < offSince) {
                offSince = now;
            }
            if (SkelCover.offGroundFailed(offSince, now)) {
                return fail(mod, "off the ground " + SkelCover.OFF_GROUND_TICKS / 20 + " s" + (player.isInWater() ? " (in water)" : "")
                        + " at " + player.blockPosition().toShortString());
            }
        }
        boolean first = digFirst.getAsBoolean();
        long shot = shotAt.getAsLong();
        // (skel_cover_dig_first) an arrow landed since the last one acted on, lately
        boolean arrow = first && shot > shotHandled && now >= shot && now - shot <= ARROW_TICKS;
        if (dig != null) {
            CombatCommit.WallState state = dig.state();
            if (state == CombatCommit.WallState.FAILED) {
                // (dig_first: the ground that would not come out after all is the pillbox's, once)
                if (first && !boxing && boxFeet == null && CornerWallTask.wallItems(mod).length > 0 && player.onGround()) {
                    dig = null;
                    startBox(player, seen.get());
                    return null;
                }
                return fail(mod, "the dig failed");
            }
            if (state != CombatCommit.WallState.BUILT) return dig;
            if (builtAt < 0) builtAt = now;
            if (first && !SkelCover.holeHolds(true, dig.topShut())) return fail(mod, "dug in with no top, open to a skeleton over the rim");
            if (arrow && shot > builtAt + SETTLE_TICKS) {
                shotHandled = shot;
                return fail(mod, "an arrow in the hole with its top shut");
            }
            return stand(mod);
        }
        Seen s = seen.get();
        sayPlaced(level);
        if (boxing) return box(mod, player, level, now, s, arrow, shot);
        // in cover (no line left), in the air after a knock, or chewing: still, the meal is not the click's to break
        // (dig_first: the hole or the box goes up once taken, whatever the line does this tick: a skeleton steps behind a tree and
        // out again, and the cover in place of a run has zombies coming)
        if ((!first && s.lines().isEmpty()) || !player.onGround() || mod.getFoodChain().isTryingToEat()) return stand(mod);
        BlockPos feet = player.blockPosition();
        if (first) return takeCover(mod, player, level, s, feet, shot);
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

    // no cover here: SkelCover.Hold lets go, the run's rules again
    private Task fail(AltoClef mod, String why) {
        failed = why;
        Debug.logInternal("combat: skeleton cover: no cover: " + why);
        return stand(mod);
    }

    // ---- (skel_cover_dig_first) dig first, else the pillbox

    private Task takeCover(AltoClef mod, LocalPlayer player, Level level, Seen s, BlockPos feet, long shot) {
        boolean blocks = CornerWallTask.wallItems(mod).length > 0;
        SkelCover.Way way = SkelCover.way(CornerWallTask.shut(level, feet), walled(level, feet), CornerWallTask.diggable(mod, level, feet), blocks);
        shotHandled = Math.max(shotHandled, shot);
        switch (way) {
            case SHUT -> {
                return stand(mod);
            }
            case DIG -> {
                dig = CornerWallTask.digIn(lead(s, player));
                mod.getClientBaritone().getPathingBehavior().cancelEverything();
                return dig;
            }
            case BOX -> {
                startBox(player, s);
                return null;
            }
            default -> {
                return fail(mod, "no block and no ground to dig at " + feet.toShortString());
            }
        }
    }

    // the four cells at the eye's height are solid: a hole, a nook or a box of an earlier round, the top is all it lacks
    private static boolean walled(Level level, BlockPos feet) {
        for (net.minecraft.core.Direction d : net.minecraft.core.Direction.Plane.HORIZONTAL) {
            if (!CornerWallTask.solid(level, feet.above().relative(d))) return false;
        }
        return true;
    }

    private void startBox(LocalPlayer player, Seen s) {
        boxing = true;
        boxDone = false;
        boxFeet = player.blockPosition();
        boxCells.clear();
        boxSkipped.clear();
        current = null;
        Mob toward = s.lines().isEmpty() ? null : s.lines().get(0);
        double dx = toward == null ? 1 : toward.getX() - player.getX();
        double dz = toward == null ? 0 : toward.getZ() - player.getZ();
        for (int[] side : SkelCover.boxSides(dx, dz)) boxCells.add(boxFeet.offset(side[0], 1, side[1]));
        boxCells.add(boxFeet.above(2));
        boxLead = lead(s, player);
        Debug.logInternal("combat: " + boxLead + "a pillbox at " + boxFeet.toShortString() + " (no ground to dig here)");
    }

    private Task box(AltoClef mod, LocalPlayer player, Level level, long now, Seen s, boolean arrow, long shot) {
        if (!player.onGround() || mod.getFoodChain().isTryingToEat()) return stand(mod);
        if (!player.blockPosition().equals(boxFeet)) {
            // knocked off the spot: a new look from where the buddy is now
            boxing = false;
            boxFeet = null;
            current = null;
            return stand(mod);
        }
        if (boxDone) {
            if (!arrow) return stand(mod);
            // an arrow in the finished box: the cells given up once more, then the dig, then no cover
            shotHandled = shot;
            if (boxRounds >= BOX_ROUNDS) {
                if (CornerWallTask.diggable(mod, level, boxFeet)) {
                    boxing = false;
                    dig = CornerWallTask.digIn(lead(s, player));
                    mod.getClientBaritone().getPathingBehavior().cancelEverything();
                    return dig;
                }
                return fail(mod, "arrows in the pillbox at " + boxFeet.toShortString() + ", nothing more to put up");
            }
            boxRounds++;
            boxDone = false;
            boxSkipped.clear();
            current = null;
        }
        if (arrow) shotHandled = shot;
        BlockPos next = CornerWallTask.wallItems(mod).length > 0 && boxClicks < BOX_MAX_CLICKS ? nextBoxCell(level, player) : null;
        if (next == null) {
            boxDone = true;
            int up = 0;
            for (BlockPos cell : boxCells) if (CornerWallTask.solid(level, cell)) up++;
            Debug.logInternal("combat: " + boxLead + "pillbox up at " + boxFeet.toShortString() + ": " + up + " of " + boxCells.size()
                    + " cells shut" + (CornerWallTask.solid(level, boxFeet.above(2)) ? "" : ", no top (nothing to put it against)"));
            if (up == 0) return fail(mod, "no block of the pillbox went in at " + boxFeet.toShortString());
            return stand(mod);
        }
        if (!next.equals(current)) {
            current = next;
            currentSince = now;
            boxClicks++;
        }
        placing = true;
        mod.getClientBaritone().getPathingBehavior().requestPause();
        if (now - currentSince > CELL_TICKS || !CornerWallTask.click(mod, player, level, next, CornerWallTask.BESIDE_SUPPORT, keys)) {
            boxSkipped.add(next);
            current = null;
        } else {
            clicked.putIfAbsent(next, boxLead);
        }
        return null;
    }

    // the pillbox's next cell: in order, the first not shut that a block can go into with a face beside it; a side cell with no
    // face yet gets the cell under it first (its top face is the one the side needs over open ground)
    private BlockPos nextBoxCell(Level level, LocalPlayer player) {
        for (BlockPos cell : boxCells) {
            if (CornerWallTask.solid(level, cell)) continue;
            if (boxUsable(level, player, cell)) return cell;
            BlockPos below = cell.below();
            if (cell.getY() == boxFeet.getY() + 1 && boxUsable(level, player, below)) return below;
        }
        return null;
    }

    private boolean boxUsable(Level level, LocalPlayer player, BlockPos cell) {
        return !boxSkipped.contains(cell) && CornerWallTask.open(level, cell, player)
                && CornerWallTask.support(level, player, cell, CornerWallTask.BESIDE_SUPPORT) != null;
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
