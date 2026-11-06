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

    private BlockPos _origin;

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
        BlockPos below = cell.below();
        if (level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)) {
            return standing(new InteractWithBlockTask(new ItemTarget(Items.OBSIDIAN, 1), Direction.UP, below, false), cell, Direction.UP);
        }
        // the frame runs along z: a top cell's faces are its neighbours along it, the other top cell or a corner
        for (Direction side : new Direction[]{Direction.NORTH, Direction.SOUTH}) {
            BlockPos by = cell.relative(side);
            if (level.getBlockState(by).isFaceSturdy(level, by, side.getOpposite())) {
                return standing(new InteractWithBlockTask(new ItemTarget(Items.OBSIDIAN, 1), side.getOpposite(), by, false), cell, side.getOpposite());
            }
        }
        for (Direction side : new Direction[]{Direction.NORTH, Direction.SOUTH}) {
            BlockPos corner = cell.relative(side);
            if (!corner.equals(_origin.offset(0, 3, -1)) && !corner.equals(_origin.offset(0, 3, 2))) continue;
            if (!level.getBlockState(corner).canBeReplaced() || !level.getBlockState(corner.below()).isFaceSturdy(level, corner.below(), Direction.UP)) continue;
            for (Item block : CORNER_BLOCKS) {
                if (mod.getItemStorage().hasItem(block)) {
                    setDebugState("Placing frame... a corner block first");
                    return standing(new InteractWithBlockTask(new ItemTarget(block, 1), Direction.UP, corner.below(), false), corner, Direction.UP);
                }
            }
        }
        return null;
    }

    // (the buddy's fork: where to stand for a click into the frame. InteractWithBlockTask's own goal for a click on a top
    // face is the cell being filled and two above it at once (GoalNear 0 with GoalBlockSide UP 1), which no cell meets:
    // "No path found" every two seconds, so a cell went in only when its face was in reach from where the buddy already
    // stood. a column's third block sits on a top face above the eyes from the ground: both runs of m4cream-3 that never
    // left the overworld stood 40 game minutes under it, 6 Nov 2026. so a top face is clicked from beside the column with
    // the eyes over it, the third block from one block up and a corner from two (baritone pillars there, never in the
    // portal's own space), and a top cell's side face from the ground in front or behind, on that face's side)
    private Task standing(InteractWithBlockTask click, BlockPos placed, Direction face) {
        List<Vec3i> cells = standCells(placed.subtract(_origin), face);
        if (cells.isEmpty()) return click;
        Goal[] goals = new Goal[cells.size()];
        for (int i = 0; i < goals.length; i++) goals[i] = new GoalBlock(_origin.offset(cells.get(i)));
        GoalComposite goal = new GoalComposite(goals);
        return click.walkTo(t -> goal);
    }

    // the feet cells, relative to the origin, from which a block goes into the frame cell `placed` by a click on `face` of
    // its neighbour (UP: the top face under it; NORTH or SOUTH: a side face along the frame)
    static List<Vec3i> standCells(Vec3i placed, Direction face) {
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
            int side = face.getStepZ();
            for (int dx : new int[]{-1, 1, -2, 2}) {
                for (int dz : new int[]{0, side}) {
                    out.add(new Vec3i(placed.getX() + dx, 0, placed.getZ() + dz));
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
