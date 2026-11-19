package adris.altoclef.tasks.construction.compound;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.InteractWithBlockTask;
import adris.altoclef.tasks.construction.PlaceBlockTask;
import adris.altoclef.tasks.movement.TimeoutWanderTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.time.TimerGame;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalComposite;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;

/**
 * Build a nether portal with obsidian blocks.
 */
public class ConstructNetherPortalObsidianTask extends Task {

    // There's some code duplication between here and ConstructNetherPortalBucketTask...
    // but it's so heavily intertwined/changed that it would take forever to untangle and
    // retangle the two together.

    // Order here matters
    static final Vec3i[] PORTAL_FRAME = new Vec3i[]{
            // Left side
            new Vec3i(0, 0, -1),
            new Vec3i(0, 1, -1),
            new Vec3i(0, 2, -1),
            // Right side
            new Vec3i(0, 0, 2),
            new Vec3i(0, 1, 2),
            new Vec3i(0, 2, 2),
            // Top
            new Vec3i(0, 3, 0),
            new Vec3i(0, 3, 1),
            // Bottom
            new Vec3i(0, -1, 0),
            new Vec3i(0, -1, 1)
    };

    static final Vec3i[] PORTAL_INTERIOR = new Vec3i[]{
            //Inside
            new Vec3i(0, 0, 0),
            new Vec3i(0, 1, 0),
            new Vec3i(0, 2, 0),
            new Vec3i(0, 0, 1),
            new Vec3i(0, 1, 1),
            new Vec3i(0, 2, 1),
            //Outside 1
            new Vec3i(1, 0, 0),
            new Vec3i(1, 1, 0),
            new Vec3i(1, 2, 0),
            new Vec3i(1, 0, 1),
            new Vec3i(1, 1, 1),
            new Vec3i(1, 2, 1),
            //Outside 2
            new Vec3i(-1, 0, 0),
            new Vec3i(-1, 1, 0),
            new Vec3i(-1, 2, 0),
            new Vec3i(-1, 0, 1),
            new Vec3i(-1, 1, 1),
            new Vec3i(-1, 2, 1)
    };

    // every cell the build touches (frame and the space we clear) plus one all the way round, the same "never break
    // a block next to lava" buffer the bucket variant's portalable region keeps, so nothing we place or break has lava or water next to it. both corners are inclusive
    static final Vec3i SCAN_MIN;
    static final Vec3i SCAN_MAX;

    static {
        int minX = 0, minY = 0, minZ = 0, maxX = 0, maxY = 0, maxZ = 0;
        for (Vec3i[] cells : new Vec3i[][]{PORTAL_FRAME, PORTAL_INTERIOR}) {
            for (Vec3i cell : cells) {
                minX = Math.min(minX, cell.getX());
                minY = Math.min(minY, cell.getY());
                minZ = Math.min(minZ, cell.getZ());
                maxX = Math.max(maxX, cell.getX());
                maxY = Math.max(maxY, cell.getY());
                maxZ = Math.max(maxZ, cell.getZ());
            }
        }
        SCAN_MIN = new Vec3i(minX - 1, minY - 1, minZ - 1);
        SCAN_MAX = new Vec3i(maxX + 1, maxY + 1, maxZ + 1);
    }

    private final TimerGame _areaSearchTimer = new TimerGame(5);

    // the frame under way, kept across instances: a goal that starts over (the buddy's food run broke in) comes back to
    // the blocks it placed, where a frame somewhere else would want the obsidian that now stands in the old one
    private static BlockPos _lastOrigin;
    private static baritone.api.utils.Dimension _lastDimension;
    // the frame's obsidian as last seen: its chunk may be out of sight by the time the order comes back to it
    private static int _lastCount;
    // a walk back to the blocks placed beats a cast that goes down a cave for lava: the night's shelter and a food run took
    // the buddy off a frame begun with two blocks, out of a 48 block reach, and with eight of ten in the bag the bucket
    // cast had the order (attempt 175)
    private static final double RESUME_RANGE = 256;

    // (Tuning frame_new_site, a fix of 16 Nov 2026, on) a frame cell that will not go in drops the site: after DROP_FAILS clicks
    // given up on it (InteractWithBlockTask.clicksGivenUp, on a block a step from it at most), or DROP_TICKS of placing with no
    // frame block put in, a new site is picked at least DROP_AWAY blocks from every site dropped, and a dropped site stays out
    // for the run. the click on a frame cell bans the block it was on and wanders, and the frame handed the same cell out
    // again: "Failed, blacklisting and wandering." 47 to 84 times on one cell over 30 to 74 minutes, 7 stalls of heel-27/28
    static final int DROP_FAILS = 3;
    static final long DROP_TICKS = 2 * 60 * 20;
    static final double DROP_AWAY = 8;

    // a site dropped: where, in which dimension, and its obsidian as last seen (fetched back into the bag before a new frame)
    static final class Dropped {
        final BlockPos at;
        final baritone.api.utils.Dimension dim;
        int count;

        Dropped(BlockPos at, baritone.api.utils.Dimension dim, int count) {
            this.at = at;
            this.dim = dim;
            this.count = count;
        }
    }

    private static final List<Dropped> DROPPED = new ArrayList<>();

    static boolean newSiteOn() {
        return adris.altoclef.util.helpers.Tuning.get("frame_new_site", 0) > 0;
    }

    // the frame under way and the sites dropped as a memory of the run (RunMemory): 1 with one known, and one for each dropped
    public static int remembered() {
        return (_lastOrigin != null ? 1 : 0) + DROPPED.size();
    }

    public static void forgetRun() {
        _lastOrigin = null;
        _lastDimension = null;
        _lastCount = 0;
        DROPPED.clear();
    }

    private BlockPos _origin;

    // (frame_new_site) the frame cell being placed, the clicks given up on it, the count of given-up clicks last looked at; the
    // game ticks spent placing since a frame block last went in, the frame's obsidian then, and the last tick looked at
    private BlockPos _tryCell;
    private int _tryFails, _seenGivenUp, _placedSeen = -1;
    private long _placingTicks, _placingAt = Long.MIN_VALUE;

    private BlockPos _destroyTarget;
    // ticks the interior cell we're on has gone without a ray, see InteriorPick
    private int _noReachTicks;
    private int _reachSwitches;
    // (the buddy's fork: the interior has to stay air this long before the flint and steel. a block broken on the client can
    // come back from the server a few ticks later, or a path places one in the frame; the fire then lit no portal, burned
    // the buddy standing beside it, the douse flooded the frame and the build cleared water for two game hours, attempt
    // 218, 4 Nov 2026)
    static final int SETTLE_TICKS = 10;
    private int _airTicks;

    private static BlockPos getBuildableAreaNearby(AltoClef mod) {
        BlockPos checkOrigin = mod.getPlayer().blockPosition();
        for (BlockPos toCheck : WorldHelper.scanRegion(mod, checkOrigin.offset(SCAN_MIN), checkOrigin.offset(SCAN_MAX))) {
            if (Minecraft.getInstance().level == null) {
                return null;
            }
            BlockState state = Minecraft.getInstance().level.getBlockState(toCheck);
            boolean validToWorld = (WorldHelper.canPlace(mod, toCheck) || WorldHelper.canBreak(mod, toCheck));
            if (!validToWorld || state.getBlock() == Blocks.LAVA || state.getBlock() == Blocks.WATER || state.getBlock() == Blocks.BEDROCK) {
                return null;
            }
        }
        // (the buddy's fork: firm ground on both sides of the frame at the origin's own level, so every frame block is in reach
        // from the ground. on a hillside the top of the low side wanted a block to stand on, every block in the bag was the
        // kit's reserve, and the buddy stood ten real minutes on one cell with five of ten placed, attempt 196, 4 Nov 2026)
        var level = Minecraft.getInstance().level;
        if (level == null) return null;
        for (int dx : new int[]{-1, 1}) {
            for (int dz = -1; dz <= 2; dz++) {
                BlockPos floor = checkOrigin.offset(dx, -1, dz);
                if (level.getBlockState(floor).getCollisionShape(level, floor).isEmpty()) return null;
            }
        }
        return checkOrigin;
    }

    // the frame we left in this dimension, when we are near it and at least one of its blocks stands (as last seen, when
    // its chunk is not loaded now)
    private static BlockPos lastFrame(AltoClef mod) {
        if (_lastOrigin == null || _lastDimension != WorldHelper.getCurrentDimension() || mod.getPlayer() == null
                || !_lastOrigin.closerToCenterThan(mod.getPlayer().position(), RESUME_RANGE)) return null;
        if (mod.getWorld().isLoaded(_lastOrigin)) _lastCount = obsidianIn(mod, _lastOrigin);
        return _lastCount > 0 ? _lastOrigin : null;
    }

    private static int obsidianIn(AltoClef mod, BlockPos origin) {
        int n = 0;
        for (Vec3i cell : PORTAL_FRAME) {
            if (mod.getWorld().getBlockState(origin.offset(cell)).getBlock() == Blocks.OBSIDIAN) n++;
        }
        return n;
    }

    // (the buddy's fork: a frame cell holds obsidian when the world says so. the tracker's answer is no for a cell it banned,
    // and a click that failed bans the block it was on, a column's own obsidian: the frame then counted one short each time,
    // wanted eight or nine in the bag, and went off on a lava cast and a branch mine with nine in the frame and the bag,
    // m4cream-3 runs 1 and 2, 6 Nov 2026. the tracker only for a cell out of the loaded world)
    private static boolean obsidianAt(AltoClef mod, BlockPos pos) {
        if (mod.getWorld() != null && mod.getWorld().isLoaded(pos)) {
            return mod.getWorld().getBlockState(pos).getBlock() == Blocks.OBSIDIAN;
        }
        return mod.getBlockTracker().blockIsValid(pos, Blocks.OBSIDIAN);
    }

    // obsidian standing in the frame we left nearby: it counts toward a frame as much as the bag's
    public static int obsidianInLastFrame(AltoClef mod) {
        return lastFrame(mod) == null ? 0 : _lastCount;
    }

    // (frame_new_site) that and the obsidian standing in the sites dropped nearby (as last seen): the frame fetches it back into
    // the bag before it picks a new site, so for the choice of a frame from the bag over the bucket cast it counts as the bag's
    public static int obsidianOfOurFrames(AltoClef mod) {
        int n = obsidianInLastFrame(mod);
        if (!newSiteOn() || mod.getPlayer() == null) return n;
        for (Dropped d : DROPPED) {
            if (d.dim != WorldHelper.getCurrentDimension() || !d.at.closerToCenterThan(mod.getPlayer().position(), RESUME_RANGE)) continue;
            if (mod.getWorld().isLoaded(d.at)) d.count = obsidianIn(mod, d.at);
            n += d.count;
        }
        return n;
    }

    // (frame_new_site) the site is dropped: a few clicks given up on one frame cell, or long with no frame block put in
    static boolean dropSite(int fails, long placingTicks) {
        return fails >= DROP_FAILS || placingTicks >= DROP_TICKS;
    }

    // (frame_new_site) a click given up on this block was one for this frame cell: on the cell's floor, a side or a corner's floor
    static boolean byCell(BlockPos clicked, BlockPos cell) {
        return Math.abs(clicked.getX() - cell.getX()) <= 1 && Math.abs(clicked.getY() - cell.getY()) <= 1
                && Math.abs(clicked.getZ() - cell.getZ()) <= 1;
    }

    // (frame_new_site) the game ticks between two looks that count as placing: none across a pause of more than a second (a
    // food run, a fight, the obsidian or the flint fetched), so only time spent at the frame counts
    static long placingStep(long last, long now) {
        long d = now - last;
        return last == Long.MIN_VALUE || d <= 0 || d > 20 ? 0 : d;
    }

    // (frame_new_site) the distance across (x and z) from this origin to the nearest of these sites; MAX_VALUE with none
    static double awayFrom(BlockPos origin, List<BlockPos> sites) {
        double best = Double.MAX_VALUE;
        for (BlockPos at : sites) best = Math.min(best, Math.hypot(origin.getX() - at.getX(), origin.getZ() - at.getZ()));
        return best;
    }

    private static List<BlockPos> droppedHere() {
        List<BlockPos> out = new ArrayList<>();
        for (Dropped d : DROPPED) if (d.dim == WorldHelper.getCurrentDimension()) out.add(d.at);
        return out;
    }

    // a spot picked, or a frame begun nearby: it is finished where it is, wherever that is
    public boolean begun(AltoClef mod) {
        return _origin != null || lastFrame(mod) != null;
    }

    @Override
    protected void onStart(AltoClef mod) {
        _reachSwitches = 0;
        _noReachTicks = 0;
        _airTicks = 0;
        if (_origin == null) _origin = lastFrame(mod);
        mod.getBehaviour().push();

        // Avoid breaking portal frame if we're obsidian.
        mod.getBehaviour().avoidBlockBreaking(block -> {
            if (_origin != null) {
                // Don't break frame
                for (Vec3i framePosRelative : PORTAL_FRAME) {
                    BlockPos framePos = _origin.offset(framePosRelative);
                    if (block.equals(framePos)) {
                        return mod.getWorld().getBlockState(framePos).getBlock() == Blocks.OBSIDIAN;
                    }
                }
            }
            return false;
        });
        mod.getBehaviour().addProtectedItems(Items.FLINT_AND_STEEL);
    }

    @Override
    protected Task onTick(AltoClef mod) {
        if (_origin != null) {
            if (mod.getWorld().getBlockState(_origin.above()).getBlock() == Blocks.NETHER_PORTAL) {
                setDebugState("Done constructing nether portal.");
                // every lit cell, the bottom two most of all: the enter task only walks to a cell feet fit in, and the
                // tracker's own scan won't hear about this portal for a while
                for (Vec3i inside : PORTAL_INTERIOR) {
                    BlockPos cell = _origin.offset(inside);
                    if (mod.getWorld().getBlockState(cell).getBlock() == Blocks.NETHER_PORTAL) {
                        mod.getBlockTracker().addBlock(Blocks.NETHER_PORTAL, cell);
                    }
                }
                return null;
            }
        }
        int neededObsidian = 10;
        BlockPos placeTarget = null;
        if (_origin != null) {
            for (Vec3i frameOffs : PORTAL_FRAME) {
                BlockPos framePos = _origin.offset(frameOffs);
                if (!obsidianAt(mod, framePos)) {
                    placeTarget = framePos;
                    break;
                }
                neededObsidian--;
            }
        }

        // Get obsidian if we don't have.
        if (mod.getItemStorage().getItemCount(Items.OBSIDIAN) < neededObsidian) {
            setDebugState("Getting obsidian");
            return TaskCatalogue.getItemTask(Items.OBSIDIAN, neededObsidian);
        }

        // Find spot
        if (_origin == null) {
            if (_areaSearchTimer.elapsed()) {
                _areaSearchTimer.reset();
                Debug.logMessage("(Searching for area to build portal nearby...)");
                _origin = getBuildableAreaNearby(mod);
                // (frame_new_site) never within DROP_AWAY of a site dropped: the wander goes on
                List<BlockPos> dropped = newSiteOn() ? droppedHere() : List.of();
                if (_origin != null && !dropped.isEmpty()) {
                    double away = awayFrom(_origin, dropped);
                    if (away < DROP_AWAY) {
                        _origin = null;
                    } else {
                        Debug.logInternal("portal frame: a new site at " + _origin.toShortString() + ", " + Math.round(away)
                                + " blocks from the nearest of " + dropped.size() + " dropped (frame_new_site)");
                    }
                }
                if (_origin != null) {
                    _lastOrigin = _origin;
                    _lastDimension = WorldHelper.getCurrentDimension();
                    _lastCount = obsidianIn(mod, _origin);
                }
            }
            setDebugState("Looking for portalable area...");
            return new TimeoutWanderTask();
        }

        // Get flint and steel
        if (!mod.getItemStorage().hasItem(Items.FLINT_AND_STEEL)) {
            setDebugState("Getting flint and steel");
            return TaskCatalogue.getItemTask(Items.FLINT_AND_STEEL, 1);
        }

        // Place frame
        if (placeTarget != null) {
            _airTicks = 0;
            // (frame_new_site) a cell that will not go in: the site dropped, a new one looked for after the obsidian is back
            if (newSiteOn() && siteGivenUp(mod, placeTarget)) {
                setDebugState("Frame site dropped, a new one");
                return null;
            }
            setDebugState("Placing frame...");
            Task click = clickInFrame(mod, placeTarget);
            if (click != null) return click;
            return new PlaceBlockTask(placeTarget, new Block[]{Blocks.OBSIDIAN}, false, true);
        }

        // Clear middle
        // same pick as the bucket build: stay on the cell we're on unless it got banned or has gone a second without a ray
        // while another cell has one
        if (_destroyTarget != null && !WorldHelper.isAir(mod, _destroyTarget)) {
            _noReachTicks = InteriorPick.reachNow(mod, _destroyTarget) ? 0 : _noReachTicks + 1;
        } else {
            _destroyTarget = null;
            _noReachTicks = 0;
        }
        BlockPos next = InteriorPick.pick(mod, _origin, PORTAL_INTERIOR, cell -> WorldHelper.isAir(mod, cell), _destroyTarget, _noReachTicks, _reachSwitches);
        if (next != null) {
            _airTicks = 0;
            if (!next.equals(_destroyTarget)) {
                if (_destroyTarget != null && !mod.getBlockTracker().unreachable(_destroyTarget)) {
                    _reachSwitches++;
                }
                _destroyTarget = next;
                _noReachTicks = 0;
            }
            return ConstructNetherPortalBucketTask.clearInteriorCell(mod.getWorld().getBlockState(next), next);
        }
        if (++_airTicks < SETTLE_TICKS) {
            setDebugState("Interior clear, waiting a moment before lighting");
            return null;
        }
        // Flint and steel
        return new InteractWithBlockTask(new ItemTarget(Items.FLINT_AND_STEEL, 1), Direction.UP, _origin.below(), true);
    }

    // (frame_new_site) the watch over the cell being placed; true once it dropped the site
    private boolean siteGivenUp(AltoClef mod, BlockPos cell) {
        int placed = obsidianIn(mod, _origin);
        if (!cell.equals(_tryCell)) {
            _tryCell = cell;
            _tryFails = 0;
            _seenGivenUp = InteractWithBlockTask.clicksGivenUp;
        }
        if (placed != _placedSeen) {
            _placedSeen = placed;
            _placingTicks = 0;
        }
        long gt = mod.getWorld().getGameTime();
        _placingTicks += placingStep(_placingAt, gt);
        _placingAt = gt;
        int n = InteractWithBlockTask.clicksGivenUp;
        if (n != _seenGivenUp) {
            BlockPos at = InteractWithBlockTask.givenUpAt;
            if (at != null && byCell(at, cell)) _tryFails += n - _seenGivenUp;
            _seenGivenUp = n;
        }
        if (!dropSite(_tryFails, _placingTicks)) return false;
        String why = _tryFails >= DROP_FAILS ? _tryFails + " clicks given up on the frame cell at " + cell.toShortString()
                : "no frame block put in for " + _placingTicks / 1200 + " minutes";
        BlockPos was = _origin;
        baritone.api.utils.Dimension dim = WorldHelper.getCurrentDimension();
        DROPPED.add(new Dropped(was, dim, placed));
        if (was.equals(_lastOrigin)) {
            _lastOrigin = null;
            _lastDimension = null;
            _lastCount = 0;
        }
        // the click's bans on the old frame's blocks lifted, so its obsidian can be mined back
        int lifted = mod.getBans().lift("couldn't reach it", k -> k.kind() == adris.altoclef.trackers.Bans.Kind.BLOCK && k.dim() == dim
                && Math.abs(k.x() - was.getX()) <= 2 && k.y() >= was.getY() - 2 && k.y() <= was.getY() + 4
                && k.z() >= was.getZ() - 3 && k.z() <= was.getZ() + 4);
        Debug.logInternal("portal frame: the site at " + was.toShortString() + " dropped (" + why + ", " + placed + " of 10 placed"
                + (lifted > 0 ? ", " + lifted + " bans on it lifted" : "") + "): a new one at least " + Math.round(DROP_AWAY)
                + " blocks from every site dropped (frame_new_site)");
        _origin = null;
        _destroyTarget = null;
        _noReachTicks = 0;
        _reachSwitches = 0;
        _standGoal = null;
        _standCell = null;
        _tryCell = null;
        _tryFails = 0;
        _placedSeen = -1;
        _placingTicks = 0;
        _placingAt = Long.MIN_VALUE;
        return true;
    }

    // (the buddy's fork: a frame cell with a face to click on goes in with a plain click, as a player builds it. baritone's
    // builder wants to stand next to the cell, and with the frame's side two high on open ground there is nothing to stand on
    // there: it gave up every ten seconds and wandered off and back, five real minutes with two of ten placed (attempt 221,
    // 4 Nov 2026). the top row has no face of its own until a corner holds a block, so a throwaway goes there first; a
    // portal's corners can be anything. a cell in the ground, or with no face, is still baritone's)
    private static final Item[] CORNER_BLOCKS = {Items.COBBLESTONE, Items.COBBLED_DEEPSLATE, Items.NETHERRACK, Items.DIRT,
            Items.STONE, Items.ANDESITE, Items.DIORITE, Items.GRANITE, Items.TUFF, Items.BLACKSTONE, Items.BASALT,
            Items.SMOOTH_BASALT, Items.DEEPSLATE};

    private Task clickInFrame(AltoClef mod, BlockPos cell) {
        var level = mod.getWorld();
        if (level == null || mod.getPlayer() == null || !level.getBlockState(cell).canBeReplaced()
                || mod.getPlayer().getBoundingBox().intersects(new AABB(cell))) return null;
        // (Tuning portal_frame_clear, a fix of 18 Nov 2026, on) short grass, leaf litter or a snow layer in the cell first, by hand:
        // the click aims at the face under it, the ray stops on the plant's outline, and the click waited for a look it never
        // got until the move checker gave up; 5 of the 15 sites one run dropped in 34 minutes on a forest floor (look 131)
        BlockState inCell = level.getBlockState(cell);
        if (!inCell.isAir() && inCell.getFluidState().isEmpty() && !inCell.getShape(level, cell).isEmpty()
                && adris.altoclef.util.helpers.Tuning.get("portal_frame_clear", 0) > 0) {
            setDebugState("Placing frame... clearing " + net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(inCell.getBlock()).getPath() + " out of a frame cell first");
            return new adris.altoclef.tasks.construction.DestroyBlockTask(cell);
        }
        BlockPos below = cell.below();
        if (level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)) {
            Task step = stepUp(mod, cell);
            if (step != null) return step;
            return standing(mod, new InteractWithBlockTask(new ItemTarget(Items.OBSIDIAN, 1), Direction.UP, below, false), cell, Direction.UP);
        }
        // the frame runs along z: a top cell's faces are its neighbours along it, the other top cell or a corner
        for (Direction side : new Direction[]{Direction.NORTH, Direction.SOUTH}) {
            BlockPos by = cell.relative(side);
            if (level.getBlockState(by).isFaceSturdy(level, by, side.getOpposite())) {
                return standing(mod, new InteractWithBlockTask(new ItemTarget(Items.OBSIDIAN, 1), side.getOpposite(), by, false), cell, side.getOpposite());
            }
        }
        for (Direction side : new Direction[]{Direction.NORTH, Direction.SOUTH}) {
            BlockPos corner = cell.relative(side);
            if (!corner.equals(_origin.offset(0, 3, -1)) && !corner.equals(_origin.offset(0, 3, 2))) continue;
            if (!level.getBlockState(corner).canBeReplaced() || !level.getBlockState(corner.below()).isFaceSturdy(level, corner.below(), Direction.UP)) continue;
            for (Item block : CORNER_BLOCKS) {
                if (mod.getItemStorage().hasItem(block)) {
                    setDebugState("Placing frame... a corner block first");
                    return standing(mod, new InteractWithBlockTask(new ItemTarget(block, 1), Direction.UP, corner.below(), false), corner, Direction.UP);
                }
            }
        }
        return null;
    }

    // (Tuning portal_frame_step, a fix of 18 Nov 2026, on) a column's third block is clicked from one block up, and baritone was
    // to pillar there: in one run it never did (no path, "couldn't get more than 0 blocks", even a goal straight above the
    // feet), and 9 of 15 sites were dropped in 34 minutes (look 131). so, as a player does, a block goes on the ground beside
    // the column first, outside the portal's space and best not in front of its opening, and the stand is a step up onto it.
    // null when a stand one up already has its floor, when the stand is on the ground or two up, or with no block to spare
    private Task stepUp(AltoClef mod, BlockPos cell) {
        if (adris.altoclef.util.helpers.Tuning.get("portal_frame_step", 0) <= 0) return null;
        var level = mod.getWorld();
        List<Vec3i> cells = standCells(cell.subtract(_origin), Direction.UP, true);
        if (cells.isEmpty() || cells.get(0).getY() != 1) return null;
        for (Vec3i c : cells) {
            BlockPos feet = _origin.offset(c), floor = feet.below();
            if (level.getBlockState(floor).isFaceSturdy(level, floor, Direction.UP) && level.getBlockState(feet).canBeReplaced()
                    && level.getBlockState(feet.above()).canBeReplaced()) return null;
        }
        Item block = null;
        for (Item b : CORNER_BLOCKS) {
            if (mod.getItemStorage().hasItem(b)) { block = b; break; }
        }
        if (block == null) return null;
        BlockPos pick = null;
        for (int pass = 0; pass < 2 && pick == null; pass++) {
            for (Vec3i c : cells) {
                // the first pass only beside the frame, the second also in front of the opening
                boolean front = isIn(new Vec3i(0, c.getY(), c.getZ()), PORTAL_INTERIOR);
                if (pass == 0 && front) continue;
                BlockPos feet = _origin.offset(c), floor = feet.below(), ground = floor.below();
                if (!level.getBlockState(floor).canBeReplaced() || !level.getFluidState(floor).isEmpty()) continue;
                if (!level.getBlockState(ground).isFaceSturdy(level, ground, Direction.UP)) continue;
                if (!level.getBlockState(feet).canBeReplaced() || !level.getBlockState(feet.above()).canBeReplaced()) continue;
                if (mod.getPlayer().getBoundingBox().intersects(new AABB(floor))) continue;
                pick = ground;
                break;
            }
        }
        if (pick == null) return null;
        // (Tuning portal_step_clear, a fix of 19 Nov 2026, on) a snow layer or a plant in the step's cell first, by hand, as
        // portal_frame_clear does for a frame cell: the ray to the ground's top face stopped on the snow's outline, the click
        // answered "can't reach" five times from the goal itself, and two snowy sites were dropped with 5 of 10 placed (look 136)
        BlockState inStep = level.getBlockState(pick.above());
        if (!inStep.isAir() && inStep.getFluidState().isEmpty() && !inStep.getShape(level, pick.above()).isEmpty()
                && adris.altoclef.util.helpers.Tuning.get("portal_step_clear", 0) > 0) {
            setDebugState("Placing frame... clearing " + net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(inStep.getBlock()).getPath() + " out of the step's cell first");
            return new adris.altoclef.tasks.construction.DestroyBlockTask(pick.above());
        }
        setDebugState("Placing frame... a step beside the column first");
        // (through standing: InteractWithBlockTask's own goal for a top face is met by no cell, see standing)
        return standing(mod, new InteractWithBlockTask(new ItemTarget(block, 1), Direction.UP, pick, false), pick.above(), Direction.UP);
    }

    // (the buddy's fork: where to stand for a click into the frame. InteractWithBlockTask's own goal for a click on a top
    // face is the cell being filled and two above it at once (GoalNear 0 with GoalBlockSide UP 1), which no cell meets:
    // "No path found" every two seconds, so a cell went in only when its face was in reach from where the buddy already
    // stood. a column's third block sits on a top face above the eyes from the ground: both runs of m4cream-3 that never
    // left the overworld stood 40 game minutes under it, 6 Nov 2026. so a top face is clicked from beside the column with
    // the eyes over it, the third block from one block up and a corner from two (baritone pillars there, never in the
    // portal's own space), and a top cell's side face from the ground in front or behind, on that face's side)
    private Task standing(AltoClef mod, InteractWithBlockTask click, BlockPos placed, Direction face) {
        boolean ground = face == Direction.UP || interiorTopClear(mod);
        // (one goal for one click: InteractWithBlockTask.isEqual compares the goal, and a new one each tick restarted the
        // click every tick, its path cancelled before a step and its timers reset: 40 000 restarts a run, m4cream2-1, all
        // eight stalled at the frame, 6 Nov 2026)
        if (_standGoal == null || !placed.equals(_standCell) || face != _standFace || ground != _standGround) {
            List<Vec3i> cells = standCells(placed.subtract(_origin), face, ground);
            if (cells.isEmpty()) return click;
            Goal[] goals = new Goal[cells.size()];
            for (int i = 0; i < goals.length; i++) goals[i] = new GoalBlock(_origin.offset(cells.get(i)));
            GoalComposite goal = new GoalComposite(goals);
            _standCell = placed;
            _standFace = face;
            _standGround = ground;
            _standGoal = t -> goal;
        }
        return click.walkTo(_standGoal);
    }

    private BlockPos _standCell;
    private Direction _standFace;
    private boolean _standGround;
    private java.util.function.Function<BlockPos, Goal> _standGoal;

    // the cells a click on a top cell's side face looks through from the ground: the interior's top and the rows in front
    // and behind. they are cleared only once the frame stands, so a block left in one hides the face from below
    private boolean interiorTopClear(AltoClef mod) {
        if (mod == null || mod.getWorld() == null) return true;
        for (Vec3i c : PORTAL_INTERIOR) {
            if (c.getY() >= 1 && !WorldHelper.isAir(mod, _origin.offset(c))) return false;
        }
        return true;
    }

    // the feet cells, relative to the origin, from which a block goes into the frame cell `placed` by a click on `face` of
    // its neighbour (UP: the top face under it; NORTH or SOUTH: a side face along the frame)
    static List<Vec3i> standCells(Vec3i placed, Direction face, boolean ground) {
        List<Vec3i> out = new ArrayList<>();
        if (face == Direction.UP) {
            // the eyes (1.62 over the feet) over the face, which is at the placed cell's own floor
            int y = Math.max(0, placed.getY() - 1);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    Vec3i s = new Vec3i(placed.getX() + dx, y, placed.getZ() + dz);
                    if (isIn(s, PORTAL_FRAME)) continue;
                    // above the ground the stand is a pillar, so none in the space that must be air to light (it runs from
                    // the ground to the top of the frame's sides, so the stand's own cell tells)
                    if (y > 0 && isIn(s, PORTAL_INTERIOR)) continue;
                    out.add(s);
                }
            }
        } else if (face.getAxis() == Direction.Axis.Z) {
            // the eyes on the face's own side: from the ground in front or behind (`ground`: the cells the look goes through
            // are clear), or from a pillar two up beside a side, out of the portal's space
            int side = face.getStepZ();
            if (ground) {
                for (int dx : new int[]{-1, 1, -2, 2}) {
                    for (int dz : new int[]{0, side}) {
                        out.add(new Vec3i(placed.getX() + dx, 0, placed.getZ() + dz));
                    }
                }
            }
            for (int dx : new int[]{-1, 1}) {
                for (int dz : new int[]{0, side, 2 * side}) {
                    Vec3i s = new Vec3i(placed.getX() + dx, 2, placed.getZ() + dz);
                    if (!isIn(s, PORTAL_INTERIOR) && !isIn(s, PORTAL_FRAME)) out.add(s);
                }
            }
        }
        return out;
    }

    private static boolean isIn(Vec3i cell, Vec3i[] cells) {
        for (Vec3i c : cells) if (c.equals(cell)) return true;
        return false;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        mod.getBehaviour().pop();
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof ConstructNetherPortalObsidianTask;
    }

    @Override
    protected String toHudString() {
        return "Building a Nether portal";
    }

    @Override
    protected String toDebugString() {
        return "Building nether portal with obsidian";
    }
}
