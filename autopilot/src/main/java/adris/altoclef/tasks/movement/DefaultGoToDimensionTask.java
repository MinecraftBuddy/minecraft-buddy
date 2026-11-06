package adris.altoclef.tasks.movement;

import baritone.Baritone;
import adris.altoclef.AltoClef;
import adris.altoclef.tasks.construction.compound.ConstructNetherPortalBucketTask;
import adris.altoclef.tasks.construction.compound.ConstructNetherPortalObsidianTask;
import adris.altoclef.tasksystem.Task;
import baritone.api.utils.Dimension;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.ui.HudText;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;

/**
 * Some generic tasks require us to go to the nether/overworld/end.
 * <p>
 * The user should be able to specify how this should be done in settings
 * (ex, craft a new portal from scratch or check particular portal areas first or highway or whatever)
 */
public class DefaultGoToDimensionTask extends Task {

    private final Dimension _target;
    // Cached to keep build properties alive if this task pauses/resumes.
    private final ConstructNetherPortalBucketTask _cachedNetherBucketConstructionTask = new ConstructNetherPortalBucketTask();
    private final ConstructNetherPortalObsidianTask _cachedNetherObsidianConstructionTask = new ConstructNetherPortalObsidianTask();
    // the frame from the bag's obsidian, once chosen, is built to the end: its blocks leave the bag as they go in, and
    // counting them again would hand the half built frame over to the bucket cast
    private boolean _obsidianFrame;
    private GetToYTask _upForFrame;
    private int _upForFrameY;

    public DefaultGoToDimensionTask(Dimension target) {
        _target = target;
    }

    // the cast we hand out, so a caller can see how far its frame got
    public ConstructNetherPortalBucketTask bucketPortalTask() {
        return _cachedNetherBucketConstructionTask;
    }

    @Override
    protected void onStart(AltoClef mod) {
        mod.getBlockTracker().trackBlock(Blocks.NETHER_PORTAL);
    }

    @Override
    protected Task onTick(AltoClef mod) {
        if (WorldHelper.getCurrentDimension() == _target) return null;

        switch (_target) {
            case OVERWORLD:
                switch (WorldHelper.getCurrentDimension()) {
                    case NETHER:
                        return goToOverworldFromNetherTask(mod);
                    case END:
                        return goToOverworldFromEndTask(mod);
                }
                break;
            case NETHER:
                switch (WorldHelper.getCurrentDimension()) {
                    case OVERWORLD:
                        return goToNetherFromOverworldTask(mod);
                    case END:
                        // First go to the overworld
                        return goToOverworldFromEndTask(mod);
                }
                break;
            case END:
                switch (WorldHelper.getCurrentDimension()) {
                    case NETHER:
                        // First go to the overworld
                        return goToOverworldFromNetherTask(mod);
                    case OVERWORLD:
                        return goToEndTask(mod);
                }
                break;
        }

        setDebugState(WorldHelper.getCurrentDimension() + " -> " + _target + " is NOT IMPLEMENTED YET!");
        return null;
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        mod.getBlockTracker().stopTracking(Blocks.NETHER_PORTAL);
    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof DefaultGoToDimensionTask task) {
            return task._target == _target;
        }
        return false;
    }

    @Override
    protected String toDebugString() {
        return "Going to dimension: " + _target + " (default version)";
    }

    @Override
    protected String toHudString() {
        return "Heading to " + HudText.dimension(_target);
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        return WorldHelper.getCurrentDimension() == _target;
    }

    private Task goToOverworldFromNetherTask(AltoClef mod) {
        if (netherPortalIsClose(mod)) {
            setDebugState("Going to nether portal");
            return new EnterNetherPortalTask(Dimension.NETHER);
        }

        Optional<BlockPos> closest = mod.getMiscBlockTracker().getLastUsedNetherPortal(Dimension.NETHER);
        if (closest.isPresent()) {
            setDebugState("Going to last nether portal pos");
            return new GetToBlockTask(closest.get());
        }

        setDebugState("Constructing nether portal with obsidian");
        return new ConstructNetherPortalObsidianTask();
    }

    private Task goToOverworldFromEndTask(AltoClef mod) {
        setDebugState("TODO: Go to center portal (at 0,0). If it doesn't exist, kill ender dragon lol");
        return null;
    }

    private Task goToNetherFromOverworldTask(AltoClef mod) {
        if (netherPortalIsClose(mod)) {
            setDebugState("Going to nether portal");
            return new EnterNetherPortalTask(Dimension.NETHER);
        }
        return switch (Baritone.settings().altoOverworldToNetherBehaviour.value) {
            case BUILD_PORTAL_VANILLA -> obsidianFrame(mod) ? upForTheFrame(mod) : _cachedNetherBucketConstructionTask;
            case GO_TO_HOME_BASE -> new GetToBlockTask(Baritone.settings().altoHomeBasePosition.value);
        };
    }

    // the frame from the bag is built on the ground, not in the rock: one dug in at y -3 let water in from a pocket the
    // digging opened, the portal would not stay lit, and the buddy stood in the water inside its own frame with no path
    // out (attempt 170). up on top the buddy's own food rule, which asks under the open sky, also sees it once more before
    // the nether. a frame already begun is finished where it stands
    // under the sea there is no ground up there to build on: the frame goes in the rock where we are. the climb dug up to the
    // sea floor at y 10 and found no path through the water over it (attempt 178)
    private Task upForTheFrame(AltoClef mod) {
        BlockPos at = mod.getPlayer().blockPosition();
        boolean begun = _cachedNetherObsidianConstructionTask.begun(mod);
        int water = begun ? 0 : waterAbove(mod, at);
        if (!begun && water >= SEA_DEPTH) {
            setDebugState("The sea over us (" + water + " deep): the frame goes in the rock here");
        }
        if (!begun && water < SEA_DEPTH) {
            // the climb picked is seen through; on top, the ground there decides whether there is more to climb
            if (_upForFrame != null && !_upForFrame.isFinished(mod)) {
                setDebugState("Up to the surface at y " + _upForFrameY + " for the portal");
                return _upForFrame;
            }
            int top = mod.getWorld().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, at.getX(), at.getZ());
            if (at.getY() < top - 1) {
                _upForFrame = new GetToYTask(top);
                _upForFrameY = top;
                setDebugState("Up to the surface at y " + top + " for the portal");
                return _upForFrame;
            }
        }
        _upForFrame = null;
        return _cachedNetherObsidianConstructionTask;
    }

    // this much water over our head is a sea: a pond, a river or a pool in a mineshaft is climbed past. any water at all sent a
    // climb from a mineshaft back to y 7, the frame went in the rock, the food rule never saw the sky, and the buddy went into
    // the nether with 19 points (attempt 180)
    static final int SEA_DEPTH = 6;

    // the water in our column from over our head up to the surface, in blocks
    private static int waterAbove(AltoClef mod, BlockPos at) {
        int top = mod.getWorld().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, at.getX(), at.getZ());
        BlockPos.MutableBlockPos cell = new BlockPos.MutableBlockPos();
        int n = 0;
        for (int y = at.getY() + 2; y <= top; y++) {
            if (mod.getWorld().getFluidState(cell.set(at.getX(), y, at.getZ())).is(FluidTags.WATER)) n++;
        }
        return n;
    }

    // a frame's worth of obsidian in the bag (or standing in the frame we left nearby) and flint and steel: a frame of it
    // where we stand, no lava to find. the cast alone sent the buddy down a cave for lava six times in one order, each
    // time a new frame at a new spot, with ten obsidian in the bag all the while (attempt 167)
    private boolean obsidianFrame(AltoClef mod) {
        if (!_obsidianFrame && mod.getItemStorage().hasItem(Items.FLINT_AND_STEEL)
                && mod.getItemStorage().getItemCount(Items.OBSIDIAN) + ConstructNetherPortalObsidianTask.obsidianInLastFrame(mod) >= 10) {
            _obsidianFrame = true;
            setDebugState("Constructing nether portal with the obsidian in the bag");
        }
        return _obsidianFrame;
    }

    private Task goToEndTask(AltoClef mod) {
        // Keep in mind that getting to the end requires going to the nether first.
        setDebugState("TODO: Get to End, Same as BeatMinecraft");
        return null;
    }

    private boolean netherPortalIsClose(AltoClef mod) {
        // the same pick the EnterNetherPortalTask we hand out makes, or a portal it can't get into keeps handing it out
        // when the build below is what would help
        Optional<BlockPos> closest = EnterNetherPortalTask.usablePortal(mod, EnterNetherPortalTask.ANY_PORTAL, mod.getPlayer().position());
        return closest.isPresent() && closest.get().closerToCenterThan(mod.getPlayer().position(), 2000);
    }
}
