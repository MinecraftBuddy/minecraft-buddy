package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.AbstractDoToClosestObjectTask;
import adris.altoclef.tasks.ResourceTask;
import adris.altoclef.tasks.construction.DestroyBlockTask;
import adris.altoclef.tasks.movement.PickupDroppedItemTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.trackers.BanPolicy;
import adris.altoclef.trackers.Bans;
import adris.altoclef.trackers.EntityTracker;
import adris.altoclef.tasks.speedrun.gamer.SmeltSurface;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.MiningRequirement;
import adris.altoclef.util.helpers.BuriedLogs;
import adris.altoclef.util.helpers.FloatingBlock;
import adris.altoclef.util.helpers.GameClock;
import baritone.Baritone;
import baritone.api.utils.Dimension;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.ItemPickupRules;
import adris.altoclef.util.helpers.LocalBlockSearch;
import adris.altoclef.util.helpers.LookHelper;
import adris.altoclef.util.helpers.HotbarTools;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.DropPatience;
import adris.altoclef.util.helpers.DropWatch;
import adris.altoclef.util.helpers.MineAnchor;
import adris.altoclef.util.helpers.MineStick;
import adris.altoclef.util.helpers.NoPathScope;
import adris.altoclef.util.helpers.PlacedByUs;
import adris.altoclef.util.helpers.StoneDigRank;
import adris.altoclef.util.helpers.StoneTunnel;
import adris.altoclef.util.helpers.TreeChop;
import adris.altoclef.util.helpers.TreeReach;
import adris.altoclef.util.helpers.TreeReachScan;
import adris.altoclef.util.helpers.TreeStrikes;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.progresscheck.MovementProgressChecker;
import net.minecraft.world.inventory.ContainerInput;
import adris.altoclef.util.slots.PlayerSlot;
import adris.altoclef.util.time.TimerGame;
import adris.altoclef.ui.HudText;
import java.util.*;
import net.minecraft.client.Minecraft;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

public class MineAndCollectTask extends ResourceTask {

    private final Block[] _blocksToMine;

    private final MiningRequirement _requirement;

    private final TimerGame _cursorStackTimer = new TimerGame(3);

    private final MineOrCollectTask _subtask;

    // the catalogue's ores, the ones CataloguedResourceTask is allowed to mine as one (see together)
    private boolean _ore;

    // a together task's ores and which of them each block counts for. null on every other task, which is the whole point:
    // a plain miner never looks at any of this
    private final List<Part> _parts;
    private final Map<Block, List<Part>> _partsOf;
    // what is still short, worked out once a tick (see refreshShort). the pick asks per tracked block, and an inventory
    // count per block per ore per tick adds up fast
    private int _shortAt = Integer.MIN_VALUE;
    private Set<Block> _wantedNow = new HashSet<>();
    private Block[] _shortBlocks = new Block[0];
    private ItemTarget[] _shortTargets = new ItemTarget[0];
    private MiningRequirement _requirementNow;

    // one ore of a together task: what we want out of it, the pick it takes, the blocks it comes out of
    record Part(ItemTarget target, MiningRequirement requirement, Block[] blocks) {
    }

    // blocks to build a tower with, dug by hand before the log that needs one (see dirtFirst)
    private Task _dirtTask;
    private int _dirtSince;
    private BlockPos _dirtLog;
    // how long the dirt trip gets, and how long a trip that got us nothing to place with keeps the high logs off the menu
    private static final int DIRT_PATIENCE_TICKS = 1200;
    private static final int DIRT_OFF_TICKS = 6000;
    // a tree we already dug for is not dug for again for this long
    private static final int DIRT_TREE_TICKS = 3600;

    public MineAndCollectTask(ItemTarget[] itemTargets, Block[] blocksToMine, MiningRequirement requirement) {
        this(itemTargets, blocksToMine, requirement, null);
    }

    private MineAndCollectTask(ItemTarget[] itemTargets, Block[] blocksToMine, MiningRequirement requirement, List<Part> parts) {
        super(itemTargets);
        _requirement = requirement;
        _blocksToMine = blocksToMine;
        _subtask = new MineOrCollectTask(_blocksToMine, _itemTargets);
        _parts = parts;
        _partsOf = parts == null ? null : partsOf(parts);
        _requirementNow = requirement;
        if (parts != null) {
            // a met ore's blocks stop being picks and its drops stop being worth a walk, or it eats the bag space the bill
            // planned for the others
            _subtask._only = this::wantedAt;
            _subtask._dropsFor = () -> {
                refreshShort(AltoClef.getInstance());
                return _shortTargets;
            };
        }
    }

    // several catalogue ores as one task that always goes for the nearest block of whichever ores are still short. one
    // at a time walked right past the diamonds on the way to the iron, then all the way back for them
    public static MineAndCollectTask together(List<MineAndCollectTask> ores) {
        List<Part> parts = new ArrayList<>();
        List<ItemTarget> targets = new ArrayList<>();
        // insertion order, so the same ores rebuilt next pass are the same task (isEqualResource is an array compare)
        Set<Block> blocks = new LinkedHashSet<>();
        MiningRequirement lowest = null;
        for (MineAndCollectTask ore : ores) {
            for (ItemTarget target : ore._itemTargets) {
                parts.add(new Part(target, ore._requirement, ore._blocksToMine));
                targets.add(target);
            }
            blocks.addAll(Arrays.asList(ore._blocksToMine));
            if (lowest == null || ore._requirement.ordinal() < lowest.ordinal()) {
                lowest = ore._requirement;
            }
        }
        return new MineAndCollectTask(targets.toArray(ItemTarget[]::new), blocks.toArray(Block[]::new), lowest, parts);
    }

    public MineAndCollectTask ore() {
        _ore = true;
        return this;
    }

    public boolean isOre() {
        return _ore;
    }

    // block -> the ores it counts for. usually one, but two parts can be the same ore (a coal 5 next to a coal 10)
    static Map<Block, List<Part>> partsOf(List<Part> parts) {
        Map<Block, List<Part>> out = new LinkedHashMap<>();
        for (Part part : parts) {
            for (Block block : part.blocks()) {
                out.computeIfAbsent(block, k -> new ArrayList<>()).add(part);
            }
        }
        return out;
    }

    // the cheapest pick among the ores still short, null when none are. iron + diamond on a stone pick mines the iron
    // first and only then goes for the iron pick, which is where the one at a time order ended up anyway
    static MiningRequirement lowestShort(List<Part> parts, Predicate<Part> isShort) {
        MiningRequirement lowest = null;
        for (Part part : parts) {
            if (isShort.test(part) && (lowest == null || part.requirement().ordinal() < lowest.ordinal())) {
                lowest = part.requirement();
            }
        }
        return lowest;
    }

    // blocks worth breaking right now: some ore they count for is short and we hold the pick for it
    static Set<Block> wantedBlocks(Map<Block, List<Part>> partsOf, Predicate<Part> isShort, Predicate<MiningRequirement> canMine) {
        Set<Block> out = new HashSet<>();
        for (Map.Entry<Block, List<Part>> entry : partsOf.entrySet()) {
            for (Part part : entry.getValue()) {
                if (isShort.test(part) && canMine.test(part.requirement())) {
                    out.add(entry.getKey());
                    break;
                }
            }
        }
        return out;
    }

    // the blocks of every short ore, pick or no pick. a world that only has the met ores in it is still the wrong one
    static Block[] shortBlocks(List<Part> parts, Predicate<Part> isShort) {
        Set<Block> out = new LinkedHashSet<>();
        for (Part part : parts) {
            if (isShort.test(part)) {
                out.addAll(Arrays.asList(part.blocks()));
            }
        }
        return out.toArray(Block[]::new);
    }

    // the drops still worth chasing, in part order
    static ItemTarget[] shortTargets(List<Part> parts, Predicate<Part> isShort) {
        List<ItemTarget> out = new ArrayList<>();
        for (Part part : parts) {
            if (isShort.test(part)) {
                out.add(part.target());
            }
        }
        return out.toArray(ItemTarget[]::new);
    }

    // short the way isFinished counts it. getItemCount counts the cursor and an open grid too, so an ore could look met
    // while the task isn't done, every block gets filtered out and we wander forever
    private static boolean isShort(AltoClef mod, Part part) {
        return !StorageHelper.itemTargetsMetInventoryNoCursor(mod, part.target());
    }

    private void refreshShort(AltoClef mod) {
        int now = WorldHelper.getTicks();
        if (now == _shortAt) {
            return;
        }
        _shortAt = now;
        Predicate<Part> isShort = part -> isShort(mod, part);
        _wantedNow = wantedBlocks(_partsOf, isShort, req -> StorageHelper.miningRequirementMet(mod, req));
        _shortBlocks = shortBlocks(_parts, isShort);
        _shortTargets = shortTargets(_parts, isShort);
        MiningRequirement lowest = lowestShort(_parts, isShort);
        // nothing short means we're done, isFinished gets there before this matters
        _requirementNow = lowest != null ? lowest : _requirement;
    }

    // the block at pos is one of an ore we still want and can mine
    private boolean wantedAt(BlockPos pos) {
        AltoClef mod = AltoClef.getInstance();
        refreshShort(mod);
        if (mod.getChunkTracker().isChunkLoaded(pos)) {
            return _wantedNow.contains(mod.getWorld().getBlockState(pos).getBlock());
        }
        // out past render distance the world says air, which would make every remembered vein over there a no. the
        // tracker still knows what it saw (same trust blockIsValid gives an unloaded spot)
        return _wantedNow.contains(mod.getBlockTracker().knownBlockAt(pos));
    }

    public MineAndCollectTask(ItemTarget[] blocksToMine, MiningRequirement requirement) {
        this(blocksToMine, itemTargetToBlockList(blocksToMine), requirement);
    }

    public MineAndCollectTask(ItemTarget target, Block[] blocksToMine, MiningRequirement requirement) {
        this(new ItemTarget[]{target}, blocksToMine, requirement);
    }

    public MineAndCollectTask(Item item, int count, Block[] blocksToMine, MiningRequirement requirement) {
        this(new ItemTarget(item, count), blocksToMine, requirement);
    }

    // the subtask found nothing to go to this tick and is wandering for a block (NetherGoldTask treats that as the end of the dig)
    public boolean wandering() {
        return _subtask.wasWandering();
    }

    // only ever pick blocks that pass this as the next one to break (the coal detour: only coal we can see). a block already
    // being broken is not asked again, the swing that is halfway through finishes
    public MineAndCollectTask onlyWhere(Predicate<BlockPos> only) {
        // on top of the together filter, not instead of it
        _subtask._only = _subtask._only.and(only);
        return this;
    }

    // onlyWhere for the drops: a pile from another life counts as ours wherever it lies
    public MineAndCollectTask dropsOnlyWhere(Predicate<BlockPos> only) {
        _subtask._onlyDrops = only;
        return this;
    }

    // when the tracker's blocks all get refused, look at the world itself this far round the feet before calling it empty.
    // only for a caller whose onlyWhere is narrow, the tracker's short list has no reason to hold the blocks it wants
    public MineAndCollectTask localSearch(int radius) {
        _subtask._localSearchRadius = radius;
        return this;
    }

    @Override
    public MineAndCollectTask noReserve() {
        super.noReserve();
        return this;
    }

    public static Block[] itemTargetToBlockList(ItemTarget[] targets) {
        List<Block> result = new ArrayList<>(targets.length);
        for (ItemTarget target : targets) {
            for (Item item : target.getMatches()) {
                Block block = Block.byItem(item);
                if (block != null && !WorldHelper.isAir(block)) {
                    result.add(block);
                }
            }
        }
        return result.toArray(Block[]::new);
    }

    @Override
    protected void onResourceStart(AltoClef mod) {
        mod.getBehaviour().push();
        mod.getBlockTracker().trackBlock(_blocksToMine);

        // We're mining, so don't throw away pickaxes.
        mod.getBehaviour().addProtectedItems(Items.WOODEN_PICKAXE, Items.STONE_PICKAXE, Items.IRON_PICKAXE, Items.DIAMOND_PICKAXE, Items.NETHERITE_PICKAXE);

        _subtask.resetSearch();
    }

    @Override
    protected boolean shouldAvoidPickingUp(AltoClef mod) {
        // Picking up is controlled by a separate task here.
        return true;
    }

    // MineOrCollectTask does the drops (the whole tree, then the sweep), a parent grabbing them restarts us and we forget the tree
    @Override
    public boolean ownsDrops() {
        return true;
    }

    // the catalogue puts mineIfPresent on logs and flowers with the very blocks we break. saplings come out of leaves, a
    // placed sapling is not in our list, so that one still gets the extra miner
    @Override
    protected boolean minesOnItsOwn(Block[] blocks) {
        return coversAll(blocks, _blocksToMine);
    }

    @Override
    protected Task onResourceTick(AltoClef mod) {
        // an axe for wood, a shovel for dirt and sand, out of the bag and into the hotbar where ToolSet can see them
        HotbarTools.forBlocks(mod, _blocksToMine);
        MiningRequirement requirement = _requirement;
        if (_parts != null) {
            refreshShort(mod);
            requirement = _requirementNow;
        }
        if (!StorageHelper.miningRequirementMet(mod, requirement)) {
            return new SatisfyMiningRequirementTask(requirement);
        }

        Task dirt = dirtFirst(mod);
        if (dirt != null) {
            setDebugState("Digging blocks to tower up to a log");
            return dirt;
        }

        if (_subtask.isMining()) {
            makeSureToolIsEquipped(mod);
        }

        // Wrong dimension check.
        if (_subtask.wasWandering() && isInWrongDimension(mod) && !mod.getBlockTracker().anyFound(_parts != null ? _shortBlocks : _blocksToMine)) {
            return getToCorrectDimensionTask(mod);
        }

        return _subtask;
    }

    // a canopy log past the shaft needs a tower, and a tower needs something to place. dirt is the one block that comes out of
    // the ground by hand (same reasoning as NetherRecoverTask), so the trip happens once, before the walk to the log
    private Task dirtFirst(AltoClef mod) {
        int now = WorldHelper.getTicks();
        if (_dirtTask != null) {
            // the task's own count, not "anything to place": one dirt in the bag is a tower one block high
            boolean done = _dirtTask.isFinished(mod);
            boolean late = GameClock.since(_dirtSince, now) > DIRT_PATIENCE_TICKS;
            if (!done && !late) {
                return _dirtTask;
            }
            _dirtTask = null;
            // the stall clock still has its baseline from before the walk, it would call the first tick back a stall
            _subtask.backFromDirt();
            if (done) {
                // one trip per tree: the tower eats the blocks as it goes up, and a trip for "the shortfall" every time they
                // dip would drag us off a half built tower
                MineOrCollectTask.dirtTrip(_dirtLog, now);
            } else {
                // fell short (bag full, no dirt where we looked): not worth another go any time soon, for any tree
                MineOrCollectTask.dirtOff(now, DIRT_OFF_TICKS);
                Debug.logMessage("could not dig enough dirt to tower with, leaving the high logs alone for a while");
            }
            return null;
        }
        int want = _subtask.dirtWanted(mod);
        if (want <= 0) {
            return null;
        }
        Debug.logMessage("that log is too high to reach from the ground, digging " + want + " more dirt to tower up");
        _dirtSince = now;
        _dirtLog = _subtask.takeDirtLog();
        _dirtTask = TaskCatalogue.getItemTask(Items.DIRT, mod.getItemStorage().getItemCount(Items.DIRT) + want);
        return _dirtTask;
    }

    @Override
    protected void onResourceStop(AltoClef mod, Task interruptTask) {
        _dirtTask = null;
        mod.getBlockTracker().stopTracking(_blocksToMine);
        mod.getBehaviour().pop();
    }

    @Override
    protected boolean isEqualResource(ResourceTask other) {
        if (other instanceof MineAndCollectTask task) {
            return Arrays.equals(task._blocksToMine, _blocksToMine);
        }
        return false;
    }

    @Override
    protected String toDebugStringName() {
        return _parts != null ? "Mine Ores Together" : "Mine And Collect";
    }

    @Override
    protected String toHudString() {
        return "Mining " + HudText.some(_itemTargets);
    }

    // the cursor slot is window index -1, which forceEquipSlot turned into a click outside the window: the better pick went on the
    // floor. a pickup click on the hand slot trades the cursor for what we hold, the old tool goes back to the bag from the cursor
    private static void equipFromCursor(AltoClef mod) {
        mod.getSlotHandler().clickSlot(PlayerSlot.getEquipSlot(), 0, ContainerInput.PICKUP);
    }

    private void makeSureToolIsEquipped(AltoClef mod) {
        if (_cursorStackTimer.elapsed() && !mod.getFoodChain().needsToEat()) {
            assert Minecraft.getInstance().player != null;
            ItemStack cursorStack = StorageHelper.getItemStackInCursorSlot();
            if (cursorStack != null && !cursorStack.isEmpty()) {
                // We have something in our cursor stack
                if (cursorStack.isCorrectToolForDrops(mod.getWorld().getBlockState(_subtask.miningPos()))) {
                    // Our cursor stack would help us mine our current block
                    Item currentlyEquipped = StorageHelper.getItemStackInSlot(PlayerSlot.getEquipSlot()).getItem();
                    if (ItemHelper.isDigger(cursorStack.getItem())) {
                        if (ItemHelper.isDigger(currentlyEquipped)) {
                            Item swapPick = cursorStack.getItem();
                            if (ItemHelper.getMiningSpeed(swapPick) > ItemHelper.getMiningSpeed(currentlyEquipped)) {
                                // We can equip a better pickaxe.
                                equipFromCursor(mod);
                            }
                        } else {
                            // We're not equipped with a pickaxe...
                            equipFromCursor(mod);
                        }
                    }
                }
            }
            _cursorStackTimer.reset();
        }
    }

    private static class MineOrCollectTask extends AbstractDoToClosestObjectTask<Object> {

        private final Block[] _blocks;
        private final boolean _stoneOnly;
        private final ItemTarget[] _targets;
        private final MovementProgressChecker _progressChecker = new MovementProgressChecker();
        // empty searches since we handed baritone this block, and the tick we were last driving (see noPathVerdict)
        private final NoPathScope.Watch _noPath = new NoPathScope.Watch();
        private int _lastTickAt = Integer.MIN_VALUE / 2;
        private Task _pickupTask;
        // what _pickupTask was built for
        private ItemTarget[] _pickupFor;
        // the targets whose drops are worth chasing, null for all of them. a together task hands in only the short ones
        private Supplier<ItemTarget[]> _dropsFor;
        private final MineStick _stick = new MineStick();
        private final DropPatience _patience = new DropPatience();
        private final boolean _logsOnly;
        private BlockPos _miningPos;
        // the last block we broke, while there is more around it (see MineAnchor)
        private BlockPos _anchor;
        private boolean _anchorAnnounced;
        private ItemEntity _lockedDrop;
        // a caller's extra say on which blocks may be picked (see onlyWhere). last in the test, it may be the costly one
        private Predicate<BlockPos> _only = check -> true;
        private Predicate<BlockPos> _onlyDrops = check -> true;
        // see localSearch, -1 is off. the tick the last look came back empty, and whether the first hit got its log line
        private int _localSearchRadius = -1;
        private int _localSearchEmptyAt = Integer.MIN_VALUE / 2;
        private boolean _localSearchLogged;
        // failed breaks per tree. static because the parent rebuilds this task whenever it likes and the count has to outlive that
        private static final TreeStrikes STRIKES = new TreeStrikes();
        private final TreeReachScan _scan = new TreeReachScan();
        private final BuriedLogs _buried = new BuriedLogs();
        // no digging for dirt before this tick. static for the same reason as the strikes, a rebuilt task would start another trip
        private static int dirtOffUntil = Integer.MIN_VALUE;
        // the last tree we dug dirt for, and when
        private static BlockPos dirtTreeAt;
        private static int dirtTreeTick = Integer.MIN_VALUE;
        // how many trips that tree got (see TreeChop.tripsFor)
        private static int dirtTreeTrips;
        // a log baritone found no way to, waiting on the dirt trip it asked for (see askForDirt), and the tower it needs
        private BlockPos _dirtAsk;
        private int _dirtAskGap;
        // the tree whose drops we pick up once it is down, see treeSweep
        private BlockPos _sweepTree;

        public MineOrCollectTask(Block[] blocks, ItemTarget[] targets) {
            _blocks = blocks;
            _stoneOnly = StoneDigRank.stoneOnly(blocks);
            _logsOnly = MineAnchor.logsOnly(blocks);
            _targets = targets;
            _pickupTask = new PickupDroppedItemTask(_targets, true);
            _pickupFor = _targets;
        }

        private ItemTarget[] dropTargets() {
            return _dropsFor == null ? _targets : _dropsFor.get();
        }

        // the same pickup task while the wanted drops stay the same, a fresh one once an ore is met and its drops stop counting
        private Task pickupTask() {
            ItemTarget[] want = dropTargets();
            if (!Arrays.equals(want, _pickupFor)) {
                _pickupFor = want;
                _pickupTask = new PickupDroppedItemTask(want, true);
            }
            return _pickupTask;
        }

        @Override
        protected Vec3 getPos(AltoClef mod, Object obj) {
            if (obj instanceof BlockPos b) {
                return WorldHelper.toVec3d(b);
            }
            if (obj instanceof ItemEntity item) {
                return item.position();
            }
            throw new UnsupportedOperationException("Shouldn't try to get the position of object " + obj + " of type " + (obj != null ? obj.getClass().toString() : "(null object)"));
        }

        // exposure is six world reads, so only the stone near enough to matter pays for it. past this the walk is the
        // whole story and a buried block 40 away is as far as an exposed one
        private static final double EXPOSURE_RANGE = 24;

        private double stoneScore(AltoClef mod, double fx, double fy, double fz, double tx, double ty, double tz) {
            int bx = (int) Math.floor(tx);
            int by = (int) Math.floor(ty);
            int bz = (int) Math.floor(tz);
            boolean exposed = true;
            int falling = 0;
            if (Math.abs(tx - fx) <= EXPOSURE_RANGE && Math.abs(tz - fz) <= EXPOSURE_RANGE && Math.abs(ty - fy) <= EXPOSURE_RANGE) {
                exposed = hasAirFace(mod, bx, by, bz);
                // gravel on top of the stone doesn't show up in hasAirFace, it's a block. it comes down on whoever digs it
                falling = WorldHelper.fallingAbove(mod.getWorld(), new BlockPos(bx, by, bz));
            }
            if (_tunnel.latched()) {
                return StoneDigRank.tunnelScore(fx, fy, fz, bx, by, bz, exposed, falling, _tunnel.floor());
            }
            return StoneDigRank.score(fx, fy, fz, bx, by, bz, exposed, falling);
        }

        // stoneScore from where we stand
        private double stoneScoreFromHere(AltoClef mod, BlockPos at) {
            Vec3 me = mod.getPlayer().position();
            return stoneScore(mod, me.x, me.y, me.z, at.getX(), at.getY(), at.getZ());
        }

        // stoneScoreFromHere minus STANDING_ON, for switchCall. the pick still pays it, so the first choice keeps off the
        // column under us
        private double switchScore(AltoClef mod, BlockPos at) {
            Vec3 me = mod.getPlayer().position();
            return stoneScoreFromHere(mod, at) - StoneDigRank.standingOnPart(me.x, me.y, me.z, at.getX(), at.getY(), at.getZ());
        }

        private static boolean hasAirFace(AltoClef mod, int x, int y, int z) {
            BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
            for (Direction d : Direction.values()) {
                at.set(x + d.getStepX(), y + d.getStepY(), z + d.getStepZ());
                if (mod.getWorld().getBlockState(at).isAir()) {
                    return true;
                }
            }
            return false;
        }

        @Override
        protected Optional<Object> getClosestTo(AltoClef mod, Vec3 pos) {
            noteBreak(mod);
            Optional<Object> pick = pick(mod, pos);
            // the drop we hand out is the one we stay on, see DropPatience
            if (pick.isPresent() && pick.get() instanceof ItemEntity drop) {
                _lockedDrop = drop;
                _patience.lock(drop.getId());
            }
            return pick;
        }

        private Optional<Object> pick(AltoClef mod, Vec3 pos) {
            int now = WorldHelper.getTicks();
            boolean paused = mod.getExtraBaritoneSettings().isInteractionPaused();
            ItemEntity locked = lockedDrop(mod);

            // We can't mine right now.
            if (paused) {
                Optional<ItemEntity> drop = locked != null ? Optional.of(locked) : nearestDrop(mod, pos, true);
                return (drop.isPresent() ? drop : nearestDrop(mod, pos, false)).map(Object.class::cast);
            }

            // halfway through a block is not the time to go and get a drop. the drop gets its turn when the block is gone
            // (a drop we are already walking to is not traded for a block that happens to be cracking behind us)
            BlockPos breaking = mod.getControllerExtras().getBreakingBlockPos();
            // only the crack on the block we picked is a commitment. a stray click a frame after re-aiming lands on the neighbour,
            // and adopting that one aimed us back at it, whose first click landed on the first one: two dirt blocks took turns
            // every tick for four minutes and neither ever broke
            if (locked == null && breaking != null && breaking.equals(_miningPos) && mod.getControllerExtras().isBreakingBlock()
                    && stillOurs(mod, breaking)) {
                _stick.breaking(now, breaking);
            } else if (locked == null) {
                beginHold(mod, now);
            }
            BlockPos held = _stick.holdOn(now, check -> stillOurs(mod, check));
            if (held != null && !mod.getControllerExtras().isBreakingBlock() && despawningDropNear(mod, now, pos)) {
                // the one exception: a drop a few seconds from gone is worth a block we have not cracked yet (mid crack the base task
                // refuses every switch anyway, and a lock it never acts on would just burn the patience)
                _stick.clear();
                held = null;
            }
            if (held != null) {
                return Optional.of(held);
            }

            // and once we are on a drop nothing takes us off it. no maths, no second opinions, until it is in the bag
            if (locked != null && spendOn(mod, locked)) {
                return Optional.of(locked);
            }

            Optional<BlockPos> closestBlock = pickBlock(mod, pos);
            // pickBlock lets go of the anchor when its tree is out of logs, so a block with the anchor still set is from the tree
            boolean onTree = _logsOnly && _anchor != null && closestBlock.isPresent();
            if (onTree) {
                _sweepTree = _anchor;
            }
            if (_sweepTree != null) {
                Optional<ItemEntity> sweep = treeSweep(mod, pos, onTree);
                if (sweep.isPresent()) {
                    return sweep.map(Object.class::cast);
                }
            }
            Optional<ItemEntity> closestDrop = nearestDrop(mod, pos, true);
            if (closestDrop.isEmpty() && closestBlock.isEmpty()) {
                // nothing else to do with our hands: a drop in reach is still better than wandering off from it
                closestDrop = nearestDrop(mod, pos, false);
            }

            double blockSq = closestBlock.isEmpty() ? Double.POSITIVE_INFINITY : closestBlock.get().distToCenterSqr(pos);
            double rawDropSq = closestDrop.isEmpty() ? Double.POSITIVE_INFINITY : closestDrop.get().distanceToSqr(pos);
            boolean despawning = closestDrop.isPresent() && MineStick.aboutToDespawn(closestDrop.get().getAge());
            boolean bagFull = closestDrop.isPresent() && mod.getItemStorage().getSlotsThatCanFitInPlayerInventory(closestDrop.get().getItem(), false).isEmpty();

            if (TreeChop.dropWins(onTree, despawning, bagFull, rawDropSq, blockSq)) {
                return closestDrop.map(Object.class::cast);
            } else {
                return closestBlock.map(Object.class::cast);
            }
        }

        // the drops of the tree we are on (or just finished). mid tree only once they cover what we came for, the logs still
        // standing would be overchop. once the tree is down, all of them, nearest first from wherever we are standing
        private Optional<ItemEntity> treeSweep(AltoClef mod, Vec3 pos, boolean onTree) {
            if (!mod.getEntityTracker().itemDropped(_targets)) {
                if (!onTree) {
                    _sweepTree = null;
                }
                return Optional.empty();
            }
            List<ItemEntity> lying = countableDrops(mod, _sweepTree);
            if (onTree) {
                if (!enoughWith(mod, lying)) {
                    return Optional.empty();
                }
            } else if (lying.isEmpty()) {
                _sweepTree = null;
                return Optional.empty();
            }
            // nearest of the counted ones, not nearestDrop: that one doesn't ask isValid, and a drop in the pond next door
            // would win every tick and get dropped by lockedDrop every tick
            ItemEntity best = null;
            for (ItemEntity drop : lying) {
                if (best == null || drop.distanceToSqr(pos) < best.distanceToSqr(pos)) {
                    best = drop;
                }
            }
            return Optional.ofNullable(best);
        }

        // our drops lying around the tree that we can still count on, see TreeChop.countable
        private List<ItemEntity> countableDrops(AltoClef mod, BlockPos tree) {
            List<ItemEntity> out = new ArrayList<>();
            for (ItemEntity drop : mod.getEntityTracker().getDroppedItems()) {
                if (!TreeChop.countable(!mod.getEntityTracker().isEntityReachable(drop), drop.getAge())) continue;
                if (!TreeStrikes.sameTree(tree, drop.blockPosition()) || !isValid(mod, drop)) continue;
                out.add(drop);
            }
            return out;
        }

        // every target covered by what we hold plus what is on the floor
        private boolean enoughWith(AltoClef mod, List<ItemEntity> lying) {
            for (ItemTarget target : _targets) {
                int onFloor = 0;
                for (ItemEntity drop : lying) {
                    if (target.matches(drop.getItem().getItem())) {
                        onFloor += drop.getItem().getCount();
                    }
                }
                if (!TreeChop.enough(mod.getItemStorage().getItemCount(target), onFloor, target.getTargetCount())) {
                    return false;
                }
            }
            return true;
        }

        // the block we were handed counts as started once we can reach it, not only once the controller reports the crack: a
        // drop from the last block rolling out of the pickup box in between used to win and the block was never touched
        private void beginHold(AltoClef mod, int now) {
            BlockPos at = _miningPos;
            if (at == null || !_stick.wouldBegin(at) || !stillOurs(mod, at)) {
                return;
            }
            _stick.begin(now, at);
            if (mod.getEntityTracker().itemDropped(_targets)) {
                // once per block, so the unprotected first ticks can be counted instead of guessed at
                Debug.logInternal("mine: holding " + at.toShortString() + " from the first tick, a drop is in sight");
            }
        }

        // a drop within the first-pick range that has been around long enough to be nearly gone. every 5th tick, this is
        // the exception and not worth a tracker query per tick of every break
        private boolean despawningDropNear(AltoClef mod, int now, Vec3 pos) {
            if (now % 5 != 0) {
                return false;
            }
            Optional<ItemEntity> drop = nearestDrop(mod, pos, true);
            return drop.isPresent() && MineStick.aboutToDespawn(drop.get().getAge()) && drop.get().distanceToSqr(pos) <= DROP_FIRST_RANGE_SQ;
        }

        // the next block to break. around the one we just broke while there is anything there, otherwise the nearest
        private Optional<BlockPos> pickBlock(AltoClef mod, Vec3 pos) {
            Optional<BlockPos> pick = pickBlock(mod, pos, _logsOnly);
            if (pick.isEmpty() && _logsOnly) {
                // every log left is one we can't get at and can't tower to. a log we might fail on beats standing still, and
                // the strikes (see giveUpTree) are what move us on from the tree
                pick = pickBlock(mod, pos, false);
            }
            return pick.isPresent() ? pick : localPick(mod);
        }

        // the tracker keeps a handful per type and they don't have to be anywhere near what _only will take, with a wall of the
        // right stuff in arm's reach it can still come back with nothing. same idea as localStones, but for whoever asks
        private Optional<BlockPos> localPick(AltoClef mod) {
            int now = WorldHelper.getTicks();
            if (_localSearchRadius < 0 || LocalBlockSearch.emptyStands(pursuing() != null, now, _localSearchEmptyAt)) {
                return Optional.empty();
            }
            // the type first, the rules are the costly part and most of the cube is air or lava anyway
            Optional<BlockPos> hit = LocalBlockSearch.nearest(mod.getPlayer().blockPosition(), _localSearchRadius,
                    check -> isOneOfOurs(mod.getWorld().getBlockState(check)) && usable(mod, check, false));
            if (hit.isEmpty()) {
                _localSearchEmptyAt = now;
            } else if (!_localSearchLogged) {
                _localSearchLogged = true;
                BlockPos at = hit.get();
                Debug.logInternal("restock: tracker had nothing usable, local pick at " + at.getX() + " " + at.getY() + " " + at.getZ());
            }
            return hit;
        }

        private boolean isOneOfOurs(BlockState state) {
            for (Block block : _blocks) {
                if (state.is(block)) return true;
            }
            return false;
        }

        private boolean usable(AltoClef mod, BlockPos check, boolean sparing) {
            if (mod.getBlockTracker().unreachable(check)) return false;
            if (hangsInTheNether(mod, check)) return false;
            // ahead of worthTheTrip, so a mine post doesn't spend a column scan on the way out
            if (buriedLog(mod, check)) return false;
            return WorldHelper.canBreak(mod, check) && _only.test(check) && (!sparing || worthTheTrip(mod, check));
        }

        // sparing leaves out the logs we have no way up to (TreeReach.skip)
        private Optional<BlockPos> pickBlock(AltoClef mod, Vec3 pos, boolean sparing) {
            // (the buddy's fork: unarmoured, only the ores its own tunnel opens, see nearUnarmoured)
            Predicate<BlockPos> usable = check -> usable(mod, check, sparing) && nearUnarmoured(mod, check, _blocks);
            // what we put down ourselves is scaffolding, not a resource. it is the best looking stone there is (exposed, at
            // our feet) and eating it is how the table placement looped. only when nothing else is close do we take it
            long gameTime = mod.getWorld().getGameTime();
            Predicate<BlockPos> notOurs = check -> usable.test(check) && !PlacedByUs.GLOBAL.recent(check.getX(), check.getY(), check.getZ(), gameTime);

            if (_anchor != null) {
                Predicate<BlockPos> inTree = check -> mod.getBlockTracker().blockIsValid(check, _blocks) && notOurs.test(check);
                Optional<BlockPos> around = topDown(mod, inTree);
                if (around.isEmpty()) {
                    around = MineAnchor.nearest(_anchor, mod.getBlockTracker().getKnownLocations(_blocks), _logsOnly, pos.x, pos.y, pos.z, inTree);
                }
                if (around.isPresent()) {
                    if (!_anchorAnnounced) {
                        _anchorAnnounced = true;
                        BlockPos at = around.get();
                        Debug.logDebug("sticking with the " + (_logsOnly ? "tree" : "vein") + " at " + at.getX() + " " + at.getY() + " " + at.getZ());
                    }
                    return around;
                }
                // nothing left around it, that one is done. back to the nearest
                if (_anchorAnnounced) {
                    Debug.logDebug("done with that " + (_logsOnly ? "tree" : "vein"));
                }
                _anchor = null;
                _anchorAnnounced = false;
            }

            if (_stoneOnly) {
                return pickStone(mod, pos, usable, notOurs);
            }
            return orOwnBlock(mod, pos, usable, nearestBlock(mod, pos, notOurs));
        }

        // not a tree, see TreeReach.buried. all three asks go through here (the pick, isValid, stillOurs): a pursuit that is already
        // on its way never hears from the pick again, and a cached log in an unloaded chunk only shows what it is once we get close
        private boolean buriedLog(AltoClef mod, BlockPos pos) {
            return _logsOnly && _buried.buried(pos, check -> TreeReachScan.buried(mod, check));
        }

        // nothing of the real stuff within 16: a block we placed ourselves is a fair meal after all
        private Optional<BlockPos> orOwnBlock(AltoClef mod, Vec3 pos, Predicate<BlockPos> usable, Optional<BlockPos> closestBlock) {
            if (closestBlock.isEmpty() || closestBlock.get().distToCenterSqr(pos) > OWN_BLOCK_RANGE_SQ) {
                Optional<BlockPos> anything = nearestBlock(mod, pos, usable);
                if (anything.isPresent() && (closestBlock.isEmpty() || anything.get().distToCenterSqr(pos) < closestBlock.get().distToCenterSqr(pos))) {
                    return anything;
                }
            }
            return closestBlock;
        }

        // the tunnel we dig along once the only stone worth having is under our feet (see StoneTunnel)
        private final StoneTunnel _tunnel = new StoneTunnel();
        // the live look around our feet (see localStones) and what it was taken for
        private List<StoneDigRank.Scored> _local = List.of();
        private BlockPos _localFeet;
        private int _localFloor;
        private int _localTick;
        private boolean _localStale = true;
        // where latchTunnel last came up empty, see there
        private BlockPos _noTunnelAt;
        // how far around the feet the live look goes, and how long it is good for when nothing else changed. the walls of
        // the hole we just dug are the cheapest stone there is, and the tracker's snapshot (every 7 s, 25 a type, nearest
        // in plain 3d) is the last to hear about them
        private static final int LOCAL_RADIUS = 4;
        private static final int LOCAL_TICKS = 20;
        private static final int NO_FLOOR = Integer.MIN_VALUE;

        private Optional<BlockPos> pickStone(AltoClef mod, Vec3 pos, Predicate<BlockPos> usable, Predicate<BlockPos> notOurs) {
            Predicate<BlockPos> ok = check -> mod.getBlockTracker().blockIsValid(check, _blocks) && notOurs.test(check);
            if (tunnelOver(mod, pos, ok)) {
                _tunnel.unlatch();
            }
            Optional<BlockPos> best = bestStone(mod, pos, usable, notOurs, ok);
            if (best.isPresent() && latchTunnel(mod, pos, best.get(), ok)) {
                // the pick above was priced for the surface, the band prices things differently
                best = bestStone(mod, pos, usable, notOurs, ok);
            }
            return best.map(b -> reachableStone(mod, pos, b, ok));
        }

        // the tracker's best and the live look's best, whichever scores lower, then the own block fallback like everybody
        private Optional<BlockPos> bestStone(AltoClef mod, Vec3 pos, Predicate<BlockPos> usable, Predicate<BlockPos> notOurs, Predicate<BlockPos> ok) {
            Optional<BlockPos> cached = nearestBlock(mod, pos, notOurs);
            StoneDigRank.Scored local = firstOk(localStones(mod, pos), ok);
            Optional<BlockPos> best = cached;
            if (local != null && (cached.isEmpty() || local.score() < stoneScore(mod, pos.x, pos.y, pos.z, cached.get().getX(), cached.get().getY(), cached.get().getZ()))) {
                best = Optional.of(local.pos());
            }
            return orOwnBlock(mod, pos, usable, best);
        }

        // the cheapest one that passes, the list is sorted
        private static StoneDigRank.Scored firstOk(List<StoneDigRank.Scored> stones, Predicate<BlockPos> ok) {
            for (StoneDigRank.Scored s : stones) {
                if (ok.test(s.pos())) {
                    return s;
                }
            }
            return null;
        }

        // the stone we were asked for around the feet, read straight off the world, scored and sorted (see scanAround).
        // kept while we stand in the same block, the tunnel is the same and nothing broke, the walk in between does not
        // need 300 reads a tick
        private List<StoneDigRank.Scored> localStones(AltoClef mod, Vec3 pos) {
            BlockPos feet = mod.getPlayer().blockPosition();
            int floor = _tunnel.latched() ? _tunnel.floor() : NO_FLOOR;
            int now = WorldHelper.getTicks();
            boolean fresh = !_localStale && feet.equals(_localFeet) && floor == _localFloor && now >= _localTick && now - _localTick < LOCAL_TICKS;
            if (fresh) {
                return _local;
            }
            _localStale = false;
            _localFeet = feet;
            _localFloor = floor;
            _localTick = now;
            _local = scanAround(mod, pos, feet, floor);
            return _local;
        }

        // the rows around our feet, and the band when there is one. the rows in between are a climb from the band and the
        // band is free, so they never win while it has stone, and from up top reading them all was ten thousand reads a scan
        private List<StoneDigRank.Scored> scanAround(AltoClef mod, Vec3 pos, BlockPos feet, int floor) {
            List<StoneDigRank.Scored> out = new ArrayList<>();
            int low = feet.getY() - 1;
            int high = feet.getY() + 2;
            for (int y = low; y <= high; y++) {
                scanRow(mod, pos, feet, y, out);
            }
            if (floor != NO_FLOOR) {
                for (int y = floor; y <= floor + 1; y++) {
                    if (y < low || y > high) {
                        scanRow(mod, pos, feet, y, out);
                    }
                }
            }
            out.sort(Comparator.comparingDouble(StoneDigRank.Scored::score));
            return out;
        }

        private void scanRow(AltoClef mod, Vec3 pos, BlockPos feet, int y, List<StoneDigRank.Scored> out) {
            BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
            for (int x = feet.getX() - LOCAL_RADIUS; x <= feet.getX() + LOCAL_RADIUS; x++) {
                for (int z = feet.getZ() - LOCAL_RADIUS; z <= feet.getZ() + LOCAL_RADIUS; z++) {
                    if (isOneOfOurs(mod.getWorld().getBlockState(at.set(x, y, z)).getBlock())) {
                        out.add(new StoneDigRank.Scored(new BlockPos(x, y, z), stoneScore(mod, pos.x, pos.y, pos.z, x, y, z)));
                    }
                }
            }
        }

        private boolean isOneOfOurs(Block block) {
            for (Block b : _blocks) {
                if (b == block) {
                    return true;
                }
            }
            return false;
        }

        // the tunnel stops being a tunnel: we walked off, changed dimension, or the band ran out around us (a cave, water,
        // lava, a ravine took it). the next pick is a surface pick again and may latch a new one
        private boolean tunnelOver(AltoClef mod, Vec3 pos, Predicate<BlockPos> ok) {
            if (!_tunnel.latched()) {
                return false;
            }
            BlockPos feet = mod.getPlayer().blockPosition();
            if (_tunnel.left(feet.getX(), feet.getZ(), WorldHelper.getCurrentDimension())) {
                return true;
            }
            return !bandLeft(mod, pos, _tunnel.floor(), ok);
        }

        // some stone left in the band near us. the live look first, then what the tracker knows: a room we cleared out past
        // the live look still has walls, and walking to one beats calling the tunnel done and going down
        private boolean bandLeft(AltoClef mod, Vec3 pos, int floor, Predicate<BlockPos> ok) {
            Predicate<BlockPos> inBand = check -> StoneDigRank.inBand(check.getY(), floor) && ok.test(check);
            for (StoneDigRank.Scored s : localStones(mod, pos)) {
                if (inBand.test(s.pos())) {
                    return true;
                }
            }
            for (BlockPos known : mod.getBlockTracker().getKnownLocations(_blocks)) {
                if (known.distToCenterSqr(pos) <= BAND_KNOWN_SQ && inBand.test(known)) {
                    return true;
                }
            }
            return false;
        }

        // 16 blocks, squared. a band wall further than this is a walk, not the tunnel we are in
        private static final double BAND_KNOWN_SQ = 256;

        // the floor we last walked, kept past the task (the parent rebuilds it after every craft) so coming back to the same
        // room gets the same band. see StoneTunnel.reuse and StoneDigRank.pickFloor. and which world it is from: a new
        // world's spawn can sit right where the old one's tunnel was
        private static final StoneTunnel BAND_MEMORY = new StoneTunnel();
        private static int bandWorld;

        private static StoneTunnel bandMemory(AltoClef mod) {
            int world = System.identityHashCode(mod.getWorld());
            if (world != bandWorld) {
                BAND_MEMORY.clear();
                bandWorld = world;
            }
            return BAND_MEMORY;
        }

        // the best stone is under our feet and nothing near is cheap, which is how a column straight down started. find the
        // stone line under us and commit to walking it instead. true when we latched
        private boolean latchTunnel(AltoClef mod, Vec3 pos, BlockPos best, Predicate<BlockPos> ok) {
            BlockPos feet = mod.getPlayer().blockPosition();
            // a spot that already said no (no stone line, or a cave where the band would be) says no until we step off it,
            // the deep look is not something to redo every tick
            if (_tunnel.latched() || feet.equals(_noTunnelAt)) {
                return false;
            }
            if (!StoneDigRank.shouldTunnel(feet.getY(), best.getY(), stoneScore(mod, pos.x, pos.y, pos.z, best.getX(), best.getY(), best.getZ()))) {
                return false;
            }
            var dim = WorldHelper.getCurrentDimension();
            OptionalInt world = floorUnder(mod, feet);
            OptionalInt floor = StoneDigRank.pickFloor(world, bandMemory(mod).reuse(feet.getX(), feet.getZ(), feet.getY(), dim));
            // the remembered one can be empty where the world's one isn't, so that gets its own go
            if (tryBand(mod, pos, feet, floor, ok) || (!floor.equals(world) && tryBand(mod, pos, feet, world, ok))) {
                return true;
            }
            _noTunnelAt = feet;
            return false;
        }

        // latch on floor if its band has stone near us. a band with nothing in it is a cave floor, not a tunnel
        private boolean tryBand(AltoClef mod, Vec3 pos, BlockPos feet, OptionalInt floor, Predicate<BlockPos> ok) {
            if (floor.isEmpty()) {
                return false;
            }
            var dim = WorldHelper.getCurrentDimension();
            // latched first so the live look reaches the band (and its scores are band scores, it keys on the floor)
            _tunnel.latch(floor.getAsInt(), feet.getX(), feet.getZ(), dim);
            if (!bandLeft(mod, pos, floor.getAsInt(), ok)) {
                _tunnel.clear();
                return false;
            }
            bandMemory(mod).latch(floor.getAsInt(), feet.getX(), feet.getZ(), dim);
            return true;
        }

        // StoneDigRank.tunnelFloor, asked about the world under our feet
        private static OptionalInt floorUnder(AltoClef mod, BlockPos feet) {
            long gameTime = mod.getWorld().getGameTime();
            BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
            return StoneDigRank.tunnelFloor(feet.getY(), underStoneLine(mod, feet, gameTime),
                    y -> naturalStone(mod, at.set(feet.getX(), y, feet.getZ()), gameTime),
                    y -> openCell(mod, at.set(feet.getX(), y, feet.getZ())));
        }

        // air or fluid: a hole in the column, see tunnelFloor
        private static boolean openCell(AltoClef mod, BlockPos at) {
            var state = mod.getWorld().getBlockState(at);
            return state.isAir() || !state.getFluidState().isEmpty();
        }

        // already in the stone: stone under us and beside our feet. that is the level we keep
        private static boolean underStoneLine(AltoClef mod, BlockPos feet, long gameTime) {
            if (!naturalStone(mod, feet.below(), gameTime)) {
                return false;
            }
            for (Direction d : Direction.Plane.HORIZONTAL) {
                if (naturalStone(mod, feet.relative(d), gameTime)) {
                    return true;
                }
            }
            return false;
        }

        // stone the world made, not a block we put there
        private static boolean naturalStone(AltoClef mod, BlockPos at, long gameTime) {
            return StoneDigRank.stoneLine(mod.getWorld().getBlockState(at).getBlock())
                    && !PlacedByUs.GLOBAL.recent(at.getX(), at.getY(), at.getZ(), gameTime);
        }

        // up on a tower next to the trunk, the highest log in reach first (TreeChop.topDown)
        private Optional<BlockPos> topDown(AltoClef mod, Predicate<BlockPos> inTree) {
            if (!_logsOnly) {
                return Optional.empty();
            }
            int feetY = mod.getPlayer().blockPosition().getY();
            // the whole trunk, not the anchor box: that one only goes LOG_DOWN under the last break, so its "base" was always
            // right under our hands and the tower never counted as a tower
            List<BlockPos> tree = new ArrayList<>();
            for (BlockPos at : mod.getBlockTracker().getKnownLocations(_blocks)) {
                if (TreeChop.inTrunk(_anchor, at) && inTree.test(at)) {
                    tree.add(at);
                }
            }
            Vec3 eye = mod.getPlayer().getEyePosition();
            return Optional.ofNullable(TreeChop.topDown(tree, feetY, check -> eye.distanceToSqr(Vec3.atCenterOf(check)) <= HOLD_REACH_SQ));
        }

        // the last reach decision. the pick runs every tick and raycasts aren't free, so standing in the same block with the
        // same best we just reuse the answer
        private BlockPos _reachFeet;
        private BlockPos _reachBest;
        private BlockPos _reachPick;
        // only stone this close to the eye gets a raycast, past it nothing is going to be in reach anyway
        private static final double REACH_LOOK_SQ = 5 * 5;

        // the score ranks from the feet and doesn't care about the dirt in front of the face, so the best stone can be one
        // we have to walk to while one just as good is right there to hit. when the best is out of reach, a near tie we can
        // hit from here wins (see StoneDigRank.preferReachable)
        private BlockPos reachableStone(AltoClef mod, Vec3 pos, BlockPos best, Predicate<BlockPos> ok) {
            BlockPos feet = mod.getPlayer().blockPosition();
            if (feet.equals(_reachFeet) && best.equals(_reachBest) && (_reachPick.equals(best) || ok.test(_reachPick))) {
                return _reachPick;
            }
            _reachFeet = feet;
            _reachBest = best;
            _reachPick = best;
            if (LookHelper.getReach(best).isPresent()) {
                return best;
            }
            Vec3 eye = mod.getPlayer().getEyePosition();
            double bestScore = stoneScore(mod, pos.x, pos.y, pos.z, best.getX(), best.getY(), best.getZ());
            List<StoneDigRank.Scored> near = new ArrayList<>();
            Set<BlockPos> seen = new HashSet<>();
            seen.add(best);
            for (StoneDigRank.Scored local : localStones(mod, pos)) {
                // sorted, so past the margin there is nothing preferReachable would take anyway
                if (local.score() > bestScore + StoneDigRank.REACH_MARGIN) {
                    break;
                }
                if (seen.add(local.pos()) && eye.distanceToSqr(Vec3.atCenterOf(local.pos())) <= REACH_LOOK_SQ && ok.test(local.pos())) {
                    near.add(local);
                }
            }
            for (BlockPos check : mod.getBlockTracker().getKnownLocations(_blocks)) {
                if (!seen.add(check) || eye.distanceToSqr(Vec3.atCenterOf(check)) > REACH_LOOK_SQ || !ok.test(check)) {
                    continue;
                }
                near.add(new StoneDigRank.Scored(check, stoneScore(mod, pos.x, pos.y, pos.z, check.getX(), check.getY(), check.getZ())));
            }
            BlockPos hittable = StoneDigRank.preferReachable(near, bestScore, StoneDigRank.REACH_MARGIN, StoneDigRank.REACH_CHECKS,
                    check -> LookHelper.getReach(check).isPresent());
            if (hittable != null) {
                _reachPick = hittable;
            }
            return _reachPick;
        }

        // a block of ours going away is a break, and its drop is a tick or two from existing. picking the next block
        // right now is how a log fell and the bot walked off to the next one (-119,69,71 then -119,70,71, no pickup between),
        // so we stand still until the drop shows. the pick that follows has the patience lock for the walk to it
        @Override
        protected void onPursuitGone(AltoClef mod, Object gone) {
            if (!(gone instanceof BlockPos pos) || !pos.equals(_miningPos)) {
                return;
            }
            // a tunnel's cobble lands in vacuum range on its own while the next block cracks, stopping for it is pure cost.
            // the hole it left gave its neighbours an air face though, so the live look goes again
            if (_stoneOnly) {
                _localStale = true;
                return;
            }
            if (mod.getBlockTracker().unreachable(pos) || mod.getBlockTracker().blockIsValid(pos, _blocks)) {
                // nobody broke it and nobody banned it, it just stopped being a tree. left set, the stall checker and dirtWanted
                // would go on working on that log the whole wander
                if (buriedLog(mod, pos)) {
                    _miningPos = null;
                }
                return;
            }
            Vec3 spot = Vec3.atCenterOf(pos);
            if (mod.getPlayer().getEyePosition().distanceToSqr(spot) > HOLD_REACH_SQ) {
                return;
            }
            // the break is booked (anchor and all) now, the wait must not look like a block we are failing to mine
            noteBreak(mod);
            _miningPos = null;
            _progressChecker.reset();
            expectDrop(false, spot);
        }

        @Override
        protected boolean dropSeen(AltoClef mod, Vec3 spot) {
            return DropWatch.anyNear(mod, spot, DROP_SEEN_RADIUS);
        }

        // a drop that fell further from the block than this was somebody else's
        private static final double DROP_SEEN_RADIUS = 5;

        // the block we were on is gone and we did not give up on it: that was a break, so that is where we are working.
        // stone has its own idea of where to dig next (see StoneDigRank), a neighbourhood would walk it down a shaft
        private void noteBreak(AltoClef mod) {
            BlockPos was = _miningPos;
            if (was == null || _stoneOnly || mod.getBlockTracker().unreachable(was)) {
                return;
            }
            if (!mod.getBlockTracker().blockIsValid(was, _blocks)) {
                _anchor = was;
                _miningPos = null;
            }
        }

        // the drop we are on, or null when there is none or it stopped being worth it
        private ItemEntity lockedDrop(AltoClef mod) {
            ItemEntity drop = _lockedDrop;
            if (drop == null) {
                return null;
            }
            if (!isValid(mod, drop)) {
                _lockedDrop = null;
                _patience.unlock();
                return null;
            }
            return drop;
        }

        // a tick spent walking to the locked drop (not one spent cracking a log on the way, or paused). false when the
        // patience ran out: stuck in leaves, a hole, a pathing hole. the pickup task would take it right back as nearest,
        // so it is banned there too
        private boolean spendOn(AltoClef mod, ItemEntity drop) {
            _patience.tick();
            _patience.progress(mod.getPlayer().distanceTo(drop));
            if (!_patience.expired()) {
                return true;
            }
            Debug.logInternal("Giving up on a drop that never came");
            _patience.giveUp();
            BanPolicy.dropGaveUp(mod.getBans(), drop.getId(), EntityTracker.describe(drop));
            mod.getClientBaritone().getPathingBehavior().forceCancel();
            _lockedDrop = null;
            return false;
        }

        // the nearest drop we want. skipInReach leaves out the ones vanilla is about to put in the bag for us, as long as
        // there is room for them (a full bag is the pickup task's job, it frees a slot when it is touching the drop)
        private Optional<ItemEntity> nearestDrop(AltoClef mod, Vec3 pos, boolean skipInReach) {
            ItemTarget[] wanted = dropTargets();
            if (wanted.length == 0 || !mod.getEntityTracker().itemDropped(wanted)) {
                return Optional.empty();
            }
            Vec3 feet = mod.getPlayer().position();
            Predicate<ItemEntity> worthIt = drop -> {
                if (!mod.getEntityTracker().isEntityReachable(drop) || !_onlyDrops.test(drop.blockPosition())) return false;
                if (!skipInReach) return true;
                Vec3 at = drop.position();
                return !DropPatience.alreadyInReach(at.x - feet.x, at.y - feet.y, at.z - feet.z)
                        || mod.getItemStorage().getSlotsThatCanFitInPlayerInventory(drop.getItem(), false).isEmpty();
            };
            return mod.getEntityTracker().getClosestItemDrop(pos, worthIt, wanted);
        }

        // 6 blocks, squared. anything this close gets picked up before we mine another block
        private static final double DROP_FIRST_RANGE_SQ = TreeChop.DROP_FIRST_RANGE_SQ;

        // 16 blocks, squared. past this a block we placed ourselves is a fair meal
        private static final double OWN_BLOCK_RANGE_SQ = 256;

        private Optional<BlockPos> nearestBlock(AltoClef mod, Vec3 pos, Predicate<BlockPos> usable) {
            // stone gets its own idea of near: sideways and at our level, not the floor (see StoneDigRank)
            if (_stoneOnly) {
                return mod.getBlockTracker().getNearestTracking(pos, usable, (fx, fy, fz, tx, ty, tz) -> stoneScore(mod, fx, fy, fz, tx, ty, tz), _blocks);
            }
            // logs want plain distance: baritone's heuristic prices up like sideways, so the log above us tied with the
            // one next door and below us was nearly free. ores keep the old pick, mining down to them is the point
            if (_logsOnly) {
                return mod.getBlockTracker().getNearestTracking(pos, usable, MineAnchor::distSq, _blocks);
            }
            return mod.getBlockTracker().getNearestTracking(pos, usable, _blocks);
        }

        // something to place with, asked the way the planner asks, so a tower we count on is one it will plan
        private static boolean hasThrowaway(AltoClef mod) {
            return mod.getClientBaritoneSettings().allowPlace.value && mod.getClientBaritone().getInventoryBehavior().hasGenericThrowaway();
        }

        // with placing off nothing we dig could be used, so there is no point in the trip
        private boolean dirtPossible(AltoClef mod, int now) {
            return !dirtLockedOut(now) && mod.getClientBaritoneSettings().allowPlace.value && _scan.dirtAround(mod, now);
        }

        // a lockout further off than any we set is from a world whose clock was not ours
        private static boolean dirtLockedOut(int now) {
            return now < dirtOffUntil && dirtOffUntil - now <= DIRT_OFF_TICKS;
        }

        // baritone has no way to the log and we have nothing to tower with: that's what dirt is for, the log isn't banned yet.
        // no dirtAround here, the getItemTask finds dirt on its own and a log we can't otherwise get is worth the walk
        private boolean askForDirt(AltoClef mod, BlockPos stuck) {
            // nether stems: no dirt down there, the trip would wander a minute and lock every high log out for five
            if (WorldHelper.getCurrentDimension() != Dimension.OVERWORLD) {
                return false;
            }
            // down a mine the tower goes nowhere, the gamer climbs out first (SmeltSurface.logTick)
            if (!TreeChop.dirtDepthOk(SmeltSurface.depthBelowSky(mod), SmeltSurface.GO_UP_DEPTH)) {
                return false;
            }
            int now = WorldHelper.getTicks();
            int gap = askGap(mod, stuck, now);
            // a log in swinging range isn't missing a tower, the trip would come back with dirt and still no path
            if (!TreeChop.highUp(gap)) {
                return false;
            }
            boolean placing = mod.getClientBaritoneSettings().allowPlace.value;
            boolean holding = mod.getClientBaritone().getInventoryBehavior().hasGenericThrowaway();
            if (!TreeChop.noPathDirt(placing, holding, dirtLockedOut(now), tripsOn(stuck, now), TreeChop.tripsFor(gap))) {
                return false;
            }
            _dirtAsk = stuck.immutable();
            _dirtAskGap = gap;
            return true;
        }

        // how high the tower goes. a flagged log knows its floor, an unflagged one is measured from our feet (it has a trunk
        // under it, so the no path is about getting near it, not up to it)
        private int askGap(AltoClef mod, BlockPos log, int now) {
            TreeReachScan.Look look = _scan.look(mod, log, now);
            if (look.flagged()) {
                return look.gap();
            }
            return Math.max(0, log.getY() - mod.getPlayer().blockPosition().getY());
        }

        // the log the next dirt trip is for, and the ask is used up
        BlockPos takeDirtLog() {
            BlockPos at = _dirtAsk != null ? _dirtAsk : _miningPos;
            _dirtAsk = null;
            return at;
        }

        void backFromDirt() {
            _progressChecker.newTarget();
            _noPath.disarm();
        }

        // false for a log high over the ground (canopy, or a trunk with its bottom cut away) when we have nothing to tower
        // with and no dirt to get it from. an intact trunk is never flagged, it opens up as it is cut
        private boolean worthTheTrip(AltoClef mod, BlockPos log) {
            int now = WorldHelper.getTicks();
            TreeReachScan.Look look = _scan.look(mod, log, now);
            // most logs are not even flagged, and the bag and the dirt look are not free
            if (!look.flagged() || look.fromGround()) {
                return true;
            }
            return !TreeReach.skip(true, false, hasThrowaway(mod), dirtPossible(mod, now));
        }

        // how much more dirt to dig before the log we are on. 0 when that one needs no tower, or we hold enough blocks for the
        // climb, or we are mid swing at it, or there is no dirt about
        private int dirtWanted(AltoClef mod) {
            if (_dirtAsk != null) {
                // askForDirt only asks with nothing to place in the bag, so the whole tower
                return TreeReach.dirtWanted(_dirtAskGap);
            }
            BlockPos at = _miningPos;
            if (!_logsOnly || at == null || mod.getControllerExtras().isBreakingBlock()) {
                return 0;
            }
            int now = WorldHelper.getTicks();
            TreeReachScan.Look look = _scan.look(mod, at, now);
            if (!look.flagged() || look.fromGround() || !dirtPossible(mod, now)) {
                return 0;
            }
            // one trip per tree on this road. a second one for a giant only comes from askForDirt, once the tower ran dry
            if (tripsOn(at, now) > 0) {
                return 0;
            }
            // what the planner places from, the same list hasGenericThrowaway goes by. the old hardcoded four missed the rest
            // of the list, so a bag full of andesite still sent us off digging dirt
            List<Item> throwaways = mod.getClientBaritoneSettings().acceptableThrowawayItems.value;
            int held = mod.getItemStorage().getItemCount(throwaways.toArray(Item[]::new));
            return TreeReach.dirtShortfall(look.gap(), held);
        }

        private static void dirtOff(int now, int ticks) {
            dirtOffUntil = now + ticks;
        }

        private static void dirtTrip(BlockPos log, int now) {
            dirtTreeTrips = tripsOn(log, now) + 1;
            dirtTreeAt = log;
            dirtTreeTick = now;
        }

        // trips already made for this log's tree, while they still count
        private static int tripsOn(BlockPos log, int now) {
            boolean recent = dirtTreeAt != null && log != null && now >= dirtTreeTick && now - dirtTreeTick < DIRT_TREE_TICKS
                    && TreeStrikes.sameTree(dirtTreeAt, log);
            return recent ? dirtTreeTrips : 0;
        }

        // the nether has no floating blocks of its own, so one over a drop is a bridge of ours (or a ledge) and stays up
        private static boolean hangsInTheNether(AltoClef mod, BlockPos at) {
            if (WorldHelper.getCurrentDimension() != Dimension.NETHER) return false;
            return FloatingBlock.floating(k -> {
                BlockState below = mod.getWorld().getBlockState(at.below(k));
                return below.isAir() || below.getFluidState().is(FluidTags.LAVA);
            });
        }

        // one of the blocks we are after, still standing, not given up on, and close enough to keep swinging at
        private boolean stillOurs(AltoClef mod, BlockPos pos) {
            if (mod.getBlockTracker().unreachable(pos)) return false;
            if (hangsInTheNether(mod, pos)) return false;
            if (buriedLog(mod, pos)) return false;
            if (!mod.getBlockTracker().blockIsValid(pos, _blocks)) return false;
            // same answer as isValid, or a block that went unbreakable got dropped there and handed straight back here
            if (!WorldHelper.canBreak(mod, pos)) return false;
            // a block we placed is scaffolding, holding on to it skipped the notOurs filter in pickBlock. stone only: everyone
            // else has the "nothing else within 16, take our own" fallback, and dropping that pick every tick would just repick it
            if (_stoneOnly && PlacedByUs.GLOBAL.recent(pos.getX(), pos.getY(), pos.getZ(), mod.getWorld().getGameTime())) return false;
            return mod.getPlayer().getEyePosition().distanceToSqr(Vec3.atCenterOf(pos)) <= HOLD_REACH_SQ;
        }

        // the mid-break block is only worth holding on to while we can still hit it
        private static final double HOLD_REACH_SQ = 5.5 * 5.5;

        // the block we are breaking beats a never-tried one no matter how close the new one is. a drop beats a block we
        // haven't started yet: getClosestTo only hands one out when it should win, and the one the break just made shows up
        // a tick after the block is gone, by when we already picked the next log. once the block is started (MineStick) or
        // we are on a drop, nothing but a drop about to despawn moves us
        @Override
        protected boolean mustSwitchTo(AltoClef mod, Object current, Object candidate) {
            if (candidate instanceof ItemEntity drop) {
                boolean held = current instanceof BlockPos at && _stick.isHolding(at);
                return MineStick.dropMayTakeOver(current instanceof ItemEntity, held, MineStick.aboutToDespawn(drop.getAge()));
            }
            return candidate instanceof BlockPos b && _stick.isHolding(b);
        }

        // stone goes by its own score, not distance. the remembered heuristic retry is out: it compares path costs baritone
        // worked out from wherever we stood back then, and a deep stone that once looked cheap keeps winning on that. a new
        // stone takes over when it is SWITCH_MARGIN cheaper from where we stand, a drop the way it always did minus the retry
        @Override
        protected SwitchCall switchCall(AltoClef mod, Object current, Object candidate) {
            if (!_stoneOnly) {
                return SwitchCall.NO_OPINION;
            }
            if (mustSwitchTo(mod, current, candidate)) {
                return SwitchCall.SWITCH;
            }
            if (current instanceof BlockPos now && candidate instanceof BlockPos next) {
                // without the standing-on part: two stones side by side under us each pay it from their own column, one
                // step after either moves our feet over the line, and they took turns every tick while we stood there
                return StoneDigRank.switches(switchScore(mod, now), switchScore(mod, next)) ? SwitchCall.SWITCH : SwitchCall.STAY;
            }
            Vec3 me = mod.getPlayer().position();
            return MineStick.clearlyCloser(getPos(mod, candidate).distanceToSqr(me), getPos(mod, current).distanceToSqr(me)) ? SwitchCall.SWITCH : SwitchCall.STAY;
        }

        @Override
        public void resetSearch() {
            super.resetSearch();
            forgetFocus();
        }

        private void forgetFocus() {
            forgetDropExpect();
            _anchor = null;
            _anchorAnnounced = false;
            _lockedDrop = null;
            _patience.clear();
            _dirtAsk = null;
            _sweepTree = null;
            _tunnel.clear();
            _localStale = true;
            _noTunnelAt = null;
        }

        @Override
        protected Vec3 getOriginPos(AltoClef mod) {
            return mod.getPlayer().position();
        }

        // the buddy's fork: with ores hidden from the client (legitMine) the ores it sees are a cave's, and a cave killed it
        // without iron armour, to zombies and skeletons, within minutes (the runner, 1 Nov 2026). until it wears the whole iron
        // set (15 points) an ore counts within five blocks only, the ones its own branch tunnel opens; the rest is the branch
        // mine's (getWanderTask below). (10, a chestplate and leggings, let it walk into a cave to an ore it saw there, where
        // two skeletons shot it dead, the runner, 2 Nov 2026)
        // (by what is looked for, not by the block in the world: an ore the tracker knows 200 blocks off lies in a chunk the
        // client has unloaded, where the world says air, and the rule let it through; it walked to coal 200 blocks off
        // through the caves without armour, for its shield's smelting, the runner, 2 Nov 2026, attempt 87)
        private static boolean nearUnarmoured(AltoClef mod, BlockPos pos, Block[] blocks) {
            if (!Baritone.settings().legitMine.value || mod.getPlayer() == null) return true;
            boolean ore = false, gold = false;
            for (Block b : blocks) {
                String id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(b).getPath();
                ore |= id.endsWith("_ore");
                gold |= id.endsWith("gold_ore") && !id.contains("nether");
            }
            if (!ore || pos.closerToCenterThan(mod.getPlayer().position(), 5)) return true;
            if (mod.getPlayer().getArmorValue() < 15) return false;
            // (the buddy's fork, Tuning gold_cave_safe, on by default, 12 Nov 2026, steering 170: with the golden helmet on (15
            // armour) the cave gold the tracker knows counts, not only the tunnel's own walls; only gold at y 0 or above, out of
            // the deep dark's depth where a warden killed a buddy in full iron (3 Nov), and none beside lava)
            if (gold && adris.altoclef.util.helpers.Tuning.get("gold_cave_safe", 0) > 0) {
                if (pos.getY() < 0) return false;
                for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) {
                    if (mod.getWorld().getFluidState(pos.relative(d)).is(net.minecraft.tags.FluidTags.LAVA)) return false;
                }
            }
            return true;
        }

        @Override
        protected Task getWanderTask(AltoClef mod) {
            // the buddy's fork: with ores hidden from the client (legitMine) a walk over the ground never shows one. down to
            // the ore's height and a branch mine there instead
            if (Baritone.settings().legitMine.value && WorldHelper.getCurrentDimension() == Dimension.OVERWORLD) {
                Integer y = adris.altoclef.tasks.movement.BranchMineTask.bestY(_blocks);
                if (y != null) {
                    return new adris.altoclef.tasks.movement.BranchMineTask(_blocks, y);
                }
            }
            return super.getWanderTask(mod);
        }

        @Override
        protected boolean stillLooking(AltoClef mod) {
            // a drop we can see is an answer too, but getClosestTo already counts those before we get asked
            return mod.getBlockTracker().scanPending(_blocks);
        }

        @Override
        protected Task onTick(AltoClef mod) {
            if (mod.getClientBaritone().getPathingBehavior().isPathing()) {
                _progressChecker.reset();
            }
            NoPathScope.Verdict noPath = noPathVerdict(mod);
            if (noPath != NoPathScope.Verdict.NONE) {
                giveUpNoPath(mod, noPath);
            }
            if (_miningPos != null && !_progressChecker.check(mod, _miningPos)) {
                mod.getClientBaritone().getPathingBehavior().forceCancel();
                if (_logsOnly && askForDirt(mod, _miningPos)) {
                    // same deal as giveUpNoPath: a path that goes nowhere with an empty bag gets a tower before a ban
                    Debug.logMessage("Got nowhere on the log at " + _miningPos.toShortString() + ", getting dirt to tower up to it.");
                    _miningPos = null;
                    _progressChecker.newTarget();
                    return super.onTick(mod);
                }
                Debug.logMessage("Failed to mine block. Suggesting it may be unreachable.");
                // a short ban right away (the tracker honours it, so the next pick is somewhere else) and a strike, three of which
                // is the long one. the short one used to be a set of this task's own that no other task could see
                BanPolicy.miningStalled(mod.getBans(), WorldHelper.getCurrentDimension(), _miningPos.getX(), _miningPos.getY(), _miningPos.getZ());
                mod.getBlockTracker().requestBlockUnreachable(_miningPos, 2, "mining got nowhere");
                if (_logsOnly) {
                    giveUpTree(mod, _miningPos);
                }
                _miningPos = null;
                _progressChecker.newTarget();
            }
            return super.onTick(mod);
        }

        // baritone's search for the block came back empty. the six second progress checker would get to the same ban, 6 s
        // (and a failed search or three) later, standing still the whole time. NONE when we aren't after a block, were away
        // from the wheel (the failures counted then were somebody else's asks), or can swing at it from here anyway
        private NoPathScope.Verdict noPathVerdict(AltoClef mod) {
            int now = WorldHelper.getTicks();
            boolean away = now - _lastTickAt > 2;
            _lastTickAt = now;
            // a block that is gone or already given up on is not what a search is for anymore (the wander that takes over from
            // a pursuit that ended would get blamed on it)
            if (_miningPos == null || mod.getBlockTracker().unreachable(_miningPos) || !mod.getBlockTracker().blockIsValid(_miningPos, _blocks)) {
                _noPath.disarm();
                return NoPathScope.Verdict.NONE;
            }
            var pathing = mod.getClientBaritone().getPathingBehavior();
            // failures first, then exhausted: the pathing thread writes them the other way round
            int failures = pathing.calcFailures();
            int exhausted = pathing.calcExhausted();
            boolean inReach = mod.getPlayer().getEyePosition().distanceToSqr(Vec3.atCenterOf(_miningPos)) <= HOLD_REACH_SQ
                    || mod.getControllerExtras().isBreakingBlock();
            if (away || inReach || !_noPath.armed()) {
                _noPath.arm(failures, exhausted);
                return NoPathScope.Verdict.NONE;
            }
            return _noPath.poll(failures, exhausted, pathing.isPathing());
        }

        // the block, and when the search looked at everything, the rest of its vein for a short while. a timed out search says
        // nothing about the neighbours, they may be a short walk from where it gave up
        private void giveUpNoPath(AltoClef mod, NoPathScope.Verdict verdict) {
            BlockPos stuck = _miningPos;
            var dim = WorldHelper.getCurrentDimension();
            mod.getClientBaritone().getPathingBehavior().forceCancel();
            if (_logsOnly && askForDirt(mod, stuck)) {
                // no ban, no strike: with dirt in the bag baritone towers up beside the trunk. a second no path after the trip
                // (tripsOn) is the real one and lands below
                Debug.logMessage("No way to the log at " + stuck.getX() + " " + stuck.getY() + " " + stuck.getZ() + " yet, getting dirt to tower up to it.");
                _miningPos = null;
                _progressChecker.newTarget();
                _noPath.disarm();
                return;
            }
            // stone and logs have no veins to speak of (a hillside, a tree with its own rules)
            boolean veins = verdict == NoPathScope.Verdict.EXHAUSTED && !_stoneOnly && !_logsOnly;
            List<BlockPos> cells = NoPathScope.cells(stuck, veins ? mod.getBlockTracker().getKnownLocations(_blocks) : List.of(), verdict, veins);
            BanPolicy.noPathBlock(mod.getBans(), dim, stuck.getX(), stuck.getY(), stuck.getZ());
            Set<Bans.Key> vein = new LinkedHashSet<>();
            for (int i = 1; i < cells.size(); i++) {
                BlockPos cell = cells.get(i);
                vein.add(Bans.Key.block(dim, cell.getX(), cell.getY(), cell.getZ()));
            }
            if (!vein.isEmpty()) {
                BanPolicy.noPathVein(mod.getBans(), vein, stuck.getX(), stuck.getY(), stuck.getZ());
            }
            // a strike either way, like the stall this replaces: a block that times out every time has to reach the long ban.
            // a timed out search to something far off gets the long ban at once: walking toward it resets the strike count
            // (the ban book counts a closer approach as a new try), so the same far block came back every couple of minutes
            mod.getBlockTracker().requestBlockUnreachable(stuck,
                    NoPathScope.strikesAllowed(verdict, mod.getPlayer().blockPosition().distSqr(stuck)), "no path to it");
            if (_logsOnly) {
                // the stall used to be what counted a tree's strikes, and the pick relies on them to leave a canopy
                giveUpTree(mod, stuck);
            }
            Debug.logMessage("No way to the block at " + stuck.getX() + " " + stuck.getY() + " " + stuck.getZ()
                    + (verdict == NoPathScope.Verdict.EXHAUSTED ? " (searched it all)" : " (search timed out)")
                    + (vein.isEmpty() ? "" : ", skipping its vein too") + ".");
            _miningPos = null;
            _progressChecker.newTarget();
            _noPath.disarm();
        }

        // the per block ban is over in a minute and the same canopy came right back. the second stall on one tree gives up on
        // all of it for five minutes (it goes in the shared ban book, so any other task honours it too), and the anchor goes so
        // the next pick can't walk back into the tree it was just banned from
        private void giveUpTree(AltoClef mod, BlockPos stalled) {
            var dim = WorldHelper.getCurrentDimension();
            List<BlockPos> spots = STRIKES.strike(dim, stalled, WorldHelper.getTicks());
            if (spots.isEmpty()) {
                return;
            }
            // the spots that stalled go in themselves, the tracker may not know a log we were standing at
            Set<Bans.Key> logs = new LinkedHashSet<>();
            for (BlockPos spot : spots) {
                logs.add(Bans.Key.block(dim, spot.getX(), spot.getY(), spot.getZ()));
            }
            for (BlockPos log : mod.getBlockTracker().getKnownLocations(_blocks)) {
                for (BlockPos spot : spots) {
                    if (TreeStrikes.sameTree(spot, log)) {
                        logs.add(Bans.Key.block(dim, log.getX(), log.getY(), log.getZ()));
                        break;
                    }
                }
            }
            BanPolicy.treeGaveUp(mod.getBans(), logs, stalled.getX(), stalled.getY(), stalled.getZ());
            _anchor = null;
            _anchorAnnounced = false;
            Debug.logMessage("Giving up on the tree at " + stalled.getX() + " " + stalled.getY() + " " + stalled.getZ() + ", on to another one.");
        }

        @Override
        protected Task getGoalTask(Object obj) {
            if (obj instanceof BlockPos newPos) {
                if (_miningPos == null || !_miningPos.equals(newPos)) {
                    _progressChecker.newTarget();
                    _noPath.disarm();
                }
                _miningPos = newPos;
                return new DestroyBlockTask(_miningPos);
            }
            if (obj instanceof ItemEntity drop) {
                _miningPos = null;
                BlockPos leaf = leafUnder(drop);
                return leaf != null ? new DestroyBlockTask(leaf) : pickupTask();
            }
            throw new UnsupportedOperationException("Shouldn't try to get the goal from object " + obj + " of type " + (obj != null ? obj.getClass().toString() : "(null object)"));
        }

        // a log drop that landed on the canopy. pathing up there is a climb through leaves that DropPatience gives up on,
        // the leaf under it is one swing and the drop comes down to us
        private BlockPos leafUnder(ItemEntity drop) {
            AltoClef mod = AltoClef.getInstance();
            if (!_logsOnly || mod == null || mod.getPlayer() == null) {
                return null;
            }
            BlockPos under = drop.blockPosition().below();
            boolean leaf = mod.getWorld().getBlockState(under).is(BlockTags.LEAVES);
            return TreeChop.cutLeafUnder(drop.getY() - mod.getPlayer().getY(), leaf) ? under : null;
        }

        @Override
        protected boolean isValid(AltoClef mod, Object obj) {
            if (obj instanceof BlockPos b) {
                // a block we gave up on is not valid anymore, or the "new target must be twice as close" rule would
                // keep us on it (it used to be swapped out for any other nearest block, which is how it got away with this)
                return !mod.getBlockTracker().unreachable(b) && !hangsInTheNether(mod, b) && !buriedLog(mod, b)
                        && mod.getBlockTracker().blockIsValid(b, _blocks) && WorldHelper.canBreak(mod, b);
            }
            if (obj instanceof ItemEntity drop) {
                // picked up or despawned, don't keep chasing a ghost. one we gave up on or the pickup task banned stays dead
                if (!drop.isAlive() || !mod.getEntityTracker().isEntityReachable(drop)) return false;
                // never worth a swim, whatever the water setting says
                if (ItemPickupRules.lavaBlocksPickup(drop)) return false;
                // in the water the pickup task can't see it anymore and would wander until the patience ran out
                if (!Baritone.settings().altoPickupItemsInWater.value && !ItemPickupRules.isPickupSafe(drop)) return false;
                Item item = drop.getItem().getItem();
                ItemTarget[] wanted = dropTargets();
                if (wanted != null) {
                    for (ItemTarget target : wanted) {
                        if (target.matches(item)) return true;
                    }
                }
                return false;
            }
            return false;
        }

        @Override
        protected void onStart(AltoClef mod) {
            _progressChecker.newTarget();
            _noPath.disarm();
            _miningPos = null;
            _stick.clear();
            forgetFocus();
        }

        @Override
        protected void onStop(AltoClef mod, Task interruptTask) {

        }

        @Override
        protected boolean isEqual(Task other) {
            if (other instanceof MineOrCollectTask task) {
                return Arrays.equals(task._blocks, _blocks) && Arrays.equals(task._targets, _targets);
            }
            return false;
        }

        @Override
        protected String toDebugString() {
            return "Mining or Collecting";
        }

        @Override
        protected String toHudString() {
            return "Looking for " + HudText.some(_targets);
        }

        @Override
        protected boolean isHudPlumbing() {
            return true;
        }

        public boolean isMining() {
            return _miningPos != null;
        }

        public BlockPos miningPos() {
            return _miningPos;
        }
    }

}
