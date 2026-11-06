package adris.altoclef.tasks.resources;

import adris.altoclef.AltoClef;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.ResourceTask;
import adris.altoclef.tasks.container.CraftInTableTask;
import adris.altoclef.tasks.container.LootContainerTask;
import adris.altoclef.tasks.misc.EquipArmorTask;
import adris.altoclef.tasks.movement.DefaultGoToDimensionTask;
import adris.altoclef.tasks.movement.SearchChunkForBlockTask;
import adris.altoclef.tasks.slot.EnsureFreeInventorySlotTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.util.CraftingRecipe;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.RecipeTarget;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.WorldHelper;
import baritone.api.utils.Dimension;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

public class GetSmithingTemplateTask extends ResourceTask {

    // plain blackstone is half of basalt deltas, so it says nothing. these two only show up in bastions (same pick as NetherPhase)
    private static final Block[] BASTION = {Blocks.POLISHED_BLACKSTONE_BRICKS, Blocks.GILDED_BLACKSTONE};
    // a bastion is big but not that big, a chest more than ~3 chunks (about 40 blocks) from any of its bricks is someone's base
    private static final int BASTION_CHUNK_RADIUS = 3;
    // a chest with no bricks around it gets asked again after this, bricks further out may have loaded by then
    private static final int NOT_BASTION_RECHECK_TICKS = 100;
    // one boot is enough to keep piglins calm when we open their chest, and it costs the least armor to swap in
    private static final Item[] GOLD_PIECES = {Items.GOLDEN_BOOTS, Items.GOLDEN_HELMET, Items.GOLDEN_LEGGINGS, Items.GOLDEN_CHESTPLATE};
    private static final Item TEMPLATE = Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE;
    // a chest we can't reach would hold the loot task hostage, so each one gets two minutes of our own ticks
    private static final int CHEST_TICKS = 20 * 120;
    private static final int DUPE_TICKS = 20 * 120;
    // a bastion fits in about 5x5 chunks, so 4 around each opened chest covers it from any corner
    private static final int DONE_CHUNK_RADIUS = 4;

    private final Set<ChunkPos> _doneChunks = new HashSet<>();
    // SearchChunksExploreTask forgets what it explored every time it restarts, and it restarts on every chest and every
    // mob fight. so we remember which bastions are picked clean ourselves
    private final Task _searcher = new SearchChunkForBlockTask(BASTION) {
        @Override
        protected boolean isChunkWithinSearchSpace(AltoClef mod, ChunkPos pos) {
            return !_doneChunks.contains(pos) && super.isChunkWithinSearchSpace(mod, pos);
        }
    };
    private final int _count;
    // chests we already opened. hoglin stable bastions often have no template at all, and walking back to an empty
    // chest forever is not a plan
    private final Set<BlockPos> _visited = new HashSet<>();
    // chests already known to sit in a bastion. a chest only ever goes from "no bricks seen near it" to "bricks", never back
    private final Set<BlockPos> _inBastion = new HashSet<>();
    private final Map<BlockPos, Integer> _notBastionUntil = new HashMap<>();
    // chests we actually looked inside. only these finish off a bastion, a chest we gave up on says nothing about its neighbours
    private final Set<BlockPos> _opened = new HashSet<>();
    private int _ticks;
    private LootContainerTask _lootTask;
    // ticks spent on the current chest, counted only while we're the one ticking. a game timer would also count mob
    // fights and running away, and give up on chests we never even got to
    private int _lootTicks;
    private Task _dupeTask;
    private int _dupeTarget = -1;
    private int _dupeTicks;
    private boolean _dupeGaveUp;

    public GetSmithingTemplateTask(int count) {
        super(TEMPLATE, count);
        _count = count;
    }

    @Override
    protected void onResourceStart(AltoClef mod) {
        mod.getBlockTracker().trackBlock(Blocks.CHEST);
    }

    @Override
    protected Task onResourceTick(AltoClef mod) {
        _ticks++;
        Task dupe = duplicateTask(mod);
        if (dupe != null) {
            setDebugState("Copying a template with diamonds");
            return dupe;
        }
        if (WorldHelper.getCurrentDimension() != Dimension.NETHER) {
            setDebugState("Going to nether");
            return new DefaultGoToDimensionTask(Dimension.NETHER);
        }
        if (_lootTask != null) {
            Task loot = lootTick(mod);
            if (loot != null) {
                return loot;
            }
        }
        Optional<BlockPos> chest = nextBastionChest(mod);
        if (chest.isPresent()) {
            _lootTask = new LootContainerTask(chest.get(), List.of(TEMPLATE));
            _lootTicks = 0;
            return null;
        }
        // every chest we know around here is opened, so the bastions they're in are done. the searcher skips those
        // chunks and wanders off until a fresh bastion loads in
        for (BlockPos pos : _opened) {
            ChunkPos c = ChunkPos.containing(pos);
            for (int dx = -DONE_CHUNK_RADIUS; dx <= DONE_CHUNK_RADIUS; dx++) {
                for (int dz = -DONE_CHUNK_RADIUS; dz <= DONE_CHUNK_RADIUS; dz++) {
                    _doneChunks.add(new ChunkPos(c.x() + dx, c.z() + dz));
                }
            }
        }
        setDebugState("Searching for a bastion");
        return _searcher;
    }

    // null once this chest is done with (opened, gone, unreachable or out of time)
    private Task lootTick(AltoClef mod) {
        if (_lootTask.isFinished(mod) || !mod.getBlockTracker().blockIsValid(_lootTask.chest, Blocks.CHEST) || ++_lootTicks > CHEST_TICKS) {
            if (_lootTask.isFinished(mod)) {
                _opened.add(_lootTask.chest);
            }
            markVisited(mod, _lootTask.chest);
            _lootTask = null;
            return null;
        }
        Task gold = goldTask(mod);
        if (gold != null) {
            setDebugState("Putting on gold so the piglins stay chill");
            return gold;
        }
        // a full bag makes LootContainerTask see "nothing i can take" and call a template chest empty
        if (mod.getItemStorage().getSlotThatCanFitInPlayerInventory(new ItemStack(TEMPLATE), false).isEmpty()) {
            setDebugState("Making room for the template");
            return new EnsureFreeInventorySlotTask();
        }
        setDebugState("Looting a bastion chest");
        // same instance every tick, LootContainerTask compares its target list by identity and would restart otherwise
        return _lootTask;
    }

    // a double chest is two tracked chests and one inventory, opening one half already looked in both
    private void markVisited(AltoClef mod, BlockPos chest) {
        _visited.add(chest);
        BlockPos left = WorldHelper.getChestLeft(mod, chest);
        if (left == null) {
            return;
        }
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos other = chest.relative(dir);
            if (left.equals(WorldHelper.getChestLeft(mod, other))) {
                _visited.add(other);
            }
        }
    }

    // vanilla 26.3: 7 diamonds + 1 netherrack + 1 template -> 2 templates. only done with everything already in the bag.
    // the upgrade collects its diamond piece before it asks for the template (CataloguedResourceTask puts the craft first),
    // so loose diamonds here are spares. we never go mining for them, a bastion trip is the fallback
    private Task duplicateTask(AltoClef mod) {
        int have = mod.getItemStorage().getItemCount(TEMPLATE);
        if (_dupeGaveUp || have < 1 || have >= _count || mod.getItemStorage().getItemCount(Items.DIAMOND) < 7) {
            return null;
        }
        if (mod.getItemStorage().getItemCount(Items.NETHERRACK) < 1) {
            // netherrack is a nether trip for an overworld bot, still way cheaper than a bastion
            return TaskCatalogue.getItemTask("netherrack", 1);
        }
        // no table and no wood in the nether can stall the craft forever while we sit on everything else. bastion it is.
        // counted after the netherrack, a portal trip alone can take longer than this
        if (++_dupeTicks > DUPE_TICKS) {
            _dupeGaveUp = true;
            return null;
        }
        // one copy at a time, each one eats a template and seven diamonds, and we look again once it lands
        if (_dupeTask == null || _dupeTarget != have + 1) {
            _dupeTarget = have + 1;
            _dupeTask = new CraftInTableTask(new RecipeTarget(TEMPLATE, _dupeTarget, dupeRecipe()), false, true);
        } else if (_dupeTask.isFinished(mod)) {
            // the craft gave up without the copy landing. handing it back would just sit there, go loot instead
            return null;
        }
        return _dupeTask;
    }

    // public so the recipe parity test can hold it against the vanilla json
    public static CraftingRecipe dupeRecipe() {
        ItemTarget d = new ItemTarget(Items.DIAMOND, 1);
        ItemTarget s = new ItemTarget(TEMPLATE, 1);
        ItemTarget c = new ItemTarget(Items.NETHERRACK, 1);
        return CraftingRecipe.newShapedRecipe("netherite_upgrade_smithing_template", new ItemTarget[]{d, s, d, d, c, d, d, d, d}, 2);
    }

    // only gold we already own. EquipArmorTask goes shopping for anything missing, so it only gets pieces in the bag
    private static Task goldTask(AltoClef mod) {
        if (StorageHelper.isArmorEquipped(mod, GOLD_PIECES)) {
            return null;
        }
        for (Item piece : GOLD_PIECES) {
            if (mod.getItemStorage().getItemCountInventoryOnly(piece) > 0) {
                return new EquipArmorTask(piece);
            }
        }
        return null;
    }

    private Optional<BlockPos> nextBastionChest(AltoClef mod) {
        BlockPos me = mod.getPlayer().blockPosition();
        return mod.getBlockTracker().getKnownLocations(Blocks.CHEST).stream()
                .filter(pos -> !_visited.contains(pos) && mod.getBlockTracker().blockIsValid(pos, Blocks.CHEST))
                .filter(pos -> inBastion(mod, pos))
                .min(Comparator.comparingDouble(pos -> pos.distSqr(me)));
    }

    // getNearestWithinRange walks every block in an 81 block cube, and the block tracker only keeps the 25 bricks
    // nearest the player, which makes a chest across the bastion look like it's in the middle of nowhere.
    // the chunk palettes know about every brick and skip whole sections, same question SearchChunkForBlockTask asks
    private boolean inBastion(AltoClef mod, BlockPos chest) {
        if (_inBastion.contains(chest)) {
            return true;
        }
        Integer until = _notBastionUntil.get(chest);
        if (until != null && _ticks < until) {
            return false;
        }
        ChunkPos c = ChunkPos.containing(chest);
        for (int dx = -BASTION_CHUNK_RADIUS; dx <= BASTION_CHUNK_RADIUS; dx++) {
            for (int dz = -BASTION_CHUNK_RADIUS; dz <= BASTION_CHUNK_RADIUS; dz++) {
                if (mod.getChunkTracker().chunkHas(new ChunkPos(c.x() + dx, c.z() + dz), GetSmithingTemplateTask::isBastionBlock)) {
                    _inBastion.add(chest);
                    return true;
                }
            }
        }
        _notBastionUntil.put(chest, _ticks + NOT_BASTION_RECHECK_TICKS);
        return false;
    }

    private static boolean isBastionBlock(BlockState state) {
        for (Block b : BASTION) {
            if (state.is(b)) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected void onResourceStop(AltoClef mod, Task interruptTask) {
        mod.getBlockTracker().stopTracking(Blocks.CHEST);
    }

    @Override
    protected boolean isEqualResource(ResourceTask other) {
        return other instanceof GetSmithingTemplateTask;
    }

    @Override
    protected String toHudString() {
        return "Looking for a smithing template in a bastion";
    }

    @Override
    protected String toDebugStringName() {
        return "Collect " + _count + " smithing templates";
    }

    @Override
    protected boolean shouldAvoidPickingUp(AltoClef mod) {
        return false;
    }
}
