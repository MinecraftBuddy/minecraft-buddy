package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.InteractWithBlockTask;
import adris.altoclef.tasks.ResourceTask;
import adris.altoclef.tasks.construction.DestroyBlockTask;
import adris.altoclef.tasks.movement.TimeoutWanderTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.MiningRequirement;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.MineAnchor;
import adris.altoclef.util.helpers.TreeChop;
import adris.altoclef.util.helpers.TreeStrikes;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

public class CollectStrippedLogTask extends ResourceTask {
    private static final Item[] _axes = new Item[]{Items.WOODEN_AXE, Items.STONE_AXE, Items.GOLDEN_AXE, Items.IRON_AXE,
            Items.DIAMOND_AXE, Items.NETHERITE_AXE};
    private final Item[] _strippedLogs;
    private final Item[] _strippableLogs;
    private final int _targetCount;

    public CollectStrippedLogTask(Item[] strippedLogs, Item[] strippableLogs, int count) {
        super(new ItemTarget(strippedLogs, count));
        _strippedLogs = strippedLogs;
        _strippableLogs = strippableLogs;
        _targetCount = count;
    }

    public CollectStrippedLogTask(int count) {
        this(ItemHelper.STRIPPED_LOGS, ItemHelper.STRIPPABLE_LOGS, count);
    }

    public CollectStrippedLogTask(Item strippedLogs, Item strippableLogs, int count) {
        this(new Item[]{strippedLogs}, new Item[]{strippableLogs}, count);
    }

    public CollectStrippedLogTask(Item strippedLog, int count) {
        this(strippedLog, ItemHelper.strippedToLogs(strippedLog), count);
    }

    // the log we stripped last. a strip fires no place event, so the tracker only sees the stripped log on its next rescan
    // (seconds), and the old pick spent that time stripping every other log on the tree. this one we look at ourselves
    private BlockPos _lastStripped;

    // the drops wait while the tree still has bark on it, they fall to the floor anyway and walking down for each one is
    // the walk the strip-chop-strip order is here to save. once the tree is done (or the floor already covers it) they go
    @Override
    protected boolean shouldAvoidPickingUp(AltoClef mod) {
        if (_lastStripped == null) {
            return false;
        }
        if (enoughOnFloor(mod)) {
            return false;
        }
        boolean standing = isStripped(mod, _lastStripped) || isStrippable(mod, _lastStripped);
        return standing || nextInTree(mod, _lastStripped).isPresent();
    }

    // held plus the stripped drops lying around the tree already covers the count
    private boolean enoughOnFloor(AltoClef mod) {
        ItemTarget stripped = new ItemTarget(_strippedLogs);
        int lying = 0;
        for (ItemEntity drop : mod.getEntityTracker().getDroppedItems()) {
            boolean ours = stripped.matches(drop.getItem().getItem()) && TreeStrikes.sameTree(_lastStripped, drop.blockPosition());
            if (ours && TreeChop.countable(!mod.getEntityTracker().isEntityReachable(drop), drop.getAge())) {
                lying += drop.getItem().getCount();
            }
        }
        return TreeChop.enough(mod.getItemStorage().getItemCount(_strippedLogs), lying, _targetCount);
    }

    private boolean isStripped(AltoClef mod, BlockPos at) {
        return isOneOf(mod, at, ItemHelper.itemsToBlocks(_strippedLogs));
    }

    private boolean isStrippable(AltoClef mod, BlockPos at) {
        return isOneOf(mod, at, ItemHelper.itemsToBlocks(_strippableLogs));
    }

    private static boolean isOneOf(AltoClef mod, BlockPos at, Block[] blocks) {
        BlockState state = mod.getWorld().getBlockState(at);
        for (Block block : blocks) {
            if (state.is(block)) {
                return true;
            }
        }
        return false;
    }

    // the strippable log of the same tree nearest the one we just took (MineAnchor's box, so the trunk goes in order)
    private Optional<BlockPos> nextInTree(AltoClef mod, BlockPos last) {
        Block[] strippable = ItemHelper.itemsToBlocks(_strippableLogs);
        return MineAnchor.nearest(last, mod.getBlockTracker().getKnownLocations(strippable), true, last.getX() + 0.5, last.getY() + 0.5, last.getZ() + 0.5,
                check -> !mod.getBlockTracker().unreachable(check) && mod.getBlockTracker().blockIsValid(check, strippable));
    }

    @Override
    protected void onResourceStart(AltoClef mod) {
        mod.getBlockTracker().trackBlock(ItemHelper.itemsToBlocks(_strippedLogs));
        mod.getBlockTracker().trackBlock(ItemHelper.itemsToBlocks(_strippableLogs));
    }

    @Override
    protected Task onResourceTick(AltoClef mod) {
        if (!mod.getItemStorage().hasItem(_axes)) {
            setDebugState("Getting axe for stripping");
            return TaskCatalogue.getItemTask(Items.WOODEN_AXE, 1);
        }
        // strip one, break it, strip the next one. the world says what the log is right now, the tracker would be seconds late
        if (_lastStripped != null && mod.getBlockTracker().unreachable(_lastStripped)) {
            _lastStripped = null;
        }
        boolean haveLast = _lastStripped != null;
        switch (TreeChop.stripStep(haveLast, haveLast && isStripped(mod, _lastStripped), haveLast && isStrippable(mod, _lastStripped))) {
            case BREAK:
                setDebugState("Breaking the log we stripped");
                return new DestroyBlockTask(_lastStripped);
            case STRIP:
                setDebugState("Stripping log");
                return new InteractWithBlockTask(new ItemTarget(_axes), _lastStripped);
            default:
                break;
        }
        Optional<BlockPos> next = haveLast ? nextInTree(mod, _lastStripped) : Optional.empty();
        if (next.isEmpty()) {
            _lastStripped = null;
            // stripped logs that were already standing (ours from before, or somebody's) before any new stripping
            if (mod.getItemStorage().getItemCount(_strippedLogs) < _targetCount) {
                Optional<BlockPos> strippedLogBlockPos = mod.getBlockTracker().getNearestTracking(ItemHelper.itemsToBlocks(_strippedLogs));
                if (strippedLogBlockPos.isPresent()) {
                    setDebugState("Getting stripped log");
                    return new MineAndCollectTask(new ItemTarget(_strippedLogs), ItemHelper.itemsToBlocks(_strippedLogs), MiningRequirement.HAND);
                }
            }
            next = mod.getBlockTracker().getNearestTracking(ItemHelper.itemsToBlocks(_strippableLogs));
        }
        if (next.isPresent()) {
            _lastStripped = next.get();
            setDebugState("Stripping log");
            return new InteractWithBlockTask(new ItemTarget(_axes), _lastStripped);
        }
        setDebugState("Searching log");
        return new TimeoutWanderTask();
    }

    @Override
    protected void onResourceStop(AltoClef mod, Task interruptTask) {
        _lastStripped = null;
        mod.getBlockTracker().stopTracking(ItemHelper.itemsToBlocks(_strippedLogs));
        mod.getBlockTracker().stopTracking(ItemHelper.itemsToBlocks(_strippableLogs));
    }

    @Override
    protected boolean isEqualResource(ResourceTask other) {
        if (other instanceof CollectStrippedLogTask task) {
            return task._targetCount == _targetCount;
        }
        return false;
    }

    @Override
    protected String toHudString() {
        return "Stripping logs";
    }

    @Override
    protected String toDebugStringName() {
        return "Collect Stripped Log";
    }
}
