package adris.altoclef.tasks.squashed;

import adris.altoclef.AltoClef;
import adris.altoclef.AltoSettings;
import adris.altoclef.BotBehaviour;
import adris.altoclef.TaskCatalogue;
import adris.altoclef.tasks.resources.CollectIronIngotTask;
import adris.altoclef.tasksystem.Task;
import adris.altoclef.ui.HudText;
import adris.altoclef.util.ItemTarget;
import adris.altoclef.util.helpers.CatalogueBook;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.RecipeBill;
import adris.altoclef.util.helpers.StationHook;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.WalkCost;
import adris.altoclef.util.helpers.WorldHelper;
import adris.altoclef.util.time.TimerGame;
import baritone.api.utils.Dimension;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

// get things the way you'd do it by hand: work out the whole shopping list first (RecipeBill), grab the tools the job calls
// for, fetch every raw material in one go, then smelt and craft bottom up. the old way (CataloguedResourceTask straight
// away) had each recipe level replan from the bag by itself and kept going back out for the iron somebody forgot about.
// anything the bill can't plan (mob drops, nether stuff, custom tasks) or anything still short at the end is the old way
// anyway: the last stage is the plain catalogue task for the targets, so this is never worse than before
public class GatherBillTask extends Task {

    // "craft as many as you can" targets are 99999999. nobody is mining that, the plain task handles those
    private static final int BILL_CAP = 4096;
    // room left over for whatever the crafts spit out while the leaves are in the bag
    private static final int SLOTS_KEPT_FREE = 2;
    // a job whose finals fill the bag still gets a few slots of leaves a trip, or the up-front list would never fetch anything
    private static final int MIN_LEAF_SLOTS = 3;
    // a tool that can't be had (no trees, no stone) gets this many goes and then the job carries on without it
    private static final int TOOL_TRIES = 3;
    // one fetch per pickaxe tier plus a bag refill or two. a seatbelt, the passes stop by themselves when nothing's left
    private static final int MAX_LEAF_PASSES = 6;
    // after the crafting, back out for whatever's still short (the bag cap cut it, a craft ate something), at most this often
    private static final int MAX_LOOPS = 2;
    private static final Block[] STATIONS = {Blocks.CRAFTING_TABLE, Blocks.FURNACE, Blocks.BLAST_FURNACE};
    // how long the first bill waits for the tracker's first look at the stations. before it, "no table around" only means
    // "haven't looked", and that bill would send us off for a pickaxe and 8 cobble for a furnace that's standing right there
    private static final double FIRST_SCAN_SECONDS = 3;

    private enum Stage {LEAVES, LEVELS, FINAL}

    private final ItemTarget[] _targets;
    private final Map<String, Integer> _wanted = new LinkedHashMap<>();
    private final boolean _billable;
    private Stage _stage = Stage.LEAVES;
    private Task _current;
    private final Map<String, Integer> _toolTries = new HashMap<>();
    private final Set<String> _made = new HashSet<>();
    private final Map<Item, Integer> _reserved = new HashMap<>();
    private int _leafPasses;
    private int _loops;
    // the running fetch and what it was chopping/digging with a tool in the bag. the tool snapping halfway is a cheap bag
    // check, not a whole bill every tick
    private Task _leafTask;
    private final Map<String, Integer> _axeLeaves = new HashMap<>();
    private final Map<String, Integer> _shovelLeaves = new HashMap<>();
    // the tool being made right now and how many the bag had before, so one that worked gives its try back
    private String _makingTool;
    private int _toolsBefore;
    // the leaves we're out fetching right now, so progress() can see the bag fill up before anything is crafted
    private List<RecipeBill.Fetch> _fetching = List.of();
    // same for the tool or the level being made right now. 128 stone out of a furnace is a long time with no new stage
    private List<ItemTarget> _making = List.of();
    // one per task next() hands out. the stage and _made both go backwards on a loop-back now, this never does
    private int _steps;
    private boolean _finalCounted;
    private BotBehaviour.State _level;
    // the wood the first bill gave the sticks and the table, kept for every bill after (RecipeBill.of's latched says why)
    private String _mainWood;
    private final TimerGame _scanWait = new TimerGame(FIRST_SCAN_SECONDS);
    private boolean _looked;

    public GatherBillTask(ItemTarget... targets) {
        _targets = targets;
        boolean billable = targets.length > 0;
        for (ItemTarget target : targets) {
            String name = target == null ? null : CatalogueBook.canonical(target);
            if (name == null || target.getTargetCount() > BILL_CAP) {
                billable = false;
                break;
            }
            _wanted.merge(name, target.getTargetCount(), Integer::sum);
        }
        _billable = billable;
        if (!billable) {
            _stage = Stage.FINAL;
        }
    }

    @Override
    protected void onStart(AltoClef mod) {
        _level = mod.getBehaviour().push();
        // back from a fight or a meal: the stage picks up where it was, so does what it was keeping safe
        if (!_reserved.isEmpty()) {
            mod.getBehaviour().reserveProtectedItems(_level, _reserved);
        }
        mod.getBlockTracker().trackBlock(STATIONS);
        _scanWait.reset();
    }

    @Override
    protected Task onTick(AltoClef mod) {
        if (!_looked && _stage != Stage.FINAL) {
            if (!mod.getBlockTracker().hasBeenScanned(STATIONS) && !_scanWait.elapsed()) {
                setDebugState("Looking around for a table and a furnace");
                return null;
            }
            _looked = true;
        }
        if (_current != null && _current == _leafTask && toolBroke(mod)) {
            // the next() below makes a new one and picks the fetch back up
            _current = null;
        }
        // a few stages can be empty in a row (no tools needed, nothing to fetch), skip through them in the same tick
        for (int i = 0; i < 8; i++) {
            if (_current != null && !_current.isFinished(mod)) {
                return _current;
            }
            _current = next(mod);
            if (_current == null) {
                return null;
            }
        }
        return _current;
    }

    private Task next(AltoClef mod) {
        // the up-front list is overworld stuff: the leaves are overworld mines, and gold in the nether comes from
        // CollectGoldIngotTask's nugget route, not raw gold. anywhere else it's the plain task like before
        if (!_billable || WorldHelper.getCurrentDimension() != Dimension.OVERWORLD) {
            _stage = Stage.FINAL;
            return finalTask(null);
        }
        settleTool(mod);
        RecipeBill.ToolBag bag = toolBag(mod);
        RecipeBill.Bill bill = bill(mod, bag);
        // every stage starts with the tool check, a tool that broke or wore out since the last one gets made again here
        Task tool = nextTool(mod, bill, bag);
        if (tool != null) return tool;
        if (_stage == Stage.LEAVES) {
            String pick = RecipeBill.nextPick(bill.leaves, bag);
            boolean pickDue = pick != null && canTry(pick);
            Task leaves = _leafPasses < MAX_LEAF_PASSES ? leaves(mod, bill, bag, pickDue) : null;
            if (leaves != null) {
                _leafPasses++;
                return leaves;
            }
            // this tier's leaves are home, its cobble (or iron) makes the next pick, and the next pass gets the next tier
            if (pickDue) return makeTool(mod, pick);
            _stage = Stage.LEVELS;
        }
        if (_stage == Stage.LEVELS) {
            Task level = nextLevel(bill);
            if (level != null) return level;
            if (_loops < MAX_LOOPS && !bill.leaves.isEmpty()) {
                _loops++;
                _leafPasses = 0;
                // the things still to make get their go again with the next batch of leaves
                for (List<RecipeBill.Node> lvl : bill.levels) {
                    for (RecipeBill.Node node : lvl) _made.remove(node.name());
                }
                _stage = Stage.LEAVES;
                return next(mod);
            }
            _stage = Stage.FINAL;
        }
        return finalTask(bill);
    }

    // the bill is worked out fresh at the start of every stage, from whatever the bag holds by then. a death or a fight
    // in between just means a different bill. the picks we'll make later from the fetched stuff go on it too
    private RecipeBill.Bill bill(AltoClef mod, RecipeBill.ToolBag bag) {
        CatalogueBook book = new CatalogueBook(items -> mod.getItemStorage().getItemCount(items));
        RecipeBill.World world = new RecipeBill.World(stationNear(mod, Blocks.CRAFTING_TABLE), stationNear(mod, Blocks.FURNACE),
                otherFuelSmelts(mod), n -> CollectIronIngotTask.wouldBuildBlast(n, mod));
        RecipeBill.Bill bill = latch(RecipeBill.of(_wanted, book, world, _mainWood));
        List<String> later = RecipeBill.laterTools(bill.leaves, bag);
        if (later.isEmpty()) {
            return bill;
        }
        Map<String, Integer> withTools = new LinkedHashMap<>(_wanted);
        for (String tool : later) {
            withTools.merge(tool, book.held(tool) + 1, Math::max);
        }
        return latch(RecipeBill.of(withTools, book, world, _mainWood));
    }

    // the bill a GatherBillTask for `wanted` would work out right now, the picks it would make later included, with no wood
    // latched. for the take-along (TakeAlongChain), so what it takes along is what this task would otherwise walk for
    public static RecipeBill.Bill billFor(AltoClef mod, Map<String, Integer> wanted) {
        CatalogueBook book = new CatalogueBook(items -> mod.getItemStorage().getItemCount(items));
        RecipeBill.World world = new RecipeBill.World(stationNear(mod, Blocks.CRAFTING_TABLE), stationNear(mod, Blocks.FURNACE),
                otherFuelSmelts(mod), n -> CollectIronIngotTask.wouldBuildBlast(n, mod));
        RecipeBill.Bill bill = RecipeBill.of(wanted, book, world);
        List<String> later = RecipeBill.laterTools(bill.leaves, toolBag(mod));
        if (later.isEmpty()) {
            return bill;
        }
        Map<String, Integer> withTools = new LinkedHashMap<>(wanted);
        for (String tool : later) {
            withTools.merge(tool, book.held(tool) + 1, Math::max);
        }
        return RecipeBill.of(withTools, book, world);
    }

    // a bill with no generic planks left says null, that's not a reason to forget the wood. a broken axe brings it back
    private RecipeBill.Bill latch(RecipeBill.Bill bill) {
        if (bill.mainWood() != null) _mainWood = bill.mainWood();
        return bill;
    }

    // the wooden pick, the axe and the shovel. a stone pick and up waits in the LEAVES stage until this tier's leaves are
    // home, it's made out of them
    private Task nextTool(AltoClef mod, RecipeBill.Bill bill, RecipeBill.ToolBag bag) {
        for (String tool : RecipeBill.tools(bill.leaves, bill.deferred, bag)) {
            if (Arrays.asList(RecipeBill.PICKAXES).indexOf(tool) >= 2 || !canTry(tool)) continue;
            return makeTool(mod, tool);
        }
        return null;
    }

    private boolean canTry(String tool) {
        return TaskCatalogue.taskExists(tool) && _toolTries.getOrDefault(tool, 0) < TOOL_TRIES;
    }

    private Task makeTool(AltoClef mod, String tool) {
        _toolTries.merge(tool, 1, Integer::sum);
        // one more than the bag has: a worn one still counts for the item task, and "have 1" would be done already.
        // if the worn one snaps while this runs we end up with a spare. a spare axe, oh no
        int have = toolCount(mod, tool);
        int want = have + 1;
        _makingTool = tool;
        _toolsBefore = have;
        reserve(mod, tool, want);
        _making = List.of(new ItemTarget(tool, want));
        _steps++;
        setDebugState("Getting a " + tool + " for the job");
        return TaskCatalogue.getItemTask(tool, want);
    }

    // the cap is for tools that can't be had, not for a big job that wears out five axes. one that got made gives its try back
    private void settleTool(AltoClef mod) {
        if (_makingTool != null && toolCount(mod, _makingTool) > _toolsBefore) {
            _toolTries.merge(_makingTool, -1, Integer::sum);
        }
        _makingTool = null;
    }

    private static int toolCount(AltoClef mod, String tool) {
        return mod.getItemStorage().getItemCount(TaskCatalogue.getItemMatches(tool));
    }

    // the leaves the bag's pickaxe can get right now, lowest pickaxe first. the next tier's stuff waits for the next pass,
    // after its pick got made out of this pass's cobble. a pick we gave up on doesn't hold the rest back, MineAndCollectTask
    // sorts its own pick out like it always did
    private Task leaves(AltoClef mod, RecipeBill.Bill bill, RecipeBill.ToolBag bag, boolean pickDue) {
        int window = pickDue ? Math.max(0, bag.pickTier()) : Integer.MAX_VALUE;
        List<RecipeBill.Node> now = new ArrayList<>();
        for (RecipeBill.Node leaf : bill.leaves) {
            if (leaf.tier() <= window) now.add(leaf);
        }
        int free = freeSlots(mod) - SLOTS_KEPT_FREE;
        int budget = Math.max(Math.min(MIN_LEAF_SLOTS, free), free - RecipeBill.reservedSlots(bill, _wanted, GatherBillTask::stackSize));
        List<RecipeBill.Fetch> fetch = RecipeBill.fitBag(now, budget, GatherBillTask::stackSize);
        if (fetch.isEmpty()) {
            return null;
        }
        _fetching = fetch;
        _steps++;
        _axeLeaves.clear();
        _shovelLeaves.clear();
        List<ItemTarget> targets = new ArrayList<>();
        for (RecipeBill.Fetch f : fetch) {
            targets.add(new ItemTarget(f.name(), f.target()));
            reserve(mod, f.name(), f.target());
            if (bag.axeTier() >= 1 && RecipeBill.isLog(f.name())) _axeLeaves.put(f.name(), f.target());
            if (bag.shovelTier() >= 1 && RecipeBill.isShovelStuff(f.name())) _shovelLeaves.put(f.name(), f.target());
        }
        setDebugState("Fetching the raw materials");
        // not squashed: the order is the bill's, lowest pickaxe first. that only holds between the non-ore leaves, the ores
        // in one pass get mined together, nearest short one first (CataloguedResourceTask.oresTogether)
        _leafTask = new CataloguedResourceTask(false, targets.toArray(ItemTarget[]::new));
        return _leafTask;
    }

    // the fetch started with an axe (or shovel) in the bag, that one is gone or worn now, and there's still chopping to do
    private boolean toolBroke(AltoClef mod) {
        if (_axeLeaves.isEmpty() && _shovelLeaves.isEmpty()) {
            return false;
        }
        RecipeBill.ToolBag bag = toolBag(mod);
        String material = bag.pickTier() >= 2 ? "stone_" : "wooden_";
        boolean broke = broke(mod, material + "axe", bag.axeTier(), _axeLeaves) || broke(mod, material + "shovel", bag.shovelTier(), _shovelLeaves);
        // the cut-short fetch doesn't count as one of the passes, it picks back up right after the new tool
        if (broke && _leafPasses > 0) _leafPasses--;
        return broke;
    }

    // the leaves are cleared either way, so a tool the bill doesn't ask for any more (or can't get) never fires twice
    private boolean broke(AltoClef mod, String tool, int tier, Map<String, Integer> leaves) {
        if (tier >= 1 || leaves.isEmpty()) {
            return false;
        }
        boolean short_ = false;
        for (Map.Entry<String, Integer> leaf : leaves.entrySet()) {
            short_ |= mod.getItemStorage().getItemCount(TaskCatalogue.getItemMatches(leaf.getKey())) < leaf.getValue();
        }
        leaves.clear();
        return short_ && canTry(tool);
    }

    // the next bunch RecipeBill.nextBatch says is up, made up to the whole bill's count: the planks get crafted for the
    // targets and for the stairs that eat them in one go, so nothing downstream comes up short
    private Task nextLevel(RecipeBill.Bill bill) {
        List<String> todo = RecipeBill.nextBatch(bill, _made);
        if (todo.isEmpty()) {
            return null;
        }
        _made.addAll(todo);
        List<ItemTarget> targets = new ArrayList<>();
        for (String name : RecipeBill.consumersFirst(bill, todo)) {
            targets.add(new ItemTarget(name, bill.need(name)));
        }
        _making = targets;
        _steps++;
        setDebugState("Making the in-betweens");
        return TaskCatalogue.getSquashedItemTask(targets.toArray(ItemTarget[]::new));
    }

    // the plain catalogue task, consumers first (RecipeBill.consumersFirst says why). the squasher still pulls the table
    // crafts up front, but those are consumers anyway, and stairs-before-planks survives since both are Collect* tasks
    private Task finalTask(RecipeBill.Bill bill) {
        setDebugState("Finishing up the old way");
        // once, a final task that keeps finishing short gets handed out every tick and that's not getting anywhere
        if (!_finalCounted) {
            _finalCounted = true;
            _steps++;
        }
        if (_targets.length == 1) {
            return TaskCatalogue.getItemTask(_targets[0]);
        }
        if (bill == null) {
            return TaskCatalogue.getSquashedItemTask(_targets);
        }
        Map<String, List<ItemTarget>> byName = new LinkedHashMap<>();
        for (ItemTarget target : _targets) {
            byName.computeIfAbsent(CatalogueBook.canonical(target), k -> new ArrayList<>()).add(target);
        }
        List<ItemTarget> sorted = new ArrayList<>();
        for (String name : RecipeBill.consumersFirst(bill, new ArrayList<>(byName.keySet()))) {
            sorted.addAll(byName.get(name));
        }
        return TaskCatalogue.getSquashedItemTask(sorted.toArray(ItemTarget[]::new));
    }

    // a table or furnace we'd use instead of making one: standing within STATION_NEAR, reachable, not coming down.
    // same line DoStuffInContainerTask goes by for a world one, close enough for a shopping list
    private static boolean stationNear(AltoClef mod, Block block) {
        Vec3 me = mod.getPlayer().position();
        return mod.getBlockTracker().getNearestTracking(me,
                p -> WorldHelper.canReach(mod, p) && !StationHook.pickingUp(p)
                        && WalkCost.stationDistance(p.getX(), p.getY(), p.getZ(), me.x, me.y, me.z) <= WalkCost.STATION_NEAR,
                block).isPresent();
    }

    // fuel in the bag that isn't coal, in smelts. the coal is a node of the bill and gets netted there
    private static int otherFuelSmelts(AltoClef mod) {
        int all = (int) Math.floor(StorageHelper.calculateInventoryFuelCount(mod));
        int coal = AltoSettings.isSupportedFuel(Items.COAL) ? 8 * mod.getItemStorage().getItemCount(Items.COAL) : 0;
        return Math.max(0, all - coal);
    }

    // the best pick, axe and shovel in the bag that isn't worn past ToolWear's line. a worn one is as good as none, the
    // bill makes the next one while we're still near a table instead of punching the rest of the tree
    private static RecipeBill.ToolBag toolBag(AltoClef mod) {
        int pick = 0;
        int axe = -1;
        int shovel = -1;
        for (ItemStack stack : mod.getItemStorage().getItemStacksPlayerInventory(false)) {
            if (stack.isEmpty()) continue;
            int full = tierOf(stack.getItem());
            int tier = RecipeBill.usableTier(full, stack.getDamageValue(), stack.getMaxDamage());
            String kind = ItemHelper.toolKind(stack.getItem());
            if (full < 0 || kind == null) continue;
            switch (kind) {
                // a worn stone pick still digs cobble, call it one down so the next one is stone again and not wooden first
                case "pickaxe" -> pick = Math.max(pick, tier < 0 ? full - 1 : tier);
                case "axe" -> axe = Math.max(axe, tier);
                case "shovel" -> shovel = Math.max(shovel, tier);
                default -> {
                }
            }
        }
        int p = pick;
        int a = axe;
        int s = shovel;
        return new RecipeBill.ToolBag() {
            @Override
            public int pickTier() {
                return p;
            }

            @Override
            public int axeTier() {
                return a;
            }

            @Override
            public int shovelTier() {
                return s;
            }
        };
    }

    // wood, gold, stone, iron, diamond, netherite -> 1, 1, 2, 3, 4, 5. -1 for anything that isn't one of those
    private static int tierOf(Item item) {
        Item[][] byTier = {
                {Items.WOODEN_PICKAXE, Items.WOODEN_AXE, Items.WOODEN_SHOVEL, Items.GOLDEN_PICKAXE, Items.GOLDEN_AXE, Items.GOLDEN_SHOVEL},
                {Items.STONE_PICKAXE, Items.STONE_AXE, Items.STONE_SHOVEL},
                {Items.IRON_PICKAXE, Items.IRON_AXE, Items.IRON_SHOVEL},
                {Items.DIAMOND_PICKAXE, Items.DIAMOND_AXE, Items.DIAMOND_SHOVEL},
                {Items.NETHERITE_PICKAXE, Items.NETHERITE_AXE, Items.NETHERITE_SHOVEL},
        };
        for (int t = 0; t < byTier.length; t++) {
            for (Item i : byTier[t]) {
                if (i == item) return t + 1;
            }
        }
        return -1;
    }

    private static int freeSlots(AltoClef mod) {
        int free = 0;
        for (ItemStack stack : mod.getPlayer().getInventory().getNonEquipmentItems()) {
            if (stack.isEmpty()) free++;
        }
        return free;
    }

    private static int stackSize(String name) {
        Item[] matches = TaskCatalogue.getItemMatches(name);
        return matches.length == 0 ? 64 : new ItemStack(matches[0]).getMaxStackSize();
    }

    // the junk sweep leaves the fetched stuff and the tools alone till we're done. a count and not the whole item, so the
    // pather can still pillar with cobble past what the bill wants
    private void reserve(AltoClef mod, String name, int count) {
        for (Item item : TaskCatalogue.getItemMatches(name)) {
            _reserved.merge(item, count, Math::max);
        }
        mod.getBehaviour().reserveProtectedItems(_level, _reserved);
    }

    // a number that goes up while the bill gets somewhere, for a caller with a "stuck" clock (SchematicBuildTask's
    // GatherGuard). the targets only show up at the very end now, a whole batch's worth of ore and cobble comes first,
    // and that trip is not being stuck. _steps only ever grows, so does this, minus crafting eating leaves
    public long progress(AltoClef mod) {
        long held = 0;
        for (RecipeBill.Fetch f : _fetching) {
            held += Math.min(mod.getItemStorage().getItemCount(TaskCatalogue.getItemMatches(f.name())), f.target());
        }
        for (ItemTarget t : _making) {
            held += Math.min(mod.getItemStorage().getItemCount(t.getMatches()), t.getTargetCount());
        }
        return _steps * 1_000_000L + held;
    }

    @Override
    public boolean isFinished(AltoClef mod) {
        // not counting the cursor, the same as the plain ResourceTask: done means put away
        return StorageHelper.itemTargetsMetInventoryNoCursor(mod, _targets);
    }

    @Override
    protected void onStop(AltoClef mod, Task interruptTask) {
        mod.getBlockTracker().stopTracking(STATIONS);
        mod.getBehaviour().pop();
        _level = null;
    }

    @Override
    protected boolean isEqual(Task other) {
        return other instanceof GatherBillTask task && Arrays.equals(task._targets, _targets);
    }

    @Override
    protected String toDebugString() {
        return "Gather bill (" + _stage + "): " + Arrays.toString(_targets);
    }

    @Override
    protected String toHudString() {
        return "Getting " + HudText.items(_targets);
    }
}
