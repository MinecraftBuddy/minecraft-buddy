package adris.altoclef.tasks.speedrun.gamer;

import adris.altoclef.AltoClef;
import adris.altoclef.AltoSettings;
import adris.altoclef.Debug;
import adris.altoclef.tasks.container.DoStuffInContainerTask;
import adris.altoclef.tasks.container.SmeltSplit;
import adris.altoclef.tasks.resources.CollectFoodTask;
import adris.altoclef.tasks.resources.PreyScan;
import adris.altoclef.trackers.SimpleChunkTracker;
import adris.altoclef.util.helpers.FoodHelper;
import adris.altoclef.util.helpers.ItemHelper;
import adris.altoclef.util.helpers.StationHook;
import adris.altoclef.util.helpers.StorageHelper;
import adris.altoclef.util.helpers.WorldHelper;
import baritone.api.utils.Dimension;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.WinScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.Slot;
import adris.altoclef.util.helpers.ToolWear;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.chunk.EmptyLevelChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

// GamerFacts for the real game. refresh() is called once per tick by the engine and walks the inventory once, everything
// else is a field read, so the handlers' isDone checks stay cheap no matter how many times they are asked
public final class MinecraftFacts implements GamerFacts {
    private final AltoClef mod;
    // fastutil so a tick of counting does not box a pile of integers
    private final Object2IntOpenHashMap<Item> counts = new Object2IntOpenHashMap<>(64);
    private final Object2IntOpenHashMap<Item> spent = new Object2IntOpenHashMap<>(4);
    private int bagBeds;
    private int wornSwords;
    private final ItemStack[] worn = new ItemStack[5];
    // diagnostics only (PickDiag): every pickaxe the last scan saw, in scan order. nobody carries more than eight on purpose
    private final PickSeen[] picks = new PickSeen[8];
    private int picksSeen;
    // an iron pick was worn out in the last scan, so the log line goes out once per wear out and not once per tick
    private boolean ironWornNow;
    private boolean ironWornLogged;

    private RunState state;
    private Dimension dimension = Dimension.OVERWORLD;
    private int armorPoints;
    private int foodUnits;
    private int junkFoodUnits;
    // food in the input and output slots of the furnace-like screen that is open right now, at planned value
    private int stationFood;
    // how much of it foodUnits counted, and how much was already there when the food task opened the screen and so stays out
    // (FoodPlan.leftover, -1 = nothing to leave out), for the log line
    private int stationCounted;
    private int stationSkipped;
    private int stationLeftover = -1;
    // the open furnace screen has had its slots sent (the first ContainerSetContent bumps the state id off 0)
    private boolean stationSynced;
    // which screen that is and which one the leftover was taken from, so smoker B never inherits smoker A's. and the food task's
    // tick count as of the last look: it moved since, so it ran
    private int stationMenuId = -1;
    private int leftoverMenuId = -1;
    private long foodTaskSeen;
    // the block that screen is, null when nothing says
    private RunState.Pos stationAt;
    private int buildBlocks;
    private boolean nearSurface;
    // a gold ore in the loaded chunks near us, looked at every few seconds and only while the plan could use it (GoldHunt)
    private boolean goldOreKnown;
    private long goldLookAt = Long.MIN_VALUE;
    private static final int GOLD_LOOK_TICKS = 40;
    private boolean bagFull;
    // an edible animal is loaded close by (PreyScan), looked at every few ticks and with a bigger line to leave than to arrive
    private boolean preyNear;
    private long preyLookAt = Long.MIN_VALUE;
    // last look that really saw one, so a kill's drops are not abandoned the tick the animal is gone (FoodDry.preyHeld)
    private long preySeenAt = Long.MIN_VALUE;
    private static final int PREY_LOOK_TICKS = 10;
    private int fingerprint;
    private int x;
    private int y;
    private int z;
    private long gameTime;
    // sticky: once the credits showed up the run is over, the screen going away again must not undo that
    private boolean credits;
    // every station answer of this tick, see StationVerdict
    private StationVerdict stations = StationVerdict.NONE;
    // what the verdict remembers from the last look (the table: out of NEAR, held by the walk back; the furnace's and smoker's band)
    private final StationLatches latches = new StationLatches();

    public MinecraftFacts(AltoClef mod) {
        this.mod = mod;
        java.util.Arrays.fill(worn, ItemStack.EMPTY);
        for (int i = 0; i < picks.length; i++) {
            picks[i] = new PickSeen();
        }
    }

    // the section palettes say "no gold here" for nearly every section, so this is a handful of cheap checks per look
    private void lookForGold(Player player) {
        if (dimension != Dimension.OVERWORLD || !GoldHunt.owed(this)) {
            goldOreKnown = false;
            goldLookAt = Long.MIN_VALUE;
            return;
        }
        if (gameTime < goldLookAt + GOLD_LOOK_TICKS && gameTime >= goldLookAt) {
            return;
        }
        goldLookAt = gameTime;
        goldOreKnown = false;
        int r = GoldHunt.RADIUS;
        int cx0 = (x - r) >> 4;
        int cx1 = (x + r) >> 4;
        int cz0 = (z - r) >> 4;
        int cz1 = (z + r) >> 4;
        for (int cx = cx0; cx <= cx1 && !goldOreKnown; cx++) {
            for (int cz = cz0; cz <= cz1 && !goldOreKnown; cz++) {
                LevelChunk chunk = mod.getWorld().getChunk(cx, cz);
                if (chunk instanceof EmptyLevelChunk) {
                    continue;
                }
                // only the sections inside the height box: the finder goes bottom up, so a chunk with deepslate gold at the
                // bottom would hide the vein at our own height. sections[i] covers world y minY + i * 16
                LevelChunkSection[] sections = chunk.getSections();
                int minY = chunk.getMinY();
                int lo = Math.max(0, Math.floorDiv(y - r - minY, 16));
                int hi = Math.min(sections.length - 1, Math.floorDiv(y + r - minY, 16));
                if (lo > hi) {
                    continue;
                }
                goldOreKnown = SimpleChunkTracker.findInSections(Arrays.copyOfRange(sections, lo, hi + 1), minY + lo * 16,
                        cx << 4, cz << 4, s -> s.is(Blocks.GOLD_ORE) || s.is(Blocks.DEEPSLATE_GOLD_ORE)) != null;
            }
        }
    }

    private void lookForPrey() {
        if (dimension != Dimension.OVERWORLD) {
            preyNear = false;
            preyLookAt = Long.MIN_VALUE;
            preySeenAt = Long.MIN_VALUE;
            if (state != null) {
                state.foodSearchSince = -1;
            }
            return;
        }
        if (gameTime < preyLookAt + PREY_LOOK_TICKS && gameTime >= preyLookAt) {
            return;
        }
        preyLookAt = gameTime;
        boolean seen = PreyScan.near(mod, preyNear ? FoodDry.LEAVE : FoodDry.NEAR);
        if (seen) {
            preySeenAt = gameTime;
        }
        preyNear = FoodDry.preyKept(seen, preySeenAt, gameTime, CollectFoodTask.lootPending());
        if (state != null) {
            String line = FoodDry.tick(state, CollectFoodTask.searching(), preyNear, gameTime);
            if (line != null) {
                Debug.logMessage(line);
            }
        }
    }

    @Override
    public boolean preyNear() {
        return preyNear;
    }

    @Override
    public boolean foodDryLatched() {
        return state != null && state.foodDryOn;
    }

    // false when there is no player to read (loading screen), the old numbers stay
    public boolean refresh() {
        Player player = mod.getPlayer();
        if (player == null || mod.getWorld() == null) {
            return false;
        }
        dimension = WorldHelper.getCurrentDimension();
        x = player.getBlockX();
        y = player.getBlockY();
        z = player.getBlockZ();
        gameTime = mod.getWorld().getGameTime();
        // the same test IronPhase's food gate and the smelt site use, the nether's roof is not a sky
        nearSurface = dimension == Dimension.OVERWORLD && SmeltSurface.shallow(SmeltSurface.depthBelowSky(mod));
        armorPoints = player.getArmorValue();
        if (Minecraft.getInstance().gui.screen() instanceof WinScreen) {
            credits = true;
        }
        countItems(player);
        lookForGold(player);
        lookForPrey();
        // the junk scan only runs with every slot taken, which is rare
        bagFull = !mod.getItemStorage().hasEmptyInventorySlot() && StorageHelper.getJunkSlot(mod).isEmpty();
        if (state != null) {
            Workbenches.sync(state, gameTime);
            stations = StationVerdict.of(state, dimension.name(), player.getX(), player.getY(), player.getZ(), new StationVerdict.World() {
                @Override
                public boolean stands(StationHook.Kind kind, RunState.Pos pos) {
                    BlockPos at = new BlockPos(pos.x, pos.y, pos.z);
                    return !mod.getChunkTracker().isChunkLoaded(at) || mod.getWorld().getBlockState(at).is(Workbenches.blockOf(kind));
                }

                @Override
                public boolean inBag(StationHook.Kind kind) {
                    return mod.getItemStorage().hasItem(Workbenches.itemOf(kind));
                }

                @Override
                public boolean canMake(StationHook.Kind kind) {
                    return DoStuffInContainerTask.bagCanMake(mod, kind);
                }

                @Override
                public boolean walkUsable(StationHook.Kind kind, Bench b) {
                    return DoStuffInContainerTask.walkBackUsable(mod, new BlockPos(b.pos.x, b.pos.y, b.pos.z), Workbenches.blockOf(kind));
                }
            }, latches);
        } else {
            stations = StationVerdict.NONE;
            latches.clear();
        }
        return true;
    }

    // the tick's station verdict, built by refresh() (the latches move there, every tick, whoever asks)
    @Override
    public StationVerdict stations() {
        return stations;
    }

    @Override
    public boolean tablePlacedNearby() {
        return stations.table().heldForPlanner();
    }

    @Override
    public boolean furnacePlacedNearby() {
        return stations.furnace().heldForPlanner();
    }

    @Override
    public boolean smokerPlacedNearby() {
        return stations.smoker().heldForPlanner();
    }

    @Override
    public SmeltSplit.Batch smeltBatch() {
        return SmeltSplit.active();
    }

    @Override
    public boolean cookSuspended() {
        return state != null && FurnacePlan.cookSuspended(state.cook, gameTime);
    }

    @Override
    public String cookStation() {
        return state == null ? null : FurnacePlan.cookStation(state.cook, gameTime);
    }

    @Override
    public boolean burnable(Item item) {
        // a crimson stem is in ItemHelper.LOG and burns nothing, FuelPolicy counts it as 0 and so must we
        return AltoSettings.isSupportedFuel(item) && ItemHelper.isFuel(item);
    }

    private void countItems(Player player) {
        counts.clear();
        spent.clear();
        wornSwords = 0;
        picksSeen = 0;
        ironWornNow = false;
        Inventory inv = player.getInventory();
        // main + hotbar (0..35), armor (36..39) and the offhand (40) are all in the container
        bagBeds = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            add(inv.getItem(i), areaOf(i), i);
            if (java.util.Arrays.asList(adris.altoclef.util.helpers.ItemHelper.BED).contains(inv.getItem(i).getItem())) {
                bagBeds += inv.getItem(i).getCount();
            }
        }
        // the cursor and the 2x2 grid are "ours" for a moment while we craft, not gone
        add(player.containerMenu.getCarried(), "cursor", -1);
        for (int slot = 1; slot <= 4 && slot < player.inventoryMenu.slots.size(); slot++) {
            add(player.inventoryMenu.getSlot(slot).getItem(), "grid", slot);
        }
        // same for the 3x3 of an open crafting table. this was missing and it made a smoker craft thrash: the furnace is
        // a single item, so the moment it moved into the grid we owned 0 furnaces, the kit said "make a furnace" and
        // swept the grid, and the food task put it right back. ~55 times. stacked ingredients never hit it
        if (player.containerMenu instanceof CraftingMenu table) {
            // slot 0 is the output, which we do NOT own until it is taken (see InventorySubTracker)
            for (int slot = 1; slot <= 9 && slot < table.slots.size(); slot++) {
                add(table.getSlot(slot).getItem(), "table", slot);
            }
        }
        // meat on its way into a smoker (or cooked on its way out) is not in the bag for a few ticks and not a job yet. the
        // slots are not added to counts: ore in a furnace is the early load's business (earlyLoadInFlight), and a raw iron that
        // is not "in the bag" is how that latch works. only the food sum reads them
        stationFood = 0;
        stationAt = null;
        stationSynced = false;
        stationMenuId = -1;
        if (player.containerMenu instanceof AbstractFurnaceMenu furnace) {
            boolean lit = StorageHelper.getFurnaceFuel(furnace) > 0;
            stationFood = FoodPlan.stationUnits(foodIn(furnace.getSlot(0).getItem()), foodIn(furnace.getSlot(2).getItem()), lit,
                    !furnace.getSlot(1).getItem().isEmpty());
            stationSynced = furnace.getStateId() > 0;
            stationMenuId = furnace.containerId;
            // which block the screen is, so only that station's own job hides its food (FoodPlan.inStation)
            stationAt = mod.getItemStorage().getLastBlockPosInteraction().map(p -> new RunState.Pos(p.getX(), p.getY(), p.getZ())).orElse(null);
        }
        // feet, legs, chest, head: the order the armor list had
        worn[0] = player.getItemBySlot(EquipmentSlot.FEET);
        worn[1] = player.getItemBySlot(EquipmentSlot.LEGS);
        worn[2] = player.getItemBySlot(EquipmentSlot.CHEST);
        worn[3] = player.getItemBySlot(EquipmentSlot.HEAD);
        worn[4] = inv.getItem(Inventory.SLOT_OFFHAND);
        ironWornLogged = ironWornNow;
        summarize();
    }

    private static String areaOf(int invSlot) {
        if (invSlot < 9) {
            return "hotbar";
        }
        if (invSlot < 36) {
            return "bag";
        }
        return invSlot < 40 ? "armor" : "offhand";
    }

    private static int foodIn(ItemStack stack) {
        if (stack.isEmpty() || FoodHelper.kindOf(stack.getItem()) != FoodHelper.Kind.NORMAL) {
            return 0;
        }
        return FoodHelper.plannedNutrition(stack.getItem()) * stack.getCount();
    }

    private void add(ItemStack stack, String where, int slot) {
        if (stack.isEmpty()) {
            return;
        }
        Item item = stack.getItem();
        boolean wornOut = KitPlanner.wornOut(item, stack.getDamageValue(), stack.getMaxDamage());
        if (isPick(item)) {
            notePick(stack, where, slot, wornOut);
        }
        // a pick on its last legs is not a pick we own, the planner makes the next one while there is still a table (the
        // catalogue still sees it in the bag, hence the separate count)
        if (wornOut) {
            spent.addTo(item, stack.getCount());
            return;
        }
        if (NetherPick.isSword(item) && ToolWear.wornOut(stack.getDamageValue(), stack.getMaxDamage())) {
            wornSwords += stack.getCount();
        }
        counts.addTo(item, stack.getCount());
    }

    private static boolean isPick(Item item) {
        return item == Items.IRON_PICKAXE || item == Items.STONE_PICKAXE || item == Items.WOODEN_PICKAXE
                || item == Items.GOLDEN_PICKAXE || item == Items.DIAMOND_PICKAXE || item == Items.NETHERITE_PICKAXE;
    }

    // the scan's own record of the picks it saw, plus the one line when the iron one crosses the worn out line
    private void notePick(ItemStack stack, String where, int slot, boolean wornOut) {
        if (picksSeen < picks.length) {
            picks[picksSeen++].set(stack, where, slot, wornOut);
        }
        if (wornOut && stack.getItem() == Items.IRON_PICKAXE) {
            ironWornNow = true;
            if (!ironWornLogged) {
                // also keeps a second worn one in the same scan quiet. countItems re-arms this when none is worn any more
                ironWornLogged = true;
                Debug.logInternal(PickDiag.wornOut(stack.getDamageValue(), stack.getMaxDamage(), where, slot));
            }
        }
    }

    private static final class PickSeen {
        private String item;
        private int damage;
        private int max;
        private String where;
        private int slot;
        private boolean worn;

        void set(ItemStack stack, String where, int slot, boolean worn) {
            this.item = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
            this.damage = stack.getDamageValue();
            this.max = stack.getMaxDamage();
            this.where = where;
            this.slot = slot;
            this.worn = worn;
        }

        String text() {
            return PickDiag.stack(item, damage, max, where, slot, worn);
        }
    }

    // diagnostics: the report IronPhase logs when the plan loses its iron pickaxe, built from the last refresh() plus what the
    // open screen holds right now
    @Override
    public String pickScan() {
        List<String> seen = new ArrayList<>();
        for (int i = 0; i < picksSeen; i++) {
            seen.add(picks[i].text());
        }
        List<String> open = new ArrayList<>();
        Minecraft mc = Minecraft.getInstance();
        Player player = mod.getPlayer();
        String menuName = "none";
        if (player != null && player.containerMenu != player.inventoryMenu) {
            AbstractContainerMenu menu = player.containerMenu;
            menuName = menu.getClass().getSimpleName();
            // the slots that are not the player's own: a chest, a furnace, the table's output. the table's 3x3 is in the
            // scan above already
            boolean table = menu instanceof CraftingMenu;
            for (Slot slot : menu.slots) {
                boolean scanned = slot.container == player.getInventory() || (table && slot.index >= 1 && slot.index <= 9);
                if (!scanned && isPick(slot.getItem().getItem())) {
                    ItemStack s = slot.getItem();
                    open.add(PickDiag.stack(BuiltInRegistries.ITEM.getKey(s.getItem()).getPath(), s.getDamageValue(), s.getMaxDamage(),
                            "menu", slot.index, false));
                }
            }
        }
        String screen = mc.gui.screen() == null ? "none" : mc.gui.screen().getClass().getSimpleName();
        return PickDiag.scan(seen, open, screen, menuName);
    }

    // one pass over the distinct item types: food, building blocks and the "did anything change" number together
    private void summarize() {
        int food = 0;
        int junk = 0;
        int build = 0;
        int fp = 0;
        for (Object2IntMap.Entry<Item> e : counts.object2IntEntrySet()) {
            Item item = e.getKey();
            int n = e.getIntValue();
            fp += (BuiltInRegistries.ITEM.getId(item) + 1) * 7919 + n * 31 * (BuiltInRegistries.ITEM.getId(item) + 17);
            FoodHelper.Kind kind = FoodHelper.kindOf(item);
            if (kind == FoodHelper.Kind.NORMAL) {
                // raw meat at its cooked value (FoodHelper.plannedNutrition), inventory only: food in a furnace is not here
                food += FoodHelper.plannedNutrition(item) * n;
            } else if (kind != FoodHelper.Kind.NOT_FOOD) {
                junk += item.components().get(DataComponents.FOOD).nutrition() * n;
            }
            // same rule as StorageHelper.getBuildingMaterialCount and the nether restock: AltoSettings.isBuildBlock
            if (AltoSettings.isBuildBlock(item) && AltoSettings.isThrowaway(item)) {
                build += n;
            }
        }
        // what sat in a screen the food task opened is why it walked there, not food we hold (FoodPlan.leftover)
        long foodTicks = CollectFoodTask.ticks();
        boolean foodTask = foodTicks != foodTaskSeen;
        foodTaskSeen = foodTicks;
        if (stationMenuId != leftoverMenuId) {
            stationLeftover = -1;
            leftoverMenuId = stationMenuId;
        }
        stationLeftover = FoodPlan.leftover(stationLeftover, stationFood, stationSynced, foodTask);
        stationCounted = FoodPlan.inStation(stationFood, furnaceJobs(), stationAt, dimension.name(), stationLeftover);
        stationSkipped = FoodPlan.inStation(stationFood, furnaceJobs(), stationAt, dimension.name()) - stationCounted;
        foodUnits = food + stationCounted;
        junkFoodUnits = junk;
        buildBlocks = build;
        fingerprint = fp;
    }

    // the run state the jobs live in, set once it is loaded (the facts exist before the state does)
    public void useState(RunState state) {
        this.state = state;
    }

    // RunState keeps every dimension's jobs, the planner only cares about the ones we can walk to
    @Override
    public List<RunState.FurnaceJob> furnaceJobs() {
        if (state == null || state.furnaceJobs.isEmpty()) {
            return List.of();
        }
        List<RunState.FurnaceJob> here = new ArrayList<>();
        for (RunState.FurnaceJob job : state.furnaceJobs) {
            if (job.dimension.equals(dimension.name())) {
                here.add(job);
            }
        }
        return here;
    }

    @Override
    public Dimension dimension() {
        return dimension;
    }

    @Override
    public int count(Item item) {
        return counts.getInt(item);
    }

    @Override
    public int wornSwords() {
        return wornSwords;
    }

    @Override
    public int spent(Item item) {
        return spent.getInt(item);
    }

    @Override
    public boolean armorEquipped(Item item) {
        for (ItemStack stack : worn) {
            if (stack.getItem() == item) {
                return true;
            }
        }
        return false;
    }

    @Override
    public int armorPoints() {
        return armorPoints;
    }

    @Override
    public int foodUnits() {
        return foodUnits;
    }

    @Override
    public int junkFoodUnits() {
        return junkFoodUnits;
    }

    @Override
    public int stationFoodUnits() {
        return stationCounted;
    }

    @Override
    public int stationFoodSkipped() {
        return stationSkipped;
    }

    @Override
    public boolean nearSurface() {
        return nearSurface;
    }

    @Override
    public boolean bagFull() {
        return bagFull;
    }

    @Override
    public int buildBlocks() {
        return buildBlocks;
    }

    @Override
    public int x() {
        return x;
    }

    @Override
    public int y() {
        return y;
    }

    @Override
    public int z() {
        return z;
    }

    @Override
    public long gameTime() {
        return gameTime;
    }

    @Override
    public boolean earlyIronPick() {
        return baritone.Baritone.settings().altoEarlyIronPick.value;
    }

    @Override
    public boolean goldHuntGaveUp() {
        return state != null && state.goldHuntGaveUp;
    }

    @Override
    public boolean woolHuntGaveUp() {
        return state != null && state.woolHuntGaveUp;
    }

    @Override
    public int bedsInBag() {
        return bagBeds;
    }

    @Override
    public boolean spawnBedOwed() {
        return state != null && adris.altoclef.tasks.speedrun.gamer.end.EndGear.wantSpawnBed(state, adris.altoclef.tasks.speedrun.gamer.config.GamerConfigs.get().end);
    }

    @Override
    public boolean goldOreKnown() {
        return goldOreKnown;
    }

    @Override
    public boolean diamondHuntGaveUp() {
        return state != null && state.diamondHuntGaveUp;
    }

    @Override
    public boolean earlyLoadInFlight() {
        return state != null && FurnacePlan.earlyLoadInFlight(state.earlyLoadTick, gameTime);
    }

    @Override
    public boolean creditsShown() {
        return credits;
    }

    @Override
    public int inventoryFingerprint() {
        return fingerprint;
    }

    @Override
    public java.util.Map<Item, Integer> inventoryCounts() {
        return counts;
    }
}
